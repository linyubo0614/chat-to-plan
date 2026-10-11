import json
import os
import sqlite3
import time
import uuid
from contextlib import contextmanager

from .models import Interpretation
from . import planner


class Conflict(Exception):
    pass


class Store:
    """Durable single-owner inbox; leases recover work after a crashed process."""

    def __init__(self, path, timezone="Asia/Shanghai"):
        self.path = path
        self.timezone = timezone
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
            columns = {row[1] for row in db.execute("PRAGMA table_info(tasks)")}
            if "revision" not in columns:
                db.execute("ALTER TABLE tasks ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
            planner.initialize(db,timezone)
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

    def ingest_plan(self, external_id, owner, text, result: Interpretation):
        # Persist the original validated interpretation for retry comparison even
        # after a task is edited or cancelled. The message and tasks commit together.
        context = {"interpretation": result.model_dump(mode="json")}
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row, created = self._ingest(db,"openclaw",external_id,owner,text,context)
            if created:
                self._complete(db,row,result)
        if created and hasattr(self,"delivery_signal"):
            self.delivery_signal()
        return row["id"], created

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
            self._complete(db,row,result)
        if hasattr(self,"delivery_signal"):
            self.delivery_signal()

    def _complete(self, db, row, result):
        for item in result.items:
            status = "needs_clarification" if item.clarification else "written" if item.target == "planner" else "blocked"
            reason = item.clarification or (
                "等待 HITA 兼容版连接并完成后台同步；尚未写入手机。" if item.target == "hita"
                else "已保存到对话计划；手机联网后可查看。"
            )
            key = uuid.uuid4().hex
            db.execute("INSERT INTO tasks(id,message_id,target,payload,status,reason) VALUES(?,?,?,?,?,?)", (key,row["id"],item.target,item.model_dump_json(),status,reason))
            if item.target == "planner" and not item.clarification:
                planner.save_task(db,key,row["owner"],item,self.timezone)
        db.execute("UPDATE messages SET status='parsed',question=?,lease=0,error=NULL WHERE id=?", (result.question,row["id"]))
        if row["source"] == "wechat":
            lines = ["已完成内容分析，记录状态如下："]
            for item in result.items:
                lines.append(f"• {item.title} → {'HITA' if item.target=='hita' else '对话计划'}" + (f"：{item.clarification}" if item.clarification else "（已保存，手机联网查看）" if item.target=="planner" else "（等待连接端写入并核对）"))
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
            row = db.execute("SELECT id,source,text,created,status,attempts,error,question FROM messages WHERE id=? AND owner=?", (key,owner)).fetchone()
            if not row:
                return None
            result = dict(row)
            receipt = db.execute("SELECT status,error FROM outbox WHERE id=?", (key,)).fetchone()
            result["reply"] = dict(receipt) if receipt else None
            result["tasks"] = [dict(x) for x in db.execute("SELECT id,target,payload,status,reason,revision FROM tasks WHERE message_id=? ORDER BY rowid", (key,))]
            for task in result["tasks"]:
                task["payload"] = json.loads(task["payload"])
            return result

    def list_messages(self, owner, limit=20, before=None):
        with self.connection() as db:
            rows = db.execute("""SELECT rowid AS cursor,id,source,substr(text,1,300) AS text,
                created,status,error,question FROM messages
                WHERE owner=? AND (? IS NULL OR rowid<?) ORDER BY rowid DESC LIMIT ?""",
                (owner,before,before,limit+1)).fetchall()
            more = len(rows) > limit
            entries = [dict(row) for row in rows[:limit]]
            for entry in entries:
                entry["tasks"] = {row[0]:row[1] for row in db.execute(
                    "SELECT status,count(*) FROM tasks WHERE message_id=? GROUP BY status",(entry["id"],))}
            return {"messages":entries,"next_cursor":entries[-1]["cursor"] if more else None}

    def update_task(self, key, owner, revision, item=None, cancel=False):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT tasks.* FROM tasks JOIN messages ON tasks.message_id=messages.id WHERE tasks.id=? AND messages.owner=?",(key,owner)).fetchone()
            if row is None:
                return None
            if row["revision"] != revision:
                raise Conflict("Record changed; refresh before editing")
            if row["status"] not in {"blocked","needs_clarification"}:
                raise Conflict("Only records awaiting delivery can be changed")
            if cancel:
                db.execute("UPDATE tasks SET status='cancelled',reason='已取消；未写入目标应用。',revision=revision+1 WHERE id=?",(key,))
            else:
                status = "needs_clarification" if item.clarification else "written" if item.target=="planner" else "blocked"
                reason = item.clarification or ("等待 HITA 兼容版连接并完成后台同步；尚未写入手机。" if item.target == "hita" else "已保存到对话计划；手机联网后可查看。")
                db.execute("UPDATE tasks SET target=?,payload=?,status=?,reason=?,revision=revision+1 WHERE id=?",
                    (item.target,item.model_dump_json(),status,reason,key))
                if item.target=="planner" and not item.clarification:
                    planner.save_task(db,key,owner,item,self.timezone)
            if hasattr(self,"delivery_signal"):
                self.delivery_signal()
            return row["message_id"]

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
