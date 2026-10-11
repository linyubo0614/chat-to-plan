import json
import os
import sqlite3
import time
import uuid
from contextlib import contextmanager

from .models import Interpretation


class Conflict(Exception):
    pass


class Store:
    """Durable single-owner inbox; leases recover work after a crashed process."""

    def __init__(self, path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with self.connection() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY, source TEXT NOT NULL, external_id TEXT NOT NULL,
                    owner TEXT NOT NULL, text TEXT NOT NULL, context TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
                    available REAL NOT NULL DEFAULT 0, lease REAL NOT NULL DEFAULT 0,
                    error TEXT, question TEXT, created REAL NOT NULL,
                    UNIQUE(source, external_id)
                );
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY, message_id TEXT NOT NULL REFERENCES messages(id),
                    target TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL,
                    reason TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS events (
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
                    available REAL NOT NULL DEFAULT 0, lease REAL NOT NULL DEFAULT 0, error TEXT
                );
                CREATE TABLE IF NOT EXISTS cursors (id TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS outbox (
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
                    available REAL NOT NULL DEFAULT 0, lease REAL NOT NULL DEFAULT 0, error TEXT
                );
            """)
        os.chmod(path, 0o600)

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            with db:
                yield db
        finally:
            db.close()

    def ingest(self, source, external_id, owner, text, context=None):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            return self._ingest(db, source, external_id, owner, text, context or {})

    def _ingest(self, db, source, external_id, owner, text, context):
        existing = db.execute("SELECT * FROM messages WHERE source=? AND external_id=?", (source, external_id)).fetchone()
        if existing:
            if existing["text"] != text or existing["owner"] != owner or json.loads(existing["context"]) != context:
                raise Conflict("Message ID reused with different content")
            return dict(existing), False
        key = uuid.uuid4().hex
        db.execute("INSERT INTO messages(id,source,external_id,owner,text,context,created) VALUES(?,?,?,?,?,?,?)",
                   (key, source, external_id, owner, text, json.dumps(context), time.time()))
        return dict(db.execute("SELECT * FROM messages WHERE id=?", (key,)).fetchone()), True

    def claim(self, table):
        assert table in {"messages", "events", "outbox"}
        now = time.time()
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            # Exhausted crash leases must not remain stuck in processing forever.
            db.execute(f"UPDATE {table} SET status='failed',error='retry_exhausted' WHERE attempts>=3 AND status='processing' AND lease<?", (now,))
            row = db.execute(f"SELECT * FROM {table} WHERE attempts<3 AND ((status IN ('pending','retry') AND available<=?) OR (status='processing' AND lease<?)) ORDER BY rowid LIMIT 1", (now, now)).fetchone()
            if not row:
                return None
            db.execute(f"UPDATE {table} SET status='processing',attempts=attempts+1,lease=? WHERE id=?", (now + 180, row["id"]))
            return dict(db.execute(f"SELECT * FROM {table} WHERE id=?", (row["id"],)).fetchone())

    def fail(self, table, row, code, permanent=False):
        assert table in {"messages", "events", "outbox"}
        with self.connection() as db:
            terminal = permanent or row["attempts"] >= 3
            changed = db.execute(f"UPDATE {table} SET status=?,error=?,available=?,lease=0 WHERE id=? AND attempts=? AND status='processing'",
                       ("failed" if permanent or row["attempts"] >= 3 else "retry", code, time.time()+2**row["attempts"]*5, row["id"], row["attempts"]))
            if changed.rowcount and table == "messages" and terminal and row["source"] == "wechat":
                self._outbox(db, row, "这条消息暂时未能完成分析，也没有写入任何目标应用。请检查服务配置或稍后重试。")

    def history(self, owner, current_id):
        with self.connection() as db:
            rows = db.execute("SELECT text,question FROM messages WHERE owner=? AND id!=? AND status='parsed' ORDER BY created DESC LIMIT 5", (owner, current_id)).fetchall()
            return [dict(x) for x in reversed(rows)]

    def complete(self, row, result: Interpretation):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            # Lease fencing prevents a late worker from duplicating recovered work.
            valid = db.execute("SELECT 1 FROM messages WHERE id=? AND status='processing' AND attempts=?", (row["id"], row["attempts"])).fetchone()
            if not valid:
                return
            for item in result.items:
                status = "needs_clarification" if item.clarification else "blocked"
                reason = item.clarification or (
                    "HITA 未提供已验证的外部写入接口；尚未写入手机。" if item.target == "hita"
                    else "番茄计划写入接口尚未验证；尚未写入应用。"
                )
                db.execute("INSERT INTO tasks VALUES(?,?,?,?,?,?)", (uuid.uuid4().hex,row["id"],item.target,item.model_dump_json(),status,reason))
            db.execute("UPDATE messages SET status='parsed',question=?,lease=0,error=NULL WHERE id=?", (result.question,row["id"]))
            if row["source"] == "wechat":
                lines = ["已完成内容分析；尚未写入目标应用。"]
                for item in result.items:
                    lines.append(f"• {item.title} → {'HITA' if item.target=='hita' else '番茄计划'}" + (f"：{item.clarification}" if item.clarification else "（等待接入）"))
                if result.question:
                    lines.append(result.question)
                self._outbox(db, row, "\n".join(lines))

    def _outbox(self, db, row, text):
        context = json.loads(row["context"])
        payload = {"touser":context["external_userid"], "open_kfid":context["open_kfid"],
                   "msgid": row["id"], "msgtype":"text", "text":{"content":text[:1800]}}
        db.execute("INSERT OR IGNORE INTO outbox(id,payload) VALUES(?,?)", (row["id"], json.dumps(payload,ensure_ascii=False)))

    def get_message(self, key, owner):
        with self.connection() as db:
            row = db.execute("SELECT id,status,attempts,error,question FROM messages WHERE id=? AND owner=?", (key,owner)).fetchone()
            if not row:
                return None
            result = dict(row)
            receipt = db.execute("SELECT status,error FROM outbox WHERE id=?", (key,)).fetchone()
            result["reply"] = dict(receipt) if receipt else None
            result["tasks"] = [dict(x) for x in db.execute("SELECT id,target,payload,status,reason FROM tasks WHERE message_id=?", (key,))]
            for task in result["tasks"]:
                task["payload"] = json.loads(task["payload"])
            return result

    def retry_message(self, key, owner):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT status FROM messages WHERE id=? AND owner=?", (key,owner)).fetchone()
            if row is None:
                return None
            if row[0] != "failed":
                raise Conflict("Only failed messages can be retried")
            db.execute("UPDATE messages SET status='pending',attempts=0,error=NULL,available=0,lease=0 WHERE id=?", (key,))
            # A subsequent analysis can produce a new truthful result receipt.
            db.execute("DELETE FROM outbox WHERE id=?", (key,))
            return {"id":key, "status":"pending"}

    def get_task(self, key, owner):
        with self.connection() as db:
            row = db.execute("SELECT tasks.*,messages.created FROM tasks JOIN messages ON tasks.message_id=messages.id WHERE tasks.id=? AND messages.owner=?", (key,owner)).fetchone()
            return dict(row) if row else None

    def enqueue_event(self, key, payload):
        with self.connection() as db:
            db.execute("INSERT OR IGNORE INTO events(id,payload) VALUES(?,?)", (key,json.dumps(payload)))

    def cursor(self, key):
        with self.connection() as db:
            row = db.execute("SELECT value FROM cursors WHERE id=?", (key,)).fetchone()
            return row[0] if row else ""

    def save_sync_page(self, event, messages, cursor):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            for msg in messages:
                self._ingest(db, **msg)
            db.execute("INSERT INTO cursors VALUES(?,?) ON CONFLICT(id) DO UPDATE SET value=excluded.value", (event["open_kfid"],cursor))

    def finish_job(self, table, row):
        assert table in {"events", "outbox"}
        with self.connection() as db:
            db.execute(f"UPDATE {table} SET status='done',lease=0 WHERE id=? AND attempts=?", (row["id"],row["attempts"]))

    def counts(self):
        with self.connection() as db:
            return {table:{r[0]:r[1] for r in db.execute(f"SELECT status,count(*) FROM {table} GROUP BY status")} for table in ("messages","tasks","events","outbox")}
