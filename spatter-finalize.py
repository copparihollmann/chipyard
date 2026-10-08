#!/usr/bin/env python3
"""Validate the Spatter evaluation and atomically refresh its result table."""

from __future__ import annotations

import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re


HERE = Path(__file__).resolve().parent
RTL = [
    "spatter-vcs-runs/gpu-stream-0",
    "spatter-verilator-runs/gpu-stream-4",
    "spatter-sampled-rtl-runs/gpu-stream-1",
    "spatter-sampled-rtl-runs/gpu-stream-2",
    "spatter-sampled-rtl-runs/gpu-stream-3",
    "spatter-sampled-rtl-runs/xrage5",
    "spatter-sampled-rtl-runs/xrage9-probe",
]
MODEL = [f"spatter-cyclotron-runs/gpu-stream-{i}" for i in range(5)] + [
    "spatter-cyclotron-runs/xrage5",
    "spatter-cyclotron-runs/xrage9",
]
EXPECTED_STATUS = {"xrage9-probe": "exploratory", "xrage9": "exploratory"}


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    summary = load_module("spatter_summary", HERE / "spatter-summarize.py")
    segments = load_module("spatter_segments", HERE / "spatter-verify-device-elfs.py")
    pending = []
    rows = []
    for relative in RTL + MODEL:
        path = HERE / relative
        result = json.loads((path / "result.json").read_text())
        status = result["status"]
        expected = EXPECTED_STATUS.get(path.name, "passed")
        if status in ("running", "built", "prepared"):
            pending.append(relative)
        elif status != expected:
            raise ValueError(f"{relative}: status {status}, expected {expected}")
        elf = path / "kernel.soc.elf"
        if not result.get("elf_sha256") or sha256(elf) != result["elf_sha256"]:
            raise ValueError(f"{relative}: ELF hash changed")
        if status == expected and relative.startswith("spatter-cyclotron-runs/"):
            log = (path / "cyclotron.log").read_text(errors="replace")
            match = re.search(r"SPATTER_CHECK digest=([0-9a-f]{16}) expected=(\w+) "
                              r"guards_intact=(\w+) nonzero_words=(\d+)", log)
            if not match or match.group(3) != "true" or int(match.group(4)) == 0:
                raise ValueError(f"{relative}: incomplete GPU output check")
            if path.name != "xrage9" and match.group(1) != result["expected_digest"]:
                raise ValueError(f"{relative}: full output digest mismatch")
        rows.append(summary.row_for(path, None))
    for sampled_name, original_name in (("gpu-stream-1", "gpu-stream-1"),
                                        ("gpu-stream-2", "gpu-stream-2"),
                                        ("gpu-stream-3", "gpu-stream-3"),
                                        ("xrage5", "xrage5"),
                                        ("xrage9-probe", "xrage9")):
        original = segments.device_segments(
            HERE / "spatter-vcs-runs" / original_name / "kernel.soc.elf")
        sampled = segments.device_segments(
            HERE / "spatter-sampled-builds" / sampled_name / "kernel.soc.elf")
        if original != sampled:
            raise ValueError(f"{sampled_name}: device code differs from original")
    output = io.StringIO()
    writer = csv.DictWriter(output, fieldnames=summary.FIELDS)
    writer.writeheader()
    writer.writerows(rows)
    target = HERE / "spatter-results.csv"
    temp = target.with_suffix(".tmp")
    temp.write_text(output.getvalue())
    temp.replace(target)
    if pending:
        print(f"pending RTL/model runs ({len(pending)}): {', '.join(pending)}")
        return 2
    print(f"complete: validated {len(rows)} original-size result rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
