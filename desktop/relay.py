"""Outbound reconnecting link to the owner's fixed workers.dev address."""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import threading
from urllib.parse import urlsplit

import httpx
import websocket


def relay_url(value):
    url = urlsplit(value.strip())
    if (url.scheme != "https" or not url.hostname
            or url.username or url.password or url.port not in (None, 443)
            or url.path not in ("", "/") or url.query or url.fragment):
        raise ValueError("请输入有效的 HTTPS 中转根地址，例如 https://planner.example.com")
    return "https://" + url.hostname


class RelayConnector:
    def __init__(self, address, secret, emit):
        self.address = relay_url(address)
        self.secret, self.emit = secret, emit
        self.stop_event = threading.Event()
        self.socket = None
        self.pool = ThreadPoolExecutor(max_workers=8)
        self.capacity = threading.BoundedSemaphore(32)
        self.thread = threading.Thread(target=self.run, daemon=True)

    def start(self): self.thread.start()

    def run(self):
        delay = 1
        while not self.stop_event.is_set():
            opened = threading.Event()
            def online(ws):
                opened.set()
                self.emit("tunnel", "固定中转已连接；手机无需重新扫码")
            self.socket = websocket.WebSocketApp(self.address.replace("https://", "wss://", 1) + "/bridge",
                header={"Authorization": "Bearer " + self.secret}, on_open=online, on_message=self.message)
            try: self.socket.run_forever(ping_interval=25, ping_timeout=10)
            except Exception: pass  # No URL, headers or bodies in logs.
            if self.stop_event.is_set(): break
            self.emit("tunnel", "固定中转断线，正在自动重连…")
            if opened.is_set(): delay = 1
            if self.stop_event.wait(delay): break
            delay = min(delay * 2, 30)

    def message(self, socket, raw):
        try:
            job = json.loads(raw)
            if not isinstance(job, dict) or not isinstance(job.get("id"), str): return
            if not self.capacity.acquire(blocking=False):
                self.send(socket, job["id"], 503, b'{"error":"Desktop busy"}', "application/json")
                return
            self.pool.submit(self.handle, socket, job)
        except (ValueError, RuntimeError): pass

    @staticmethod
    def send(socket, job_id, status, body, content_type):
        try: socket.send(json.dumps({"id": job_id, "status": status, "contentType": content_type,
                                     "body": base64.b64encode(body).decode("ascii")}))
        except Exception: pass

    def handle(self, socket, job):
        try:
            path, method = job.get("path", ""), job.get("method")
            # Resolve only to our fixed backend. Never interpret a remote URL.
            if (not isinstance(path, str) or not (path.startswith("/v1/") or path == "/healthz")
                    or "\\" in path or "#" in path or method not in {"GET", "POST", "PUT", "DELETE"}):
                raise ValueError()
            body = job.get("body", "").encode("utf-8")
            if len(body) > 512 * 1024: raise ValueError()
            headers = {"Authorization": job.get("authorization", ""), "Content-Type": job.get("contentType", "application/json")}
            with httpx.Client(trust_env=False, timeout=35, follow_redirects=False) as client:
                with client.stream(method, "http://127.0.0.1:8000" + path, headers=headers, content=body) as response:
                    chunks, size = [], 0
                    for chunk in response.iter_bytes():
                        size += len(chunk)
                        if size > 512 * 1024: raise ValueError()
                        chunks.append(chunk)
                    self.send(socket, job["id"], response.status_code, b"".join(chunks), response.headers.get("Content-Type", "application/json"))
        except Exception:
            self.send(socket, job["id"], 502, b'{"error":"Local response unconfirmed"}', "application/json")
        finally: self.capacity.release()

    def stop(self):
        self.stop_event.set()
        if self.socket: self.socket.close()
        self.thread.join(timeout=5)
        self.pool.shutdown(wait=False, cancel_futures=True)
