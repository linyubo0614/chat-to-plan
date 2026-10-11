"""Run on the owner's Windows PC: official webpage login remains in a local browser."""
import argparse
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit

import httpx

from .tomato_protocol import TomatoUnverified, request, validate_snapshot, write_verified


CALL = """async ({payload, expected_user}) => {
    if (location.origin !== 'https://www.incmoon.com') return {bridge_error:'unexpected_origin'};
    const app=document.body.firstElementChild?.__vue__;
    if (!app?.$api?.getSyncList) return {bridge_error:'website_changed'};
    if (app.$store?.state?.userId !== expected_user || localStorage.getItem('userId') !== expected_user)
        return {bridge_error:'account_changed'};
    try {
        const response=await app.$api.getSyncList(payload);
        return response?.data ?? {bridge_error:'response_missing'};
    } catch (error) {
        const status=error?.response?.status ?? error?.status;
        return {bridge_error:'network_request_failed', http_status:Number.isInteger(status) ? status : null};
    }
}"""
IDENTITY = """() => {
    if (location.origin !== 'https://www.incmoon.com') return null;
    const app=document.body.firstElementChild?.__vue__;
    const id=app?.$store?.state?.userId;
    return id && id === localStorage.getItem('userId') ? id : null;
}"""


FAILURES = {
    "unexpected_origin":"当前页面不是番茄计划官网",
    "website_changed":"官网同步入口已改变",
    "account_changed":"登录账号已切换",
    "response_missing":"官网没有返回同步结果",
    "network_request_failed":"官网同步请求失败",
    "session_or_response_unavailable":"官网未接受请求或登录授权失效",
    "website_protocol_changed":"官网返回的数据格式与预期不一致",
    "sync_conflict":"官网数据发生同步冲突，请刷新官网后重试",
    "ambiguous_category":"存在多个同名分类，暂不自动选择",
    "target_record_differs":"测试记录已被修改，未覆盖",
    "target_record_deleted_or_conflicting":"测试记录已被删除或存在冲突，未覆盖",
    "category_readback_failed":"分类读回未能核对",
    "record_not_found_after_write":"提交后没有读回测试记录",
    "unsupported_item":"该事项暂不支持自动写入",
}


def browser_sync(page, payload, user_id):
    reply=page.evaluate(CALL,{"payload":payload,"expected_user":user_id})
    if isinstance(reply,dict) and "bridge_error" in reply:
        code=reply["bridge_error"]
        raise TomatoUnverified(code if code in FAILURES else "website_protocol_changed",reply.get("http_status"))
    return reply


def failure_text(error, stage):
    # Emit only locally defined labels and numeric status codes, never raw exceptions.
    stages={"read":"读取现有清单", "write":"提交测试记录", "readback":"读回核对"}
    label=stages.get(stage,"浏览器调用")
    detail=FAILURES.get(error.code,"同步结果未确认") if isinstance(error,TomatoUnverified) else "浏览器或本地处理失败（"+type(error).__name__+"）"
    status=f"；返回码 {error.response_code}" if isinstance(error,TomatoUnverified) and error.response_code is not None else ""
    attempt="尚未提交测试记录。" if stage=="read" else "可能已提交，请检查官网中的测试记录后再重试。"
    return f"{label}失败：{detail}{status}。{attempt}没有修改或删除已有记录。"


