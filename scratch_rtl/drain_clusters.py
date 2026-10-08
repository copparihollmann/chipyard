#!/usr/bin/env python3
"""Report ALL temporal drain-spin clusters in a combined-config RTL trace-db.

Unlike rtl_kernel_cycles.py (which takes the global MIN over drain PCs -> only valid
for a SINGLE drain, i.e. single-launch kernels), this groups the drain-PC hits into
temporal clusters separated by large cycle gaps, so multi-launch kernels (which drain
between every phase) expose each phase boundary. The LAST cluster's start = FFN-block
completion (hand-off to the final pre-verify drain). Also reports trace_max (last
retired instruction, ~ whole-kernel span incl. verify tail).

Trace `cycle` is the Muon CORE clock; tile-clock = core*2.
Usage: drain_clusters.py <trace.sqlite>
"""
import sqlite3, sys

def main(path):
    db = sqlite3.connect(path); c = db.cursor()
    top = c.execute(
        "select pc, count(*) n from inst group by pc order by n desc limit 12"
    ).fetchall()
    if not top:
        raise SystemExit(f"{path}: empty trace")
    maxn = top[0][1]
    drain_pcs = [pc for pc, n in top if n >= 0.5 * maxn]
    ph = ",".join(str(int(p)) for p in drain_pcs)
    rows = c.execute(
        f"select cycle from inst where pc in ({ph}) order by cycle"
    ).fetchall()
    cyc = [r[0] for r in rows]
    # cluster by gaps > 5000 core-cycles
    clusters = []
    start = prev = cyc[0]
    for x in cyc[1:]:
        if x - prev > 5000:
            clusters.append((start, prev)); start = x
        prev = x
    clusters.append((start, prev))
    tmax = c.execute("select max(cycle) from inst").fetchone()[0]
    tmin = c.execute("select min(cycle) from inst").fetchone()[0]
    print(f"trace_min(core)={tmin}  trace_max(core)={tmax} (tile={tmax*2})")
    print(f"drain PCs: {[hex(int(p)) for p in drain_pcs]}  #clusters={len(clusters)}")
    for i,(s,e) in enumerate(clusters):
        print(f"  cluster[{i}] start(core)={s} (tile={s*2}) end={e} span={e-s}")
    # FFN-complete marker = start of the LAST drain cluster
    last = clusters[-1][0]
    print(f"FFN_complete(core)={last}  (tile={last*2})")

if __name__ == "__main__":
    main(sys.argv[1])
