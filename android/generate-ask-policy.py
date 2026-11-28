"""Generate Android's Ask constants from the same table the laptop embeds."""
import json
import sys
from pathlib import Path

shared = Path(__file__).resolve().parents[1] / "shared"
policy = json.loads((shared / "ask-retry.json").read_text())
out = Path(sys.argv[1]) / "org/nightingalelab/focus/phone/AskPolicy.java"
out.parent.mkdir(parents=True, exist_ok=True)
constants = "".join(f"  static final int {name.upper()} = {value};\n" for name, value in policy.items())
out.write_text(f"package org.nightingalelab.focus.phone;\n\nfinal class AskPolicy {{\n{constants}}}\n")
