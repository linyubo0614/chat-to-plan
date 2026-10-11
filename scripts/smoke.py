"""Exercise the real local HTTP service without exposing credentials."""
import json
import os
import time
from pathlib import Path

import httpx


def main():
    token = os.getenv("ASSISTANT_API_TOKEN") or Path("data/api-token").read_text().strip()
    base = f"http://127.0.0.1:{os.getenv('ASSISTANT_PORT', '8000')}"
    with httpx.Client(base_url=base,headers={"Authorization":f"Bearer {token}"},timeout=5) as client:
        client.get("/healthz").raise_for_status()
        state = client.get("/v1/status")
        state.raise_for_status()
        print("HTTP health and authenticated status: PASS")
        if state.json()["deepseek_configured"]:
            print("DeepSeek key is present; live paid requests require a separate explicit test.")
            return
        message = {"message_id":"smoke-no-key-v1", "text":"记一下买打印纸"}
        r = client.post("/v1/messages",json=message)
        r.raise_for_status()
        key = r.json()["id"]
        for _ in range(50):
            result = client.get(f"/v1/messages/{key}").json()
            if result["status"] == "failed":
                assert result["error"] == "deepseek_not_configured"
                assert not result["tasks"]
                print("Missing API key fails explicitly without fabricating tasks: PASS")
                break
            time.sleep(0.1)
        else:
            raise AssertionError("Message did not finish")
        r = client.post("/v1/messages",json=message)
        assert r.status_code == 202 and r.json()["duplicate"]
        print("Persistent message deduplication: PASS")


if __name__ == "__main__":
    main()
