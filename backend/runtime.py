"""Persist switches and encrypt user-configured provider credentials at rest."""
import base64
import json
import os
from dataclasses import replace

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from pydantic import Field

from .models import StrictModel


class SettingsUpdate(StrictModel):
    enabled: bool | None = None
    deepseek_api_key: str | None = Field(default=None,max_length=512)
    model: str | None = Field(default=None,pattern=r"^deepseek-(chat|reasoner)$")


class Runtime:
    def __init__(self, store, settings):
        self.store, self.settings = store, settings
        path = settings.db_path.parent / "credential.key"
        try:
            fd = os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        except FileExistsError:
            self.key = path.read_bytes()
        else:
            self.key = AESGCM.generate_key(bit_length=256)
            with os.fdopen(fd,"wb") as file:
                file.write(self.key)
                file.flush()
                os.fsync(file.fileno())
        if len(self.key) != 32:
            raise ValueError("Invalid persisted credential encryption key")
        with store.connection() as db:
            db.execute("CREATE TABLE IF NOT EXISTS runtime (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL)")
            db.execute("INSERT OR IGNORE INTO runtime VALUES(1,?)",(json.dumps({"enabled":True}),))

    def _read(self):
        with self.store.connection() as db:
            return json.loads(db.execute("SELECT value FROM runtime WHERE id=1").fetchone()[0])

    def snapshot(self):
        data = self._read()
        key = self.settings.deepseek_key
        if "key_ciphertext" in data:
            encrypted = base64.b64decode(data["key_ciphertext"])
            key = AESGCM(self.key).decrypt(encrypted[:12],encrypted[12:],b"deepseek-key").decode()
        return data.get("enabled",True), replace(self.settings,deepseek_key=key,model=data.get("model",self.settings.model))

    def public(self):
        enabled, settings = self.snapshot()
        return {"enabled":enabled,"deepseek_key_configured":bool(settings.deepseek_key),"model":settings.model,"timezone":settings.timezone}

    def update(self, patch: SettingsUpdate):
        with self.store.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            data = json.loads(db.execute("SELECT value FROM runtime WHERE id=1").fetchone()[0])
            if patch.enabled is not None:
                data["enabled"] = patch.enabled
            if patch.model is not None:
                data["model"] = patch.model
            if patch.deepseek_api_key is not None:
                nonce = os.urandom(12)
                encrypted = AESGCM(self.key).encrypt(nonce,patch.deepseek_api_key.strip().encode(),b"deepseek-key")
                data["key_ciphertext"] = base64.b64encode(nonce+encrypted).decode()
            db.execute("UPDATE runtime SET value=? WHERE id=1",(json.dumps(data),))
        return self.public()
