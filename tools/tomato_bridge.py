"""Run on the owner's Windows PC: official webpage login remains in a local browser."""
import argparse
import os
from pathlib import Path
from urllib.parse import urlsplit

import httpx

from .tomato_protocol import TomatoUnverified, write_verified


CALL = """async (payload) => {
    if (location.origin !== 'https://www.incmoon.com') throw new Error('Unexpected origin');
    const app=document.getElementById('app')?.__vue__;
    if (!app?.$api?.getSyncList) throw new Error('Website changed');
    const response=await app.$api.getSyncList(payload);
    return response.data;
}"""
IDENTITY = """() => {
    if (location.origin !== 'https://www.incmoon.com') return null;
    const app=document.getElementById('app')?.__vue__;
    return app?.$store?.getters?.userId || null;
}"""


def main():
    parser=argparse.ArgumentParser(description="番茄计划官网授权与新建记录验证")
    parser.add_argument("--write-test",action="store_true",help="新建一条专用测试备忘并读回核对")
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
    from playwright.sync_api import sync_playwright
    with sync_playwright() as playwright:
        context=playwright.chromium.launch_persistent_context(str(data/"tomato-browser"),headless=False,
            channel="msedge" if args.browser=="edge" else None)
        page=context.pages[0] if context.pages else context.new_page()
        page.goto("https://www.incmoon.com/web/login",wait_until="domcontentloaded",timeout=60000)
        print("请在打开的番茄计划官网中登录，进入清单页后回到此窗口。密码只填写在官网。")
        input("登录完成后按回车继续：")
        user_id=page.evaluate(IDENTITY)
        if not user_id: raise SystemExit("未识别到已登录账号，请进入清单页后重试。")
        def sync(payload): return page.evaluate(CALL,payload)
        if args.write_test or args.serve:
            test={"kind":"memo","title":"对话计划授权验证","body":"这是你授权创建的接入测试记录，可在验证完成后手动删除。","category":"对话计划测试"}
            try:
                write_verified(sync,user_id,"authorization-probe-v1",test)
            except Exception:
                raise SystemExit("测试尚未确认成功；可能已提交，请先检查官网中的测试记录。没有修改或删除已有记录。") from None
            print("官网测试记录已写入并读回核对。请在手机番茄计划中刷新，核对同名记录及“对话计划测试”分类。")
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


if __name__=="__main__": main()
