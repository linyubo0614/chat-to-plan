"""Ship the backend's validation schema as a dependency-free OpenClaw schema."""
import argparse
import json
from pathlib import Path

from backend.models import OpenClawPlan


def schema():
    model = OpenClawPlan.model_json_schema()
    definitions = model.pop("$defs", {})

    def inline(value):
        if isinstance(value, list):
            return [inline(item) for item in value]
        if isinstance(value, dict):
            if "$ref" in value:
                return inline(definitions[value["$ref"].split("/")[-1]])
            return {key:inline(item) for key,item in value.items()}
        return value

    model["properties"].pop("message_id")
    model["required"].remove("message_id")
    model["properties"]["operation_id"] = {
        "type":"string", "pattern":"^[a-f0-9]{32,64}$",
        "description":"仅重试时填写上次返回的 operation_id；新操作省略。",
    }
    return inline(model)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    path = Path(__file__).resolve().parents[1]/"openclaw"/"record.schema.json"
    content = json.dumps(schema(),ensure_ascii=False,indent=2)+"\n"
    if args.check:
        if path.read_text(encoding="utf-8") != content:
            raise SystemExit("OpenClaw schema differs from backend; regenerate it.")
        print("OpenClaw schema matches backend validation.")
    else:
        path.write_text(content,encoding="utf-8")


if __name__ == "__main__":
    main()
