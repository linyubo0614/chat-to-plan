"""Owner-scoped daily tasks and classified memos, persisted in our own service."""
import hashlib
import json
import time
from datetime import date,datetime
from zoneinfo import ZoneInfo

from .models import Item,PlannerRecord


def initialize(db,timezone):
    db.executescript("""
        CREATE TABLE IF NOT EXISTS planner_records (
            id TEXT PRIMARY KEY,owner TEXT NOT NULL,source_task_id TEXT UNIQUE,
            creation_hash TEXT,kind TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,
            category TEXT NOT NULL,important INTEGER NOT NULL,urgent INTEGER NOT NULL,
            task_date TEXT,completed INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0,
            revision INTEGER NOT NULL DEFAULT 0,created REAL NOT NULL,updated REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS planner_day ON planner_records(owner,kind,task_date,deleted);
        CREATE INDEX IF NOT EXISTS planner_category ON planner_records(owner,kind,category,deleted);
    """)
    # Preserve saved assistant content when replacing the third-party destination.
    # This imports only our own database, never the user's Tomato private data.
    rows = db.execute("SELECT t.*,m.owner FROM tasks t JOIN messages m ON t.message_id=m.id WHERE t.target='tomato'").fetchall()
    for row in rows:
        item = Item.model_validate_json(row["payload"])
        if item.kind not in {"memo","todo"}:
            continue
        db.execute("UPDATE tasks SET target='planner' WHERE id=?",(row["id"],))
        if row["status"] != "cancelled" and not item.clarification:
            save_task(db,row["id"],row["owner"],item,timezone)
            db.execute("UPDATE tasks SET status='written',reason='已保存到对话计划；手机联网后可查看。' WHERE id=?",(row["id"],))


def values(record,timezone):
    day = record.task_date
    if record.kind == "todo" and day is None:
        due = getattr(record,"due_at",None)
        day = due.astimezone(ZoneInfo(timezone)).date() if due else datetime.now(ZoneInfo(timezone)).date()
    return (record.kind,record.title,record.body,record.category,int(record.important),int(record.urgent),
            day.isoformat() if record.kind == "todo" and day else None,int(getattr(record,"completed",False)))


def save_task(db,key,owner,item,timezone):
    now = time.time()
    db.execute("""INSERT OR IGNORE INTO planner_records
        (id,owner,source_task_id,kind,title,body,category,important,urgent,task_date,completed,created,updated)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",(key,owner,key,*values(item,timezone),now,now))


def public(row):
    result = dict(row)
    for key in ("owner","creation_hash","source_task_id","deleted"):
        result.pop(key,None)
    for key in ("important","urgent","completed"):
        result[key] = bool(result[key])
    return result


class Planner:
    def __init__(self,store):
        self.store = store

    def create(self,owner,body):
        from .store import Conflict
        payload = body.model_dump(mode="json",exclude={"record_id"})
        fingerprint = hashlib.sha256(json.dumps(payload,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        with self.store.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT * FROM planner_records WHERE id=?",(body.record_id,)).fetchone()
            if existing:
                if existing["owner"] != owner or existing["creation_hash"] != fingerprint:
                    raise Conflict("Record ID reused with different content")
                # Never resurrect a deleted or edited record on network retry.
                return {"record":public(existing),"duplicate":True,"deleted":bool(existing["deleted"])}
            now = time.time()
            db.execute("""INSERT INTO planner_records
                (id,owner,creation_hash,kind,title,body,category,important,urgent,task_date,completed,created,updated)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",(body.record_id,owner,fingerprint,*values(body,self.store.timezone),now,now))
            row = db.execute("SELECT * FROM planner_records WHERE id=?",(body.record_id,)).fetchone()
            return {"record":public(row),"duplicate":False,"deleted":False}

    def get(self,key,owner):
        with self.store.connection() as db:
            row = db.execute("SELECT * FROM planner_records WHERE id=? AND owner=? AND deleted=0",(key,owner)).fetchone()
            return public(row) if row else None

    def list(self,owner,kind,day=None,category=None,q="",limit=50,before=None):
        terms = ["owner=?","kind=?","deleted=0"]
        parameters = [owner,kind]
        if day:
            terms.append("task_date=?");parameters.append(day.isoformat())
        if category is not None:
            terms.append("category=?");parameters.append(category)
        if q:
            terms.append("(title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\')")
            escaped = q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
            parameters.extend(["%"+escaped+"%"]*2)
        if before is not None:
            terms.append("rowid<?");parameters.append(before)
        parameters.append(limit+1)
        with self.store.connection() as db:
            rows = db.execute("SELECT rowid AS cursor,* FROM planner_records WHERE "+" AND ".join(terms)+" ORDER BY rowid DESC LIMIT ?",parameters).fetchall()
            more = len(rows)>limit
            records = [public(row) for row in rows[:limit]]
            return {"records":records,"next_cursor":records[-1]["cursor"] if more else None}

    def categories(self,owner,kind):
        with self.store.connection() as db:
            rows = db.execute("SELECT category,count(*) AS count FROM planner_records WHERE owner=? AND kind=? AND deleted=0 GROUP BY category ORDER BY category",(owner,kind)).fetchall()
            return {"categories":[dict(row) for row in rows]}

    def calendar(self,owner,month):
        first = date.fromisoformat(month+"-01")
        next_month = date(first.year+1,1,1) if first.month == 12 else date(first.year,first.month+1,1)
        with self.store.connection() as db:
            rows = db.execute("""SELECT task_date AS day,count(*) AS total,sum(completed) AS completed
                FROM planner_records WHERE owner=? AND kind='todo' AND deleted=0 AND task_date>=? AND task_date<?
                GROUP BY task_date ORDER BY task_date""",(owner,first.isoformat(),next_month.isoformat())).fetchall()
            return {"month":month,"days":[dict(row) for row in rows]}

    def update(self,key,owner,revision,body=None,delete=False):
        from .store import Conflict
        with self.store.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            current = db.execute("SELECT * FROM planner_records WHERE id=? AND owner=? AND deleted=0",(key,owner)).fetchone()
            if current is None:
                return None
            if current["revision"] != revision:
                raise Conflict("Record changed; refresh before editing")
            if delete:
                db.execute("UPDATE planner_records SET deleted=1,revision=revision+1,updated=? WHERE id=?",(time.time(),key))
            else:
                db.execute("""UPDATE planner_records SET kind=?,title=?,body=?,category=?,important=?,urgent=?,
                    task_date=?,completed=?,revision=revision+1,updated=? WHERE id=?""",(*values(body,self.store.timezone),time.time(),key))
            if current["source_task_id"]:
                if delete:
                    db.execute("UPDATE tasks SET status='cancelled',reason='已从对话计划移除。',revision=revision+1 WHERE id=?",(current["source_task_id"],))
                else:
                    item = Item(**body.model_dump(exclude={"revision","completed"}))
                    db.execute("UPDATE tasks SET payload=?,revision=revision+1 WHERE id=?",(item.model_dump_json(),current["source_task_id"]))
            row = db.execute("SELECT * FROM planner_records WHERE id=?",(key,)).fetchone()
            return {"record":public(row),"deleted":delete}
