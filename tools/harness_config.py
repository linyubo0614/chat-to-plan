"""Generate a Cordis overlay without touching the user's existing dsh-im config."""
import json
from pathlib import Path
import sys
import os
import time
import yaml

BEGIN = "# BEGIN chat-planner managed MCP"
END = "# END chat-planner managed MCP"


def install_patch(root, destination):
    """Append one owned block; leave every other plugin and !!js node intact."""
    from tools.openclaw_config import SetupError
    destination = Path(destination)
    old = destination.read_text(encoding="utf-8-sig") if destination.exists() else ""
    try:
        node = yaml.compose(old)
        if node is not None and not isinstance(node, yaml.SequenceNode): raise ValueError()
        if (BEGIN in old) != (END in old): raise ValueError()
        if old.count(BEGIN) > 1 or old.count(END) > 1: raise ValueError()
    except (ValueError, yaml.YAMLError):
        raise SetupError("此配置不是受支持的 Harness patch 列表，请选择当前 profile 的 cordis.patch.yml。") from None
    generated = json.loads(export_config(root).read_text(encoding="utf-8"))[0]
    block = BEGIN + "\n- " + json.dumps(generated,ensure_ascii=False) + "\n" + END + "\n"
    if BEGIN in old:
        start, end = old.index(BEGIN), old.index(END) + len(END)
        if end <= start: raise SetupError("已有计划工具配置块不完整。")
        new = old[:start] + block + old[end:].lstrip("\r\n")
    else:
        if "mcp-chat-planner" in old: raise SetupError("已有同名手动配置，请先移除重复项，避免重复注册。")
        # Explicit [] cannot be followed by block sequence items.
        base = "" if node is not None and not node.value else old
        new = base.rstrip() + "\n" + block
    try:
        if not isinstance(yaml.compose(new), yaml.SequenceNode): raise ValueError()
    except (ValueError,yaml.YAMLError): raise SetupError("无法合并现有 patch，原文件未修改。") from None
    if new == old: return destination
    folder = Path(root) / "data"
    folder.mkdir(exist_ok=True)
    if old:
        backup = folder / ("harness-patch-backup-" + str(time.time_ns()) + ".yml")
        backup.write_text(old, encoding="utf-8"); os.chmod(backup,0o600)
    destination.parent.mkdir(parents=True,exist_ok=True)
    temp = destination.with_suffix(".planner.tmp")
    temp.write_text(new,encoding="utf-8");os.chmod(temp,0o600);os.replace(temp,destination)
    return destination


def export_config(root):
    root = Path(root).resolve()
    if getattr(sys, "frozen", False):
        command = str(root / "ChatPlannerMcp.exe")
        args = ["--root", str(root)]
        if not Path(command).is_file():
            from tools.openclaw_config import SetupError
            raise SetupError("请完整解压新版 Windows 包，需要 ChatPlannerMcp.exe。")
    else:
        command, args = sys.executable, ["-m", "harness.server", "--root", str(root)]
    config = {"serverName": "chat-planner", "transport": "stdio", "command": command,
              "args": args, "cwd": str(root)}
    patch = [{"insert": [{"id": "mcp-chat-planner", "name": "@deepseek-ai/dsh-mcp-client", "config": config}]}]
    folder = root / "data"
    folder.mkdir(exist_ok=True)
    path = folder / "chat-planner.cordis.patch.yml"
    # JSON is a YAML subset; JSON quoting preserves Windows paths verbatim.
    path.write_text(json.dumps(patch, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (folder / "chat-planner.mcp.json").write_text(json.dumps({"mcpServers": {"chat-planner": {
        k: v for k, v in config.items() if k in {"command", "args", "cwd"}}}}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path
