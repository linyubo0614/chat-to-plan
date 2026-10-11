"""Expiring, single-use pairing tickets and revocable persistent device tokens."""
import hashlib
import secrets
import time
import uuid

from pydantic import Field
from .models import StrictModel


class PairCreate(StrictModel):
    label: str = Field(default="手机", min_length=1, max_length=80)


class PairClaim(StrictModel):
    code: str = Field(pattern=r"^[A-Za-z0-9_-]{43}$")
    label: str = Field(default="手机", min_length=1, max_length=80)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


class Pairing:
    def __init__(self, store):
        self.store = store
        with store.connection() as db:
            db.execute("CREATE TABLE IF NOT EXISTS pairings (hash TEXT PRIMARY KEY,owner TEXT NOT NULL,label TEXT NOT NULL,expires REAL NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY,hash TEXT UNIQUE NOT NULL,owner TEXT NOT NULL,label TEXT NOT NULL,created REAL NOT NULL)")

    def create(self, owner, label):
        code, expires = secrets.token_urlsafe(32), time.time() + 300
        with self.store.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM pairings WHERE expires < ?", (time.time(),))
            # Refreshing a QR invalidates previous codes for the same destination.
            db.execute("DELETE FROM pairings WHERE owner=? AND label=?", (owner, label))
            db.execute("INSERT INTO pairings VALUES(?,?,?,?)", (digest(code), owner, label, expires))
        return {"code": code, "expires_at": expires, "expires_in": 300}

    def claim(self, code, label):
        token, device = secrets.token_urlsafe(32), uuid.uuid4().hex
        with self.store.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM pairings WHERE hash=? AND expires>?", (digest(code), time.time())).fetchone()
            if row is None:
                return None
            db.execute("DELETE FROM pairings WHERE hash=?", (digest(code),))
            db.execute("INSERT INTO devices VALUES(?,?,?,?,?)", (device, digest(token), row["owner"], label, time.time()))
        return {"token": token, "device_id": device}

    def owner(self, token):
        with self.store.connection() as db:
            row = db.execute("SELECT owner FROM devices WHERE hash=?", (digest(token),)).fetchone()
        return row[0] if row else None

    def devices(self, owner):
        with self.store.connection() as db:
            return [dict(r) for r in db.execute("SELECT id,label,created FROM devices WHERE owner=? ORDER BY created", (owner,))]

    def revoke(self, owner, device):
        with self.store.connection() as db:
            db.execute("DELETE FROM devices WHERE owner=? AND id=?", (owner, device))
