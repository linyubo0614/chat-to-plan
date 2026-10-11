"""Build through the checked-in Gradle wrapper; preserve TLS verification."""
import argparse
import os
import subprocess
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk",default=os.getenv("ANDROID_HOME",os.getenv("ANDROID_SDK_ROOT","")))
    parser.add_argument("--jdk",default=os.getenv("JAVA_HOME",""))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    task_env = os.environ.copy()
    if args.sdk:
        task_env["ANDROID_HOME"] = str(Path(args.sdk).resolve())
    if args.jdk:
        task_env["JAVA_HOME"] = str(Path(args.jdk).resolve())
    cache = root / ".build-cache"
    cache.mkdir(exist_ok=True)
    task_env.setdefault("ANDROID_USER_HOME",str(cache / "android"))
    task_env.setdefault("GRADLE_USER_HOME",str(cache / "gradle"))
    proxy = urlsplit(os.getenv("HTTPS_PROXY",""))
    options = task_env.get("GRADLE_OPTS","")
    if proxy.hostname:
        if proxy.username or proxy.password:
            raise SystemExit("Authenticated Java proxy requires separate trusted configuration; credentials must not be placed in command logs")
        options += f" -Dhttps.proxyHost={proxy.hostname} -Dhttps.proxyPort={proxy.port or 80} -Dhttp.proxyHost={proxy.hostname} -Dhttp.proxyPort={proxy.port or 80}"
    trust = Path("/etc/ssl/certs/java/cacerts")
    if proxy.hostname and trust.is_file():
        options += f" -Djavax.net.ssl.trustStore={trust}"
    task_env["GRADLE_OPTS"] = options
    command = [str(root/"android/gradlew"),"--no-daemon","--console=plain","assembleDebug","lintDebug"]
    raise SystemExit(subprocess.run(command,cwd=root/"android",env=task_env).returncode)


if __name__ == "__main__":
    main()
