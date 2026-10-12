"""Generate a private, local preview key. Never commit or upload this file."""
import argparse
import os
import shutil
import subprocess
from pathlib import Path


def ensure_preview_key(destination, jdk=None):
    destination = Path(destination).resolve()
    if destination.is_file():
        return destination
    keytool = str(Path(jdk) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")) if jdk else shutil.which("keytool")
    if not keytool:
        raise SystemExit("A JDK with keytool is required to build preview APKs.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        keytool, "-genkeypair", "-keystore", str(destination), "-storepass", "android",
        "-keypass", "android", "-alias", "androiddebugkey", "-dname",
        "CN=Chat To Plan Preview, OU=Development, O=Chat To Plan, C=CN",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
    ], check=True)
    if os.name != "nt":
        destination.chmod(0o600)
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--jdk", default=os.getenv("JAVA_HOME"))
    args = parser.parse_args()
    print(ensure_preview_key(args.output, args.jdk))
