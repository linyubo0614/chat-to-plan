import base64
import hashlib
import json
import struct
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from dataclasses import replace
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import httpx
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from fastapi.testclient import TestClient
from pydantic import ValidationError

from backend.app import create_app
from backend.calendar import export_ics
from backend.config import Settings
from backend.models import Interpretation, Item
from backend.parser import DeepSeekParser, ParserUnavailable
from backend.store import Store
from backend.wechat import CallbackRejected, WeChatAPI, WeChatCrypto
from backend.worker import Worker


class FakeParser:
    def parse(self, text, history):
        return Interpretation(items=[Item(kind="todo",title="买打印纸",category="生活"),
            Item(kind="course",title="高数",starts_at="2026-10-14T15:00:00+08:00",ends_at="2026-10-14T16:00:00+08:00",location="二教301")])


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.settings = Settings(api_token="test-token-"+"a"*32,db_path=Path(self.tmp.name)/"assistant.db")
        self.app = create_app(self.settings,FakeParser(),run_worker=False)
        self.client = TestClient(self.app)
        self.headers = {"Authorization":f"Bearer {self.settings.api_token}"}
        self.store = self.app.state.store

    def tearDown(self):
        self.client.close()
        self.tmp.cleanup()

    def submit(self,text="记下买打印纸，周三15点高数",key="m1"):
        return self.client.post("/v1/messages",headers=self.headers,json={"message_id":key,"text":text})

    def test_authenticated_ingress_and_idempotency(self):
        self.assertEqual(self.client.get("/v1/status").status_code,401)
        first = self.submit()
        self.assertEqual(first.status_code,202)
        duplicate = self.submit()
        self.assertTrue(duplicate.json()["duplicate"])
        self.assertEqual(first.json()["id"],duplicate.json()["id"])
        self.assertEqual(self.submit("different").status_code,409)
        self.assertEqual(self.submit("   ","blank").status_code,422)
        self.assertEqual(self.submit("x"*8001,"large").status_code,422)

    def test_current_deepseek_model_is_preserved_in_phone_settings(self):
        for model in ['deepseek-flash','deepseek-v4-flash','deepseek-v4-pro']:
            response=self.client.put('/v1/settings',headers=self.headers,json={'model':model})
            self.assertEqual(response.status_code,200)
            self.assertEqual(self.client.get('/v1/settings',headers=self.headers).json()['model'],model)
        self.assertEqual(self.client.put('/v1/settings',headers=self.headers,json={'model':'openai/gpt-6'}).status_code,422)

    def test_app_settings_pause_resume_and_encrypted_key_persistence(self):
        secret = "sk-test-credential-never-returned"
        self.assertEqual(self.client.put("/v1/settings",json={"enabled":False}).status_code,401)
        response = self.client.put("/v1/settings",headers=self.headers,json={"enabled":False,"deepseek_api_key":secret,"model":"deepseek-reasoner"})
        self.assertEqual(response.status_code,200)
        self.assertNotIn(secret,response.text)
        self.assertTrue(response.json()["deepseek_key_configured"])
        with self.store.connection() as db:
            raw = db.execute("SELECT value FROM runtime").fetchone()[0]
        self.assertNotIn(secret,raw)
        key = self.submit().json()["id"]
        self.assertFalse(self.app.state.worker.run_once())
        self.assertEqual(self.store.get_message(key,"owner")["status"],"pending")
        self.client.put("/v1/settings",headers=self.headers,json={"enabled":True})
        self.app.state.worker.run_once()
        self.assertEqual(self.store.get_message(key,"owner")["status"],"parsed")
        from backend.runtime import Runtime
        restored = Runtime(Store(self.settings.db_path),self.settings)
        self.assertEqual(restored.snapshot()[1].deepseek_key,secret)
        self.client.put("/v1/settings",headers=self.headers,json={"deepseek_api_key":""})
        self.assertFalse(restored.public()["deepseek_key_configured"])
        self.assertEqual(self.client.put("/v1/settings",headers=self.headers,json={"model":"unknown"}).status_code,422)

    def test_real_api_worker_persistence_and_blocked_delivery(self):
        key = self.submit().json()["id"]
        self.app.state.worker.run_once()
        result = self.client.get(f"/v1/messages/{key}",headers=self.headers).json()
        self.assertEqual(result["status"],"parsed")
        self.assertEqual([x["target"] for x in result["tasks"]],["planner","hita"])
        self.assertEqual([x["status"] for x in result["tasks"]],["written","blocked"])
        restored = Store(self.settings.db_path)
        self.assertEqual(len(restored.get_message(key,"owner")["tasks"]),2)
        self.assertIsNone(restored.get_message(key,"another-owner"))
        task_id = result["tasks"][1]["id"]
        export = self.client.get(f"/v1/tasks/{task_id}/calendar.ics",headers=self.headers)
        self.assertEqual(export.status_code,200)
        self.assertIn("DTSTART:20261014T070000Z",export.text)
        self.assertIn("X-HITA-TYPE:class",export.text)
        self.assertEqual(restored.get_task(task_id,"owner")["status"],"blocked")

    def test_concurrent_duplicates_only_create_one_message(self):
        def ingest(_):
            return self.store.ingest("api","parallel","owner","one")
        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(ingest,range(16)))
        self.assertEqual(len({row[0]["id"] for row in results}),1)
        self.assertEqual(sum(row[1] for row in results),1)

    def test_history_pagination_and_owner_isolation(self):
        keys = [self.submit("消息"+str(i),"history"+str(i)).json()["id"] for i in range(5)]
        self.store.ingest("api","private","someone-else","private text")
        self.assertEqual(self.client.get("/v1/messages").status_code,401)
        page = self.client.get("/v1/messages?limit=2",headers=self.headers).json()
        self.assertEqual([x["id"] for x in page["messages"]],list(reversed(keys))[:2])
        self.submit("新消息","new-history")
        older = self.client.get(f'/v1/messages?limit=2&before={page["next_cursor"]}',headers=self.headers).json()
        self.assertEqual([x["id"] for x in older["messages"]],[keys[2],keys[1]])
        last = self.client.get(f'/v1/messages?limit=2&before={older["next_cursor"]}',headers=self.headers).json()
        self.assertEqual([x["id"] for x in last["messages"]],[keys[0]])
        self.assertIsNone(last["next_cursor"])
        self.assertEqual(self.client.get("/v1/messages?limit=51",headers=self.headers).status_code,422)
        self.assertEqual(self.client.get("/v1/messages?before=0",headers=self.headers).status_code,422)

    def test_edit_cancel_revision_and_export(self):
        message_id = self.submit().json()["id"]
        self.app.state.worker.run_once()
        row = self.store.get_message(message_id,"owner")
        task = row["tasks"][1]
        item = task["payload"]
        item.update(title="修改后的高数",category="学习",ends_at=None,clarification=None)
        url = f'/v1/tasks/{task["id"]}'
        response = self.client.put(url,headers=self.headers,json={"revision":0,"item":item})
        updated = response.json()["tasks"][1]
        self.assertEqual(updated["revision"],1)
        self.assertEqual(updated["status"],"needs_clarification")
        self.assertEqual(updated["payload"]["title"],"修改后的高数")
        self.assertEqual(self.client.get(url+"/calendar.ics",headers=self.headers).status_code,409)
        item.update(ends_at="2026-10-14T16:30:00+08:00",clarification=None)
        self.assertEqual(self.client.put(url,headers=self.headers,json={"revision":0,"item":item}).status_code,409)
        response = self.client.put(url,headers=self.headers,json={"revision":1,"item":item})
        self.assertEqual(response.json()["tasks"][1]["status"],"blocked")
        self.assertEqual(self.client.get(url+"/calendar.ics",headers=self.headers).status_code,200)
        self.assertEqual(self.client.delete(url+"?revision=1",headers=self.headers).status_code,409)
        cancelled = self.client.delete(url+"?revision=2",headers=self.headers)
        self.assertEqual(cancelled.json()["tasks"][1]["status"],"cancelled")
        self.assertEqual(self.client.get(url+"/calendar.ics",headers=self.headers).status_code,409)
        self.assertEqual(self.client.put(url,headers=self.headers,json={"revision":3,"item":item}).status_code,409)
        self.assertEqual(len(self.store.get_message(message_id,"owner")["tasks"]),2)
        self.assertEqual(Store(self.settings.db_path).get_message(message_id,"owner")["tasks"][1]["status"],"cancelled")

    def test_edit_is_authenticated_and_validated(self):
        message_id = self.submit().json()["id"]
        self.app.state.worker.run_once()
        task = self.store.get_message(message_id,"owner")["tasks"][0]
        url = f'/v1/tasks/{task["id"]}'
        body = {"revision":0,"item":task["payload"]}
        self.assertEqual(self.client.put(url,json=body).status_code,401)
        self.assertEqual(self.client.delete(url+"?revision=0").status_code,401)
        self.assertIsNone(self.store.update_task(task["id"],"different-owner",0,cancel=True))
        self.assertEqual(self.client.put("/v1/tasks/unknown",headers=self.headers,json=body).status_code,404)
        body["item"]["starts_at"] = "2026-10-14T15:00:00"
        self.assertEqual(self.client.put(url,headers=self.headers,json=body).status_code,422)
        self.assertEqual(self.store.get_message(message_id,"owner")["tasks"][0]["revision"],0)

    def test_existing_database_migrates_without_losing_tasks(self):
        import sqlite3
        path = Path(self.tmp.name)/"legacy.db"
        with closing(sqlite3.connect(path)) as db, db:
            db.execute("CREATE TABLE tasks(id TEXT PRIMARY KEY,message_id TEXT NOT NULL,target TEXT NOT NULL,payload TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL)")
            db.execute("INSERT INTO tasks VALUES('old','message','tomato','{}','blocked','waiting')")
        migrated = Store(path)
        with migrated.connection() as db:
            old = db.execute("SELECT * FROM tasks WHERE id='old'").fetchone()
        self.assertEqual(old["revision"],0)
        self.assertEqual(old["reason"],"waiting")

    def test_expired_lease_and_late_worker_are_fenced(self):
        key = self.submit().json()["id"]
        first = self.store.claim("messages")
        self.assertIsNone(self.store.claim("messages"))
        with self.store.connection() as db:
            db.execute("UPDATE messages SET lease=0 WHERE id=?",(key,))
        second = self.store.claim("messages")
        self.store.complete(first,FakeParser().parse("",[]))
        self.assertEqual(self.store.get_message(key,"owner")["tasks"],[])
        self.store.complete(second,FakeParser().parse("",[]))
        self.assertEqual(len(self.store.get_message(key,"owner")["tasks"]),2)

    def test_missing_key_and_explicit_retry(self):
        key = self.submit().json()["id"]
        Worker(self.store,DeepSeekParser(self.settings)).run_once()
        row = self.store.get_message(key,"owner")
        self.assertEqual((row["status"],row["error"]),("failed","deepseek_not_configured"))
        self.assertEqual(row["tasks"],[])
        response = self.client.post(f"/v1/messages/{key}/retry",headers=self.headers)
        self.assertEqual(response.status_code,202)
        self.app.state.worker.run_once()
        self.assertEqual(self.store.get_message(key,"owner")["status"],"parsed")
        self.assertEqual(self.client.post(f"/v1/messages/{key}/retry",headers=self.headers).status_code,409)

    def test_transient_failures_are_bounded(self):
        class Broken:
            def parse(self,*args):
                raise ValueError("invalid output")
        key = self.submit().json()["id"]
        worker = Worker(self.store,Broken())
        for attempt in range(3):
            worker.run_once()
            with self.store.connection() as db:
                db.execute("UPDATE messages SET available=0")
        row = self.store.get_message(key,"owner")
        self.assertEqual((row["status"],row["attempts"]),("failed",3))
        self.assertFalse(worker.run_once())
        self.assertEqual(row["tasks"],[])

    def test_model_dates_and_clarification(self):
        with self.assertRaises(ValidationError):
            Item(kind="course",title="x",starts_at="2026-10-14T15:00:00")
        with self.assertRaises(ValidationError):
            Item(kind="course",title="x",starts_at="2026-10-14T15:00:00+08:00",ends_at="2026-10-14T14:00:00+08:00")
        with self.assertRaises(ValidationError):
            Interpretation(items=[])
        item = Item(kind="course",title="上课")
        self.assertIsNotNone(item.clarification)
        row,_ = self.store.ingest("api","clarify","owner","明天下午上课")
        claimed = self.store.claim("messages")
        self.store.complete(claimed,Interpretation(items=[item]))
        self.assertEqual(self.store.get_message(row["id"],"owner")["tasks"][0]["status"],"needs_clarification")

    def test_deepseek_request_and_response_contract(self):
        requests = []
        def handle(request):
            requests.append(request)
            return httpx.Response(200,json={"choices":[{"finish_reason":"stop","message":{"content":json.dumps({"items":[{"kind":"memo","title":"笔记"}]})}}]})
        configured = replace(self.settings,deepseek_key="not-a-real-key")
        client = httpx.Client(transport=httpx.MockTransport(handle))
        with patch("backend.parser.httpx.Client",return_value=client):
            result = DeepSeekParser(configured).parse("记一下笔记",[])
        self.assertEqual(result.items[0].title,"笔记")
        payload = json.loads(requests[0].content)
        self.assertEqual(payload["response_format"],{"type":"json_object"})
        self.assertIn("Asia/Shanghai",payload["messages"][0]["content"])
        self.assertEqual(requests[0].url.host,"api.deepseek.com")

    def test_calendar_escape_utf8_and_unsupported_recurrence(self):
        item = Item(kind="schedule",title="测试"*90+"\nBEGIN:VEVENT",body="a,b;c\\d",starts_at="2026-10-14T15:00:00+08:00",ends_at="2026-10-14T16:00:00+08:00")
        data = export_ics("task",item,time.time())
        self.assertTrue(all(len(line)<=75 for line in data.split(b"\r\n")))
        unfolded = data.decode().replace("\r\n ","")
        self.assertEqual(unfolded.count("\r\nBEGIN:VEVENT\r\n"),1)
        self.assertIn("\\nBEGIN:VEVENT",unfolded)
        item.recurrence_description = "每周三"
        with self.assertRaises(ValueError):
            export_ics("task",item,time.time())


class WeChatTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.settings = Settings(api_token="a"*32,db_path=Path(self.tmp.name)/"db",corp_id="corp-id",kf_secret="test-secret",callback_token="token",encoding_key=base64.b64encode(b"k"*32).decode().rstrip("="),allowed_users=("allowed",))

    def tearDown(self):
        self.tmp.cleanup()

    def encrypted(self,text,corp=None,timestamp=None):
        raw = b"r"*16 + struct.pack(">I",len(text.encode())) + text.encode() + (corp or self.settings.corp_id).encode()
        padding = 32-len(raw)%32
        raw += bytes([padding])*padding
        enc = Cipher(algorithms.AES(b"k"*32),modes.CBC(b"k"*16)).encryptor()
        encrypted = base64.b64encode(enc.update(raw)+enc.finalize()).decode()
        timestamp,nonce = timestamp or str(int(time.time())),"test-nonce"
        signature = hashlib.sha1("".join(sorted(["token",timestamp,nonce,encrypted])).encode()).hexdigest()
        return signature,timestamp,nonce,encrypted

    def test_crypto_rejects_tampering_wrong_receiver_and_stale_events(self):
        crypto = WeChatCrypto(self.settings)
        args = self.encrypted("hello")
        self.assertEqual(crypto.decrypt(*args),"hello")
        with self.assertRaises(CallbackRejected):
            crypto.decrypt("0"*40,*args[1:])
        with self.assertRaises(CallbackRejected):
            crypto.decrypt(*self.encrypted("hello",corp="wrong"))
        with self.assertRaises(CallbackRejected):
            crypto.decrypt(*self.encrypted("hello",timestamp="1"))

    def test_callback_is_durable_idempotent_and_does_not_call_remote_inline(self):
        app = create_app(self.settings,FakeParser(),run_worker=False)
        plaintext = "<xml><Event>kf_msg_or_event</Event><Token>sync-token</Token><OpenKfId>kf1</OpenKfId></xml>"
        sig,ts,nonce,encrypted = self.encrypted(plaintext)
        params = {"msg_signature":sig,"timestamp":ts,"nonce":nonce}
        with TestClient(app) as client:
            body = f"<xml><Encrypt>{encrypted}</Encrypt></xml>"
            for _ in range(2):
                self.assertEqual(client.post("/wechat/callback",params=params,content=body).status_code,200)
            self.assertEqual(app.state.store.counts()["events"],{"pending":1})
            self.assertEqual(client.post("/wechat/callback",params=params,content="x"*65537).status_code,413)
            self.assertEqual(client.post("/wechat/callback",params={**params,"msg_signature":"bad"},content=body).status_code,403)

    def test_sync_pagination_allowlist_and_truthful_receipt(self):
        calls = []
        def handle(request):
            calls.append(request.url.path)
            if request.url.path.endswith("gettoken"):
                return httpx.Response(200,json={"access_token":"fake","expires_in":7200})
            if request.url.path.endswith("sync_msg"):
                cursor = json.loads(request.content)["cursor"]
                if not cursor:
                    return httpx.Response(200,json={"next_cursor":"page1","has_more":1,"msg_list":[
                        {"msgid":"w1","origin":3,"msgtype":"text","external_userid":"allowed","text":{"content":"买纸"}},
                        {"msgid":"bad","origin":3,"msgtype":"text","external_userid":"stranger","text":{"content":"ignored"}}]})
                return httpx.Response(200,json={"next_cursor":"page2","has_more":0,"msg_list":[]})
            if request.url.path.endswith("send_msg"):
                content = json.loads(request.content)
                self.assertIn("对话计划（已保存",content["text"]["content"])
                self.assertIn("等待连接端写入并核对",content["text"]["content"])
                self.assertEqual(content["touser"],"allowed")
                return httpx.Response(200,json={"errcode":0,"msgid":"sent"})
            raise AssertionError("Unexpected request")
        api = WeChatAPI(self.settings,httpx.Client(transport=httpx.MockTransport(handle)))
        store = Store(self.settings.db_path)
        store.enqueue_event("e1",{"token":"sync","open_kfid":"kf1"})
        worker = Worker(store,FakeParser(),api)
        worker.run_once()
        self.assertEqual(store.cursor("kf1"),"page2")
        self.assertEqual(store.counts()["messages"],{"parsed":1})
        self.assertEqual(store.counts()["outbox"],{"done":1})
        self.assertEqual(calls.count("/cgi-bin/gettoken"),1)
        self.assertEqual(calls.count("/cgi-bin/kf/sync_msg"),2)
        worker.stop()

    def test_failed_analysis_sends_failure_receipt(self):
        store = Store(self.settings.db_path)
        row,_ = store.ingest("wechat","bad-key","owner","hello",{"external_userid":"allowed","open_kfid":"kf1"})
        no_key = DeepSeekParser(self.settings)
        Worker(store,no_key).run_once()
        self.assertEqual(store.get_message(row["id"],"owner")["reply"]["status"],"pending")
        with store.connection() as db:
            payload = json.loads(db.execute("SELECT payload FROM outbox").fetchone()[0])
        self.assertIn("没有写入",payload["text"]["content"])


if __name__ == "__main__":
    unittest.main()
