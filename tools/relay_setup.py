"""Interactive, one-time account login; all generated secrets stay in data/."""
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys

from tools.openclaw_config import SetupError, command_args, find_command, refreshed_path

WRANGLER = "wrangler@4.45.0"


def deploy(root, emit):
    root = Path(root)
    folder = root / "data/relay-worker"
    folder.mkdir(parents=True, exist_ok=True)
    source = Path(getattr(sys, "_MEIPASS", root)) / "relay"
    for name in ("worker.mjs", "wrangler.toml"):
        shutil.copy2(source / name, folder / name)
    config_path = root / "data/relay.json"
    previous = json.loads(config_path.read_text(encoding="utf-8")) if config_path.exists() else {}
    name = previous.get("name") or "chat-planner-" + secrets.token_hex(6)
    if not re.fullmatch(r"chat-planner-[a-f0-9]{12}", name): raise SetupError("中转配置中的名称无效")
    secret = previous.get("secret") or secrets.token_urlsafe(32)
    # Retain the worker identity across failed setups so retry updates it.
    settings = {"name": name, "secret": secret, "address": previous.get("address", "")}
    def save():
        temp = config_path.with_suffix(".tmp")
        with open(temp, "w", encoding="utf-8") as file: json.dump(settings, file)
        os.chmod(temp, 0o600); os.replace(temp, config_path)
    save()
    config = folder / "wrangler.toml"
    config.write_text(config.read_text().replace('name = "chat-planner-relay"', 'name = "' + name + '"'), encoding="utf-8")
    npx = find_command("npx")
    def run(*args, input=None):
        result = subprocess.run(command_args(npx, ["--yes", WRANGLER, *args]), cwd=folder,
            env={**os.environ, "PATH": refreshed_path(), "WRANGLER_SEND_METRICS": "false"},
            input=input, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600,
            creationflags=0x08000000 if os.name == "nt" else 0)
        if result.returncode: raise SetupError("Cloudflare 配置未完成。请确认浏览器登录已授权、账号已启用 Workers，并重新点击配置。")
        return result.stdout
    emit("state", "正在打开 Cloudflare 登录，请在浏览器完成授权…")
    run("login")
    emit("state", "正在部署固定中转，首次可能需要几分钟…")
    output = run("deploy", "--config", str(config))
    matches = re.findall(r"https://[a-z0-9.-]+\.workers\.dev", output)
    if not matches: raise SetupError("Cloudflare 未返回 workers.dev 地址；请先在控制台开通 Workers 子域名，再重试。")
    run("secret", "put", "BRIDGE_SECRET", "--config", str(config), input=secret + "\n")
    settings["address"] = matches[-1]
    save()
    emit("state", "固定中转已配置，请启动服务后扫码。今后保留 data 文件夹即可。")
    return settings["address"]
