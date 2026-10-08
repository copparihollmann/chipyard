#!/usr/bin/env python3
"""Build a local, portable U250 evidence archive after all queue jobs finish."""

import csv
import hashlib
import io
from pathlib import Path
import subprocess
import tarfile
import tempfile


ROOT = Path("/scratch/agustin/projects/chipyard")
REPO = ROOT / "radiance-kernels-firesim"
EVAL = REPO / "kernels/evaluation/firesim"
INPUTS = ROOT / "firesim-hpc-inputs"
BITSTREAM = Path("/scratch/nicorakela/firesim_radiance-slim.tar.gz")
DRIVER_DIR = Path("/scratch/nicorakela/chipyard/sims/firesim/sim/output/xilinx_alveo_u250/xilinx_alveo_u250-firesim-FireSim-FireSimE4M3MxGemminiRadianceConfig-BaseXilinxAlveoU250Config")
OLD_ARCHIVE = ROOT / "radiance-report-final-with-brief-20260929.tar.gz"
OUTPUT = ROOT / "radiance-firesim-u250-evidence-20261004.tar.gz"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def check(path, expected):
    actual = sha256(path)
    if actual != expected:
        raise ValueError(f"hash mismatch for {path}: {actual} != {expected}")


def main():
    subprocess.run(["python3", str(EVAL / "capture.py"), "--require-complete"], check=True)
    subprocess.run(["python3", str(EVAL / "make_comparison.py"), "--check"], check=True)
    subprocess.run(["python3", str(EVAL / "verify.py")], check=True)
    with (EVAL / "plan.csv").open(newline="") as stream:
        plan = list(csv.DictReader(stream))
    with tempfile.TemporaryDirectory() as temp:
        git_bundle = Path(temp) / "radiance-kernels-spatter-workloads.bundle"
        subprocess.run(["git", "bundle", "create", str(git_bundle), "spatter-workloads"],
                       cwd=REPO, check=True)
        files = [(path, "evidence/" + str(path.relative_to(EVAL)))
                 for path in EVAL.rglob("*")
                 if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc"]
        for row in plan:
            path = INPUTS / row["elf_snapshot_path"]
            check(path, row["elf_sha256"])
            files.append((path, "inputs/" + row["elf_snapshot_path"]))
        check(BITSTREAM, "cfa8bce8c76d216c12a5771337566a0c543cde74330fcf5323094f215ce4c3cc")
        files.append((BITSTREAM, "hardware/firesim_radiance-slim.tar.gz"))
        driver = DRIVER_DIR / "FireSim-xilinx_alveo_u250"
        check(driver, "37f9039c56d21d5a7eff21a4ffe3c0df3a9af991ae346781c8d76c3e7e8ba318")
        files.append((driver, "hardware/FireSim-xilinx_alveo_u250"))
        files.append((DRIVER_DIR / "driver-bundle.tar.gz", "hardware/driver-bundle.tar.gz"))
        checker = INPUTS / "model-checker/spatter_check"
        check(checker, "53236c3bfd41ed0163e6f7629cefe077ded2df1cfa4589228c176003bd6b6e5c")
        files.append((checker, "model-checker/spatter_check"))
        files.append((git_bundle, "radiance-kernels-spatter-workloads.bundle"))
        old_sha = sha256(OLD_ARCHIVE)
        readme = (
            "U250 FireSim evidence for the Radiance STREAM/Spatter work.\n"
            "The evidence/ directory contains the PR report, scripts, CSVs, and raw queue logs.\n"
            "The inputs/ directory contains every exact ELF named by evidence/plan.csv.\n"
            "The hardware/ directory contains the bitstream and matching driver.\n"
            "The git bundle preserves the kernel branch as exported.\n"
            "The earlier model/RTL/upstream golden archive is separate: \n"
            f"{OLD_ARCHIVE}\nSHA-256 {old_sha}\n"
            "Keep both archives for a complete local evidence set.\n"
        ).encode()
        manifest = ["path,bytes,sha256"]
        for source, name in sorted(files, key=lambda entry: entry[1]):
            manifest.append(f"{name},{source.stat().st_size},{sha256(source)}")
        with tarfile.open(OUTPUT, "w:gz") as archive:
            for source, name in sorted(files, key=lambda entry: entry[1]):
                archive.add(source, arcname=name, recursive=False)
            for name, data in (("README.txt", readme),
                               ("manifest.csv", ("\n".join(manifest) + "\n").encode())):
                info = tarfile.TarInfo(name)
                info.size = len(data)
                info.mode = 0o644
                archive.addfile(info, io.BytesIO(data))
    print(f"{OUTPUT}\nSHA-256 {sha256(OUTPUT)}")


if __name__ == "__main__":
    main()
