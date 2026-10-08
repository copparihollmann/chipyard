#!/usr/bin/env bash
cd /scratch/agustin/projects/radiance-kernels/kernels
for i in $(seq 1 90); do
  ndone=0
  for k in fp4 persistent noxnrt prefetch; do
    grep -qa "SFUPipe\|PASSED\|FAILED\|8000000c" autocomp_ffn_block_$k/sim_rtl.log 2>/dev/null && ndone=$((ndone+1))
  done
  [ $ndone -ge 4 ] && break
  sleep 20
done
echo "=== ALL DONE ($ndone/4) — trace_max(core) per variant ==="
for k in fp4 persistent noxnrt prefetch; do
  t=autocomp_ffn_block_$k/trace_rtl.sqlite
  python3 - "$t" "$k" <<'PY'
import sqlite3,sys
t,k=sys.argv[1],sys.argv[2]
try:
  c=sqlite3.connect(t).cursor()
  mx=c.execute("select max(cycle) from inst").fetchone()[0]
  mn=c.execute("select min(cycle) from inst").fetchone()[0]
  n=c.execute("select count(*) from inst").fetchone()[0]
  print(f"{k:12s} trace_max(core)={mx} span={mx-mn} tile={mx*2} insts={n}")
except Exception as e:
  print(f"{k:12s} ERR {e}")
PY
done
