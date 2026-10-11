import json
import logging
import threading

import httpx
from pydantic import ValidationError

from .parser import ParserUnavailable

logger = logging.getLogger(__name__)


class Worker:
    def __init__(self, store, parser, wechat=None, runtime=None):
        self.store, self.parser, self.wechat = store, parser, wechat
        self.runtime = runtime
        self.stop_event = threading.Event()
        self.thread = None

    def run_once(self):
        worked = False
        if self.wechat:
            event = self.store.claim("events")
            if event:
                worked = True
                try:
                    self.wechat.sync_event(self.store, json.loads(event["payload"]))
                    self.store.finish_job("events", event)
                except Exception:
                    self.store.fail("events", event, "wechat_sync_failed")
        enabled = not self.runtime or self.runtime.public()["enabled"]
        row = self.store.claim("messages") if enabled else None
        if row:
            worked = True
            try:
                result = self.parser.parse(row["text"], self.store.history(row["owner"],row["id"]))
                self.store.complete(row, result)
            except ParserUnavailable:
                self.store.fail("messages", row, "deepseek_not_configured", permanent=True)
            except httpx.HTTPStatusError as exc:
                self.store.fail("messages", row, "deepseek_http_error", permanent=exc.response.status_code in {400,401,403,402})
            except (ValidationError, ValueError, KeyError, IndexError):
                self.store.fail("messages", row, "invalid_model_output")
            except Exception:
                self.store.fail("messages", row, "analysis_failed")
        if self.wechat:
            reply = self.store.claim("outbox")
            if reply:
                worked = True
                try:
                    self.wechat.post("/kf/send_msg", json.loads(reply["payload"]))
                    self.store.finish_job("outbox", reply)
                except Exception:
                    self.store.fail("outbox", reply, "wechat_reply_failed")
        return worked

    def _run(self):
        while not self.stop_event.is_set():
            try:
                worked = self.run_once()
            except Exception:
                logger.error("worker_iteration_failed")
                worked = False
            self.stop_event.wait(0.1 if worked else 1)

    def start(self):
        self.thread = threading.Thread(target=self._run, daemon=True, name="assistant-worker")
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=120)
            if self.thread.is_alive():
                raise RuntimeError("Worker did not stop within shutdown deadline")
        if self.wechat:
            self.wechat.close()
