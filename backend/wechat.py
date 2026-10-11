"""Official WeChat Customer Service callback and API protocol.

Real platform verification requires a registered enterprise and HTTPS endpoint.
"""
import base64
import hashlib
import hmac
import json
import struct
import time

import httpx
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from defusedxml import ElementTree

from .config import Settings


class CallbackRejected(ValueError):
    pass


class WeChatCrypto:
    def __init__(self, settings: Settings):
        self.settings = settings
        try:
            self.key = base64.b64decode(settings.encoding_key + "=", validate=True)
        except Exception as exc:
            raise ValueError("Invalid WeChat EncodingAESKey") from exc
        if len(self.key) != 32:
            raise ValueError("Invalid WeChat EncodingAESKey length")

    def decrypt(self, signature: str, timestamp: str, nonce: str, encrypted: str):
        try:
            if abs(time.time() - int(timestamp)) > 300:
                raise CallbackRejected("Expired callback")
            expected = hashlib.sha1("".join(sorted([self.settings.callback_token, timestamp, nonce, encrypted])).encode()).hexdigest()
            if not hmac.compare_digest(expected, signature):
                raise CallbackRejected("Invalid callback signature")
            ciphertext = base64.b64decode(encrypted, validate=True)
            decryptor = Cipher(algorithms.AES(self.key), modes.CBC(self.key[:16])).decryptor()
            raw = decryptor.update(ciphertext) + decryptor.finalize()
            pad = raw[-1]
            if not 1 <= pad <= 32 or raw[-pad:] != bytes([pad]) * pad:
                raise CallbackRejected("Invalid callback padding")
            raw = raw[:-pad]
            if len(raw) < 20:
                raise CallbackRejected("Invalid callback frame")
            size = struct.unpack(">I", raw[16:20])[0]
            if 20 + size > len(raw):
                raise CallbackRejected("Invalid callback length")
            if raw[20+size:].decode() != self.settings.corp_id:
                raise CallbackRejected("Wrong callback receiver")
            return raw[20:20+size].decode()
        except CallbackRejected:
            raise
        except Exception as exc:
            raise CallbackRejected("Malformed callback") from exc

    def event(self, signature, timestamp, nonce, body):
        xml = ElementTree.fromstring(body)
        encrypted = xml.findtext("Encrypt")
        if not encrypted:
            raise CallbackRejected("Missing encrypted payload")
        plaintext = self.decrypt(signature, timestamp, nonce, encrypted)
        event = ElementTree.fromstring(plaintext)
        if event.findtext("Event") != "kf_msg_or_event":
            raise CallbackRejected("Unsupported event")
        token, kfid = event.findtext("Token"), event.findtext("OpenKfId")
        if not token or not kfid:
            raise CallbackRejected("Missing synchronization fields")
        return hashlib.sha256(plaintext.encode()).hexdigest(), {"token": token, "open_kfid": kfid}


class WeChatAPI:
    base = "https://qyapi.weixin.qq.com/cgi-bin"

    def __init__(self, settings, client=None):
        self.settings = settings
        self.client = client or httpx.Client(timeout=20, follow_redirects=False)
        self.token = ""
        self.expires = 0

    def access_token(self):
        if time.time() >= self.expires:
            response = self.client.get(self.base + "/gettoken", params={"corpid": self.settings.corp_id, "corpsecret": self.settings.kf_secret})
            response.raise_for_status()
            data = response.json()
            if data.get("errcode", 0):
                raise ValueError("WeChat token request rejected")
            self.token = data["access_token"]
            self.expires = time.time() + max(0, data["expires_in"] - 120)
        return self.token

    def post(self, path, payload):
        response = self.client.post(self.base + path, params={"access_token": self.access_token()}, json=payload)
        response.raise_for_status()
        data = response.json()
        if data.get("errcode", 0):
            if data["errcode"] in (40014, 42001):
                self.expires = 0
            # Never include token-bearing URLs, provider bodies or secrets in logs.
            raise ValueError("WeChat API rejected request")
        return data

    def sync_event(self, store, payload):
        for _ in range(100):
            data = self.post("/kf/sync_msg", {**payload, "cursor": store.cursor(payload["open_kfid"]), "limit": 1000, "voice_format": 0})
            messages = []
            for msg in data.get("msg_list", []):
                if msg.get("origin") != 3 or msg.get("external_userid") not in self.settings.allowed_users:
                    continue
                if msg.get("msgtype") != "text":
                    continue
                content = msg.get("text", {}).get("content", "")
                if not content.strip() or len(content) > 8000:
                    continue
                messages.append({"source":"wechat", "external_id":msg["msgid"], "owner":self.settings.owner,
                                 "text":content, "context":{"external_userid":msg["external_userid"], "open_kfid":payload["open_kfid"]}})
            cursor = data["next_cursor"]
            if data.get("has_more") and cursor == store.cursor(payload["open_kfid"]):
                raise ValueError("WeChat synchronization cursor did not advance")
            store.save_sync_page(payload, messages, cursor)
            if not data.get("has_more"):
                return
        raise ValueError("Synchronization page limit reached")

    def close(self):
        self.client.close()
