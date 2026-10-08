#!/usr/bin/env bash
cd /scratch/agustin/projects/radiance-kernels/kernels
# real completion = run_standalone wrote sim_rtl.log
for i in $(seq 1 240); do
  n=0
  for k in fp4 persistent; do [ -f autocomp_ffn_block_$k/sim_rtl.log ] && n=$((n+1)); done
  [ $n -ge 2 ] && break
  sleep 30
done
echo "=== FP4/PERSISTENT DONE (n=$n/2) ==="
for k in fp4 persistent noxnrt prefetch; do
  python3 - "autocomp_ffn_block_$k/trace_rtl.sqlite" "$k" <<'PY'
import sqlite3,sys
t,k=sys.argv[1],sys.argv[2]
try:
  c=sqlite3.connect(t).cursor()
  mx=c.execute("select max(cycle) from inst").fetchone()[0]
  n=c.execute("select count(*) from inst").fetchone()[0]
  print(f"{k:12s} trace_max(core)={mx} tile={mx*2} insts={n}")
except Exception as e: print(f"{k:12s} ERR {e}")
PY
done