def main():
    parser=argparse.ArgumentParser(description="番茄计划官网授权与新建记录验证")
    parser.add_argument("--write-test",action="store_true",help="新建一条专用测试备忘并读回核对")
    parser.add_argument("--check-browser",action="store_true",help="仅检查浏览器启动，不访问账号、不写记录")
    parser.add_argument("--serve",action="store_true",help="测试通过后，持续处理该实例的番茄计划待写入事项")
    parser.add_argument("--server",default="http://127.0.0.1:8000")
    parser.add_argument("--browser",choices=["edge","chromium"],default="edge")
    args=parser.parse_args()
    base=args.server.rstrip("/");uri=urlsplit(base)
    if uri.username or uri.password or uri.query or uri.fragment or not uri.hostname or not (
        uri.scheme=="https" or uri.scheme=="http" and uri.hostname in {"127.0.0.1","localhost"}):
        raise SystemExit("请使用本机服务地址或 HTTPS 地址。")
    root=Path(__file__).resolve().parents[1];data=root/"data";data.mkdir(exist_ok=True,mode=0o700)
    # No browser state, cookies, JWTs, network traces or account contents are exported.
    print("正在启动 Microsoft Edge，请稍候……", flush=True)
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        raise SystemExit("浏览器依赖未安装完成。请重新运行 windows\\start-assistant.cmd，等待服务启动后重试。") from None
    with sync_playwright() as playwright:
        try:
            context=playwright.chromium.launch_persistent_context(str(data/"tomato-browser"),headless=False,
                channel="msedge" if args.browser=="edge" else None)
        except Exception as error:
            # Do not export browser profiles, cookies, or raw browser/network traces.
            raise SystemExit(f"Edge 启动失败（{type(error).__name__}）。请确认 Microsoft Edge 已安装，并关闭其他番茄测试/同步窗口后重试。") from None
        page=context.pages[0] if context.pages else context.new_page()
        if args.check_browser:
            page.goto("about:blank")
            print("BROWSER_START_OK: Edge opened successfully; no account access or writes.")
            context.close()
            return
        print("正在打开番茄计划官网；网络等待最多 60 秒。", flush=True)
        try:
            page.goto("https://www.incmoon.com/web/login",wait_until="domcontentloaded",timeout=60000)
        except Exception as error:
            context.close()
            raise SystemExit(f"官网打开失败（{type(error).__name__}）。请先在普通 Edge 中检查 https://www.incmoon.com/web/login 是否能访问，再重试。") from None
        print("请在打开的番茄计划官网中登录，进入清单页后回到此窗口。密码只填写在官网。")
        input("登录完成后按回车继续：")
        user_id=page.evaluate(IDENTITY)
        if not user_id: raise SystemExit("未识别到已登录账号，请进入清单页后重试。")
        def sync(payload): return browser_sync(page,payload,user_id)
        if args.write_test:
            test={"kind":"memo","title":"对话计划授权验证","body":"这是你授权创建的接入测试记录，可在验证完成后手动删除。","category":"对话计划测试"}
            stage="read"
            def progress(value):
                nonlocal stage
                stage=value
                print({"read":"正在读取现有清单……", "write":"正在提交测试记录……", "readback":"正在读回核对……"}[value],flush=True)
            try:
                write_verified(sync,user_id,"authorization-probe-v1",test,progress=progress)
            except Exception as error:
                raise SystemExit(failure_text(error,stage)) from None
            print("官网测试记录已写入并读回核对。请在手机番茄计划中刷新，核对同名记录及“对话计划测试”分类。")
        elif args.serve:
            try:
                validate_snapshot(sync(request()))
            except Exception:
                raise SystemExit("官网授权读取未通过，请检查登录状态；尚未启动事项交付。") from None
            print("官网授权读取已通过。每条事项仍需实际写入并读回后才会确认成功。")
        else:
            print("已识别登录状态。本次没有提交新记录；用 --write-test 进行写入验证。")
        if not args.serve:
            input("检查完成后按回车关闭：");context.close();return
        token=os.getenv("ASSISTANT_API_TOKEN","") or (data/"api-token").read_text().strip()
        client_id="tomato-browser-local-v1"
        print("开始后台交付。请保持本窗口和浏览器运行；不要在这个浏览器里切换账号。Ctrl+C 停止。")
        with httpx.Client(timeout=35,follow_redirects=False,headers={"Authorization":f"Bearer {token}"}) as client:
            while True:
                try:
                    if page.evaluate(IDENTITY)!=user_id: raise TomatoUnverified("account_changed")
                    claim=client.post(base+"/v1/delivery/tomato/claim?wait=25",json={"client_id":client_id})
                    claim.raise_for_status();claim=claim.json()
                    if claim["task"] is None: page.wait_for_timeout(1000);continue
                    task=claim["task"]
                    external_id=write_verified(sync,user_id,task["id"],task["payload"])
                    receipt=client.post(base+f'/v1/delivery/{task["id"]}/ack',json={"client_id":client_id,
                        "lease_token":claim["lease_token"],"revision":task["revision"],"external_id":external_id})
                    receipt.raise_for_status()
                    print("一条事项已在官网写入并读回核对。")
                except KeyboardInterrupt: break
                except Exception:
                    print("交付暂未确认，请检查登录、网络或服务状态。不会报告写入成功。")
                    page.wait_for_timeout(15000)
        context.close()


if __name__=="__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("操作已停止。")
        sys.exit(130)
