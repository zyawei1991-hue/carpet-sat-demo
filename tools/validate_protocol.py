"""校验 protocol/examples/*.json 是否符合 screen-link.schema.json。

CI 里跑这个就够了：契约改动 -> 示例不匹配 -> 红。
    python tools/validate_protocol.py
"""
import json
import pathlib
import sys

try:
    from jsonschema import Draft202012Validator
except ImportError:
    sys.exit("需要 jsonschema: pip install jsonschema")

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCHEMA_PATH = ROOT / "protocol" / "screen-link.schema.json"
EXAMPLES = sorted((ROOT / "protocol" / "examples").glob("*.json"))


def main() -> int:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    print(f"schema ok: {schema['$id']}")

    failures = 0
    for path in EXAMPLES:
        doc = json.loads(path.read_text(encoding="utf-8"))
        errors = sorted(validator.iter_errors(doc), key=lambda e: list(e.path))
        if errors:
            failures += 1
            print(f"FAIL {path.name}")
            for e in errors:
                loc = "/".join(str(p) for p in e.path) or "<root>"
                print(f"     {loc}: {e.message}")
        else:
            print(f"ok   {path.name}  (t={doc.get('t')})")

    print(f"\n{len(EXAMPLES) - failures}/{len(EXAMPLES)} examples valid")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
