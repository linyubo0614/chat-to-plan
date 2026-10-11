"""Single-owner delivery leases. Clients acknowledge only after target readback."""
import hashlib
import hmac
import json
import secrets
import threading
import time

from .models import Item
from .store import Conflict


class Delivery:
    def __init__(self,store):
        self.store = store
        self.condition = threading.Condition()
        self.generation = 0
        store.delivery_signal = self.signal
        with store.connection() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS deliveries (
                task_id TEXT PRIMARY KEY REFERENCES tasks(id),client_id TEXT NOT NULL,
                token_hash TEXT NOT NULL,revision INTEGER NOT NULL,lease REAL NOT NULL,
                attempts INTEGER NOT NULL,external_id TEXT)""")

    def signal(self):
        with self.condition:
            self.generation += 1
            self.condition.notify_all()

    def wait_claim(self,target,owner,client_id,seconds,enabled):
        deadline = time.monotonic()+seconds
        while True:
            with self.condition:
                generation = self.generation
            if not enabled():
                return {"task":None,"paused":True}
            result = self.claim(target,owner,client_id)
            remaining = deadline-time.monotonic()
            if result["task"] is not None or remaining<=0:
                return result
            with self.condition:
                self.condition.wait_for(lambda:self.generation!=generation,timeout=remaining)

    def claim(self,target,owner,client_id):
        now = time.time()
        with self.store.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute("""SELECT t.*,d.lease,d.attempts FROM tasks t
                JOIN messages m ON t.message_id=m.id LEFT JOIN deliveries d ON d.task_id=t.id
                WHERE m.owner=? AND t.target=? AND t.status IN ('blocked','delivering')
                AND (d.lease IS NULL OR d.lease<?) ORDER BY t.rowid""",(owner,target,now)).fetchall()
            for row in rows:
                item = Item.model_validate_json(row["payload"])
                # Only supported complete single events/memos are eligible.
                if item.clarification or item.recurrence_description or item.reminder_minutes is not None:
                    if not item.clarification:
                        db.execute("UPDATE tasks SET reason='重复规则或指定提醒尚未实现；未写入。' WHERE id=?",(row["id"],))
                    continue
                if target == "hita" and (not item.starts_at or not item.ends_at):
                    continue
                if (row["attempts"] or 0) >= 5:
                    db.execute("UPDATE tasks SET reason='交付多次未确认；需要检查连接端并重试。' WHERE id=?",(row["id"],))
                    continue
                token = secrets.token_urlsafe(32)
                db.execute("""INSERT INTO deliveries VALUES(?,?,?,?,?,?,NULL)
                    ON CONFLICT(task_id) DO UPDATE SET client_id=excluded.client_id,
                    token_hash=excluded.token_hash,revision=excluded.revision,lease=excluded.lease,
                    attempts=excluded.attempts,external_id=NULL""",
                    (row["id"],client_id,hashlib.sha256(token.encode()).hexdigest(),row["revision"],now+180,(row["attempts"] or 0)+1))
                db.execute("UPDATE tasks SET status='delivering',reason='等待目标端写入并核对；尚未确认成功。' WHERE id=?",(row["id"],))
                return {"task":{"id":row["id"],"revision":row["revision"],"payload":json.loads(row["payload"])},"lease_token":token,"lease_seconds":180}
            return {"task":None}

    def acknowledge(self,key,owner,receipt):
        with self.store.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("""SELECT t.status,t.revision,d.* FROM tasks t JOIN messages m ON t.message_id=m.id
                JOIN deliveries d ON d.task_id=t.id WHERE t.id=? AND m.owner=?""",(key,owner)).fetchone()
            if row is None:
                return None
            if row["client_id"] != receipt.client_id or row["revision"] != receipt.revision or not hmac.compare_digest(
                row["token_hash"],hashlib.sha256(receipt.lease_token.encode()).hexdigest()):
                raise Conflict("Delivery lease changed; claim and verify again")
            if row["status"] == "written":
                if row["external_id"] != receipt.external_id:
                    raise Conflict("Receipt differs from recorded target")
                return {"status":"written","duplicate":True}
            if row["status"] != "delivering":
                raise Conflict("Record is not awaiting a receipt")
            db.execute("UPDATE deliveries SET external_id=? WHERE task_id=?",(receipt.external_id,key))
            db.execute("UPDATE tasks SET status='written',reason='目标端已报告写入并读回核对。' WHERE id=?",(key,))
            message = db.execute("SELECT m.*,t.target,t.payload FROM tasks t JOIN messages m ON t.message_id=m.id WHERE t.id=?",(key,)).fetchone()
            if message["source"] == "wechat":
                context = json.loads(message["context"])
                title = json.loads(message["payload"])["title"]
                reply_id = "delivery-"+key
                payload = {"touser":context["external_userid"],"open_kfid":context["open_kfid"],"msgid":reply_id,
                    "msgtype":"text","text":{"content":f"已收到目标端写入并核对的确认：{title} → {'HITA兼容版' if message['target']=='hita' else '番茄计划'}。"[:1800]}}
                db.execute("INSERT OR IGNORE INTO outbox(id,payload) VALUES(?,?)",(reply_id,json.dumps(payload,ensure_ascii=False)))
            return {"status":"written","duplicate":False}

    def retry(self,key,owner):
        with self.store.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("""SELECT t.status,d.lease FROM tasks t JOIN messages m ON t.message_id=m.id
                JOIN deliveries d ON d.task_id=t.id WHERE t.id=? AND m.owner=?""",(key,owner)).fetchone()
            if row is None:
                return None
            if row["status"] != "delivering" or row["lease"] > time.time():
                raise Conflict("Only expired unconfirmed deliveries can be retried")
            db.execute("UPDATE deliveries SET attempts=0,lease=0,token_hash='' WHERE task_id=?",(key,))
            db.execute("UPDATE tasks SET reason='等待目标端重新核对交付结果。' WHERE id=?",(key,))
            return {"status":"delivering"}
