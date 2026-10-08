#!/usr/bin/env bash
# Build + Verilator-run a STANDALONE radiance-kernels kernel dir (own kernel.cpp/data/Makefile).
#   run_standalone.sh <kernel_dir_name> [DRAIN]
# Emits trace-db + sim log under the kernel dir; prints FFN-complete + trace_max via drain_clusters.py
set -eo pipefail
K=$1
DRAIN=${2:-50000}
CHIPYARD=/scratch/agustin/projects/chipyard
RK=/scratch/agustin/projects/radiance-kernels/kernels/$K
SIMBIN=$CHIPYARD/sims/verilator/simulator-chipyard.harness-RadianceSingleClusterConfig
source $CHIPYARD/env.sh >/dev/null 2>&1 || true
export LLVM_MUON=${LLVM_MUON:-/scratch2/agustin/radiance-kernels/llvm/llvm-muon}
export RISCV64_TOOLCHAIN_PATH=${RISCV:-$CHIPYARD/.conda-env/riscv-tools}

rm -f "$RK"/kernel.mu.o "$RK"/kernel.soc.elf "$RK"/kernel.radiance.elf "$RK"/kernel.o
make -C "$RK" EXTRA_MU_CFLAGS="-DDRAIN_ITERS=$DRAIN" kernel.soc.elf > "$RK/build_rtl.log" 2>&1 \
  || { echo "COMPILE-FAIL $K"; tail -15 "$RK/build_rtl.log"; exit 1; }

TRACE="$RK/trace_rtl.sqlite"; rm -f "$TRACE"
OUT=$(bash -c "ulimit -s unlimited; exec timeout 7200 '$SIMBIN' +permissive +verbose \
      +max-cycles=20000000 +trace-db='$TRACE' \
      +loadmem='$RK/kernel.soc.elf' +permissive-off '$RK/kernel.soc.elf'" 2>&1) || true
echo "$OUT" > "$RK/sim_rtl.log"
echo "===== $K (DRAIN=$DRAIN) ====="
echo "$OUT" | grep -iE 'PASSED|FAILED|tohost|Assertion|UART' | tail -4
python3 $CHIPYARD/scratch_rtl/drain_clusters.py "$TRACE" 2>&1 || echo "extract-fail"
