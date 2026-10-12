"""Interactive, one-time account login; all generated secrets stay in data/."""
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import queue
import signal
import threading
import time
import sys

from tools.openclaw_config import SetupError, command_args, find_command, refreshed_path

WRANGLER = "wrangler@4.45.0"


def deploy(root, emit, cancel_event=None):
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
    try:
        npx = find_command("npx")
    except SetupError:
        raise SetupError("未找到 Node.js / npx。请安装 Node.js 20.19 或更高版本，重新打开程序后再配置。") from None
    cancel_event = cancel_event or threading.Event()
    log_path = root / "data/relay-setup.log"
    with open(log_path,"w",encoding="utf-8") as file:
        file.write("Cloudflare 配置诊断（已过滤登录链接与凭据）\n")
    os.chmod(log_path,0o600)

    def run(stage, *args, input=None, timeout=180, idle_timeout=90):
        emit("state",stage)
        def report(line):
            safe = sanitize(line,secret)
            if safe:
                with open(log_path,"a",encoding="utf-8") as file:file.write(safe+"\n")
                emit("setup_log",safe)
        report("\n"+stage)
        try:
            return run_command(npx_args(npx,["--yes",WRANGLER,*args]),folder,
                {**os.environ,"PATH":refreshed_path(),"WRANGLER_SEND_METRICS":"false",
                 "WRANGLER_LOG_SANITIZE":"true","NO_COLOR":"1","FORCE_COLOR":"0",
                 "npm_config_fetch_retries":"1","npm_config_fetch_timeout":"60000"},
                stage,report,emit,cancel_event,input=input,timeout=timeout,idle_timeout=idle_timeout,redact=lambda text:sanitize(text,secret))
        except SetupError as exc:
            raise SetupError(str(exc)+"\n诊断记录："+str(log_path)) from None
    run("1/4 · 检查并下载 Cloudflare 部署组件…","--version")
    run("2/4 · 请在浏览器完成 Cloudflare 授权…","login",timeout=300,idle_timeout=None)
    output = run("3/4 · 部署固定中转…","deploy","--config",str(config))
    matches = re.findall(r"https://[a-z0-9.-]+\.workers\.dev", output)
    if not matches: raise SetupError("Cloudflare 未返回 workers.dev 地址；请先在控制台开通 Workers 子域名，再重试。")
    run("4/4 · 保存中转连接凭据…","secret", "put", "BRIDGE_SECRET", "--config", str(config), input=secret + "\n")
    settings["address"] = matches[-1]
    save()
    emit("state", "固定中转已配置，请启动服务后扫码。今后保留 data 文件夹即可。")
    return settings["address"]


def npx_args(command,arguments):
    # Resolve the npm shim, never the unrelated global OpenClaw executable.
    if os.name == "nt" and command.lower().endswith((".cmd",".bat")):
        entry=Path(command).parent / "node_modules/npm/bin/npx-cli.js"
        if entry.is_file():return [find_command("node"),str(entry),*arguments]
        return subprocess.list2cmdline([os.environ.get("COMSPEC","cmd.exe")])+" /d /s /c \""+subprocess.list2cmdline([command,*arguments])+"\""
    return [command,*arguments]


def sanitize(line, secret=""):
    """Diagnostics must never carry OAuth URLs, tokens or stdin secrets."""
    line = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", line).strip()
    if secret: line = line.replace(secret,"[凭据已隐藏]")
    def url(match):
        value=match.group(0)
        if re.search(r"oauth|authorize|callback",value,re.I):return "[登录链接已隐藏]"
        return value.split("?")[0].split("#")[0]
    line=re.sub(r"https?://\S+",url,line)
    line=re.sub(r"(?i)Bearer\s+\S+","Bearer [凭据已隐藏]",line)
    line=re.sub(r'(?i)([\"\']?(?:access_token|refresh_token|oauth_token|api_token|client_secret|id_token|token|authorization|cookie|secret)[\"\']?\s*[:=]\s*)(?:\"[^\"]*\"|\'[^\']*\'|[^\s,}]+)',r'\1[凭据已隐藏]',line)
    line=re.sub(r"[A-Za-z0-9_+/=-]{40,}","[长标识已隐藏]",line)
    return line[:2000]


