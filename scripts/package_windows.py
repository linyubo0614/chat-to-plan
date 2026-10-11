"""Explicit allowlist: never package local data, keys, caches or browser profiles."""
from pathlib import Path
import argparse
import zipfile


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",required=True)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    paths=[root/name for name in ("requirements.lock","requirements.windows.txt","README.md")]
    for name in ("backend","tools","windows","docs"):
        paths.extend(p for p in (root/name).rglob("*") if p.is_file() and p.suffix in {".py",".cmd",".ps1",".md"} and "__pycache__" not in p.parts)
    output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(paths):
            name = str(Path("ChatPlanner")/path.relative_to(root)).replace("\\", "/")
            content = path.read_bytes()
            if path.suffix == ".cmd":
                # CMD launchers must work when extracted from a Linux-built ZIP.
                content = content.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
            archive.writestr(name, content)
    print(f"Packaged {len(paths)} program/document files; local data and credentials excluded.")


if __name__=="__main__": main()
