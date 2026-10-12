"""Reproduce the maintained fork from a pinned upstream and the reviewed overlay."""
import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit


def run(args,cwd=None,env=None):
    subprocess.run(args,cwd=cwd,env=env,check=True)


def prepare(root,destination):
    metadata=json.loads((root/"integrations/hita/upstream.json").read_text())
    if destination.exists():
        raise SystemExit("Use a new output directory; existing checkouts are never reset or overwritten.")
    destination.parent.mkdir(parents=True,exist_ok=True)
    run(["git","clone","--no-checkout",metadata["repository"],str(destination)])
    run(["git","checkout","--detach",metadata["commit"]],cwd=destination)
    actual=subprocess.check_output(["git","rev-parse","HEAD"],cwd=destination,text=True).strip()
    if actual!=metadata["commit"]: raise SystemExit("Upstream pin verification failed")
    run(["git","apply","--check",str(root/"integrations/hita/upstream.patch")],cwd=destination)
    run(["git","apply",str(root/"integrations/hita/upstream.patch")],cwd=destination)
    for file in (root/"integrations/hita/overlay").rglob("*"):
        if file.is_file():
            target=destination/file.relative_to(root/"integrations/hita/overlay")
            if target.exists(): raise SystemExit("Overlay would overwrite an upstream file")
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,target)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",required=True,help="new checkout directory outside tracked sources")
    parser.add_argument("--sdk",default=os.getenv("ANDROID_HOME",""))
    parser.add_argument("--jdk",default=os.getenv("JAVA_HOME",""))
    parser.add_argument("--prepare-only",action="store_true")
    args=parser.parse_args();root=Path(__file__).resolve().parents[1];destination=Path(args.output).resolve()
    prepare(root,destination)
    if args.prepare_only: return
    env=os.environ.copy()
    if args.sdk: env["ANDROID_HOME"]=str(Path(args.sdk).resolve())
    if args.jdk: env["JAVA_HOME"]=str(Path(args.jdk).resolve())
    env.setdefault("GRADLE_USER_HOME",str(root/".build-cache/gradle"))
    env.setdefault("ANDROID_USER_HOME",str(root/".build-cache/android"))
    # Reuse the owner's local test key, or the per-job CI key. Never publish
    # private signing material; both APKs need the same certificate for IPC.
    configured_key = env.get("PLANNER_PREVIEW_KEYSTORE", "")
    preview_key = Path(configured_key).expanduser().resolve() if configured_key else root / ".build-cache/preview.keystore"
    if not configured_key:
        run([sys.executable, str(root / "scripts/prepare_signing.py"), "--output", str(preview_key)], env=env)
    if not preview_key.is_file():
        raise SystemExit("The configured test signing key does not exist")
    env["PLANNER_PREVIEW_KEYSTORE"] = str(preview_key.resolve())
    # Never bake the owner's personal model keys into the upstream BuildConfig.
    env.pop("DEEPSEEK_API_KEY",None);env.pop("ZHIPU_API_KEY",None)
    proxy=urlsplit(os.getenv("HTTPS_PROXY",""));options=env.get("GRADLE_OPTS","")
    if proxy.hostname:
        if proxy.username or proxy.password: raise SystemExit("Do not log authenticated Java proxy credentials")
        options+=f" -Dhttps.proxyHost={proxy.hostname} -Dhttps.proxyPort={proxy.port or 80} -Dhttp.proxyHost={proxy.hostname} -Dhttp.proxyPort={proxy.port or 80}"
        trust=Path("/etc/ssl/certs/java/cacerts")
        if trust.is_file(): options+=f" -Djavax.net.ssl.trustStore={trust}"
    env["GRADLE_OPTS"]=options
    run([str(destination/"gradlew"),"--no-daemon","--console=plain","--max-workers=2","assembleDebug","testDebugUnitTest"],cwd=destination,env=env)


if __name__=="__main__": main()