def failure_hint(output):
    lower=output.lower()
    if "more than one account" in lower or "account_id" in lower and "select" in lower:
        return "账号关联多个 Cloudflare account。请设置 CLOUDFLARE_ACCOUNT_ID 为目标账号 ID，重新打开程序后重试。"
    if "workers.dev subdomain" in lower or "workers/onboarding" in lower:
        return "账号尚未开通 workers.dev 子域名。请在 Cloudflare 控制台的 Workers 页面完成首次开通，再重试；无需购买域名。"
    if any(value in lower for value in ("econnreset","etimedout","enotfound","fetch failed","unable to verify","certificate","econnrefused","could not resolve")):
        return "访问 npm 或 Cloudflare 失败，请检查运行程序这台电脑的网络、代理和证书设置。"
    if "authentication" in lower or "unauthorized" in lower or "not logged" in lower:
        return "Cloudflare 授权未生效或已过期，请重新完成浏览器授权。"
    return "请查看下面的部署输出和诊断记录，按具体错误处理后重试。"


def stop_process_tree(process):
    if os.name=="nt":
        subprocess.run(["taskkill.exe","/PID",str(process.pid),"/T","/F"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=10)
    else:
        try:os.killpg(process.pid,signal.SIGKILL)
        except ProcessLookupError:pass
    try:process.wait(timeout=5)
    except subprocess.TimeoutExpired:process.kill()


def run_command(command, folder, env, stage, report, emit, cancel_event,
                input=None, timeout=180, idle_timeout=90,redact=sanitize):
    """Drain pipes live, bound waiting and terminate only our spawned CLI tree."""
    options={"creationflags":0x08000200} if os.name=="nt" else {"start_new_session":True}
    try:
        process=subprocess.Popen(command,cwd=folder,env=env,stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding="utf-8",errors="replace",**options)
    except OSError:
        raise SetupError(stage+" 启动失败，请检查 Node.js 是否安装完整。") from None
    lines=queue.Queue()
    def drain():
        try:
            for line in process.stdout:lines.put(line)
        finally:lines.put(None)
    reader=threading.Thread(target=drain,daemon=True);reader.start()
    finished=False
    try:
        try:
            if input is not None:process.stdin.write(input);process.stdin.flush()
        except (BrokenPipeError,OSError):pass
        finally:process.stdin.close()
        started=last_output=heartbeat=time.monotonic();output=[];finished=False;size=0
        while not finished:
            now=time.monotonic()
            if cancel_event.is_set():raise SetupError(stage+" 已取消；已有中转名称和配对数据已保留，可以重新配置。")
            if now-started>=timeout or idle_timeout is not None and now-last_output>=idle_timeout:
                tail=redact("".join(output)[-4000:])
                raise SetupError(stage+" 超时（已等待 "+str(int(now-started))+" 秒）。"+failure_hint(tail)+("\n最近输出：\n"+tail if tail else ""))
            try:line=lines.get(timeout=0.2)
            except queue.Empty:line=""
            if line is None:finished=True
            elif line:
                last_output=time.monotonic();report(line)
                output.append(line);size+=len(line)
                while size>64000 and len(output)>1:size-=len(output.pop(0))
            if now-heartbeat>=15:
                emit("state",stage+" 已等待 "+str(int(now-started))+" 秒；可点击“取消配置”。")
                heartbeat=now
        remaining=max(0.1,timeout-(time.monotonic()-started))
        try:code=process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:raise SetupError(stage+" 进程未能退出，请重试。") from None
        result="".join(output)
        if code:
            safe=redact(result[-4000:])
            raise SetupError(stage+" 失败（退出码 "+str(code)+"）。"+failure_hint(safe)+"\n最近输出：\n"+safe)
        return result
    finally:
        if not finished or process.poll() is None:stop_process_tree(process)
        reader.join(timeout=1)
        process.stdout.close()
