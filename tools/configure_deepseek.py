"""Local key entry for the Windows deployment, without a public HTTPS setup dependency."""
from getpass import getpass
from pathlib import Path

import httpx


def main():
    root=Path(__file__).resolve().parents[1]
    token=(root/"data/api-token").read_text().strip()
    key=getpass("请输入 DeepSeek API Key（输入隐藏，只保存到你的本机服务）：").strip()
    if not key: raise SystemExit("没有输入，本次未修改。")
    try:
        response=httpx.put("http://127.0.0.1:8000/v1/settings",headers={"Authorization":"Bearer "+token},
            json={"deepseek_api_key":key,"model":"deepseek-chat"},timeout=15,follow_redirects=False)
        response.raise_for_status()
    except Exception: raise SystemExit("保存失败，请先启动服务；本次没有发起 DeepSeek 分析请求。") from None
    print("API Key 已在本机服务加密保存；这一步未发起模型调用，不代表 Key 或余额已经验证。")


if __name__=="__main__": main()
