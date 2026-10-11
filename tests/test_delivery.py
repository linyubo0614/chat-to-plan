import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi.testclient import TestClient

from backend.app import create_app
from backend.config import Settings
from backend.models import Interpretation,Item


class Parser:
    def parse(self,*args):
        return Interpretation(items=[Item(kind="memo",title="笔记",category="学习"),Item(kind="course",title="高数",
            starts_at="2026-10-14T15:00:00+08:00",ends_at="2026-10-14T16:00:00+08:00")])


class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.settings=Settings(api_token="a"*32,db_path=Path(self.tmp.name)/"db")
        self.app=create_app(self.settings,Parser(),run_worker=False)
        self.client=TestClient(self.app)
        self.headers={"Authorization":"Bearer "+self.settings.api_token}
        self.store=self.app.state.store

    def tearDown(self):
        self.client.close();self.tmp.cleanup()

    def seed(self):
        self.client.post("/v1/messages",headers=self.headers,json={"message_id":"one","text":"记一条笔记，下周三高数"})
        self.app.state.worker.run_once()

    def claim(self,target="hita",client="phone"):
        return self.client.post(f"/v1/delivery/{target}/claim",headers=self.headers,json={"client_id":client})

    def receipt(self,claim,client="phone"):
        return {"client_id":client,"lease_token":claim["lease_token"],"revision":claim["task"]["revision"],"external_id":"target-event-id"}

    def test_authenticated_claim_and_readback_receipt(self):
        self.seed()
        self.assertEqual(self.client.post("/v1/delivery/hita/claim",json={"client_id":"phone"}).status_code,401)
        claim=self.claim().json();key=claim["task"]["id"]
        self.assertEqual(self.store.get_task(key,"owner")["status"],"delivering")
        self.assertIsNone(self.claim(client="second-phone").json()["task"])
        self.assertEqual(self.client.delete(f"/v1/tasks/{key}?revision=0",headers=self.headers).status_code,409)
        bad=self.receipt(claim);bad["lease_token"]="z"*40
        self.assertEqual(self.client.post(f"/v1/delivery/{key}/ack",headers=self.headers,json=bad).status_code,409)
        self.assertEqual(self.store.get_task(key,"owner")["status"],"delivering")
        ack=self.client.post(f"/v1/delivery/{key}/ack",headers=self.headers,json=self.receipt(claim))
        self.assertEqual(ack.json(),{"status":"written","duplicate":False})
        self.assertTrue(self.client.post(f"/v1/delivery/{key}/ack",headers=self.headers,json=self.receipt(claim)).json()["duplicate"])
        self.assertIsNone(self.claim().json()["task"])
        restored=create_app(self.settings,run_worker=False)
        self.assertEqual(restored.state.store.get_task(key,"owner")["status"],"written")

    def test_expired_lease_recovery_fences_old_receipt(self):
        self.seed();old=self.claim().json();key=old["task"]["id"]
        with self.store.connection() as db: db.execute("UPDATE deliveries SET lease=0 WHERE task_id=?",(key,))
        new=self.claim(client="reconnected-phone").json()
        self.assertEqual(new["task"]["id"],key)
        self.assertEqual(self.client.post(f"/v1/delivery/{key}/ack",headers=self.headers,json=self.receipt(old)).status_code,409)
        self.assertEqual(self.client.post(f"/v1/delivery/{key}/ack",headers=self.headers,json=self.receipt(new,"reconnected-phone")).status_code,200)

    def test_pause_cancel_and_unsupported_fields(self):
        self.seed();claim=self.claim().json();key=claim["task"]["id"]
        self.client.put("/v1/settings",headers=self.headers,json={"enabled":False})
        self.assertTrue(self.claim().json()["paused"])
        self.client.put("/v1/settings",headers=self.headers,json={"enabled":True})
        with self.store.connection() as db: db.execute("UPDATE deliveries SET lease=0 WHERE task_id=?",(key,))
        task=self.claim().json()["task"]
        self.assertIsNotNone(task)
        # Cancelled tasks cannot enter the queue; unsupported recurrence/reminders stay unwritten.
        with self.store.connection() as db:
            db.execute("UPDATE tasks SET status='cancelled' WHERE id=?",(task["id"],))
        self.assertIsNone(self.claim().json()["task"])
        with self.store.connection() as db:
            row=db.execute("SELECT message_id FROM tasks WHERE id=?",(key,)).fetchone()
            item=Item(kind="course",title="重复课程",starts_at="2026-10-14T15:00:00+08:00",ends_at="2026-10-14T16:00:00+08:00",recurrence_description="每周三")
            db.execute("INSERT INTO tasks(id,message_id,target,payload,status,reason) VALUES('repeat',?,'hita',?,'blocked','pending')",(row[0],item.model_dump_json()))
        self.assertIsNone(self.claim().json()["task"])
        self.assertEqual(self.store.get_task("repeat","owner")["status"],"blocked")

    def test_bounded_recovery_and_manual_retry(self):
        self.seed();claim=None
        for _ in range(5):
            claim=self.claim().json();self.assertIsNotNone(claim["task"])
            with self.store.connection() as db: db.execute("UPDATE deliveries SET lease=0")
        key=claim["task"]["id"]
        self.assertIsNone(self.claim().json()["task"])
        self.assertEqual(self.client.post(f"/v1/delivery/{key}/retry",headers=self.headers).status_code,200)
        renewed=self.claim().json();self.assertEqual(renewed["task"]["id"],key)
        self.assertEqual(self.client.post(f"/v1/delivery/{key}/retry",headers=self.headers).status_code,409)

    def test_long_poll_wakes_on_new_task(self):
        ready=threading.Event()
        def wait():
            ready.set()
            return self.client.post("/v1/delivery/hita/claim?wait=10",headers=self.headers,json={"client_id":"phone"}).json()
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(wait);ready.wait(2)
            self.seed()
            result=future.result(timeout=2)
        self.assertIsNotNone(result["task"])

    def test_long_poll_wakes_on_pause(self):
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(lambda:self.client.post("/v1/delivery/hita/claim?wait=10",headers=self.headers,json={"client_id":"phone"}).json())
            self.client.put("/v1/settings",headers=self.headers,json={"enabled":False})
            self.assertTrue(future.result(timeout=2)["paused"])

    def test_concurrent_clients_only_claim_once(self):
        self.seed()
        with ThreadPoolExecutor(max_workers=8) as pool:
            results=list(pool.map(lambda i:self.claim(client="phone"+str(i)).json(),range(8)))
        self.assertEqual(sum(row["task"] is not None for row in results),1)


if __name__=="__main__": unittest.main()
