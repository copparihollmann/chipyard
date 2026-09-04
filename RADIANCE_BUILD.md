# Building Radiance on this chipyard checkout

Reproduces the VCS build of `simv-chipyard.harness-RadianceMuonConfig` from the
state of `main` in `/scratch2/agustin/chipyard` on 2026-04-23.

## Why this dance is needed

`main` pins submodules (gemmini, rocket-chip, testchipip, tacit) that don't
compile with the radiance source checked out here. Radiance's CI targets the
chipyard `graphics` branch, so we switch chipyard to the local `graphics`
branch and align the supporting submodules to its pins. `saturn` (on
`opu-int8`) and `radiance` (ahead of graphics pin on the same line) are
preserved.

## One-time setup

```bash
cd /scratch2/agustin/chipyard

# Safety bookmark for main
git branch saved-main-before-graphics-switch

# Switch chipyard worktree to the local graphics branch
git checkout graphics

# Align submodules to graphics pins (skip saturn — keep opu-int8)
git -C generators/rocket-chip fetch origin 885dd5966a49ce19512e3cc3275515926e729ba2 --depth=1
git -C generators/rocket-chip checkout 885dd5966a49ce19512e3cc3275515926e729ba2

git -C generators/gemmini checkout 69a1c0383283d6ac95c99d2e53136fef4b0bb67e
# gemmini's nested rocc-tests may have a dirty include/gemmini_params.h from a
# prior build. Stash it so submodule init can proceed:
git -C generators/gemmini/software/gemmini-rocc-tests stash push -u -m "pre-radiance-build"
git -C generators/gemmini submodule update --init --recursive

git -C generators/testchipip checkout b2c2ab0469114baaa6b10ad5f1bbf2f42b464afd

git -C generators/tacit fetch origin 5d852bdd4b211e0d3300383fbd4cf7227cebba65 --depth=1
git -C generators/tacit checkout 5d852bdd4b211e0d3300383fbd4cf7227cebba65
```

Resulting submodule state:

| submodule    | commit       | source                          |
|--------------|--------------|---------------------------------|
| rocket-chip  | `885dd5966`  | chipyard graphics pin           |
| gemmini      | `69a1c0383`  | chipyard graphics pin           |
| shuttle      | `337385f36`  | already correct                 |
| testchipip   | `b2c2ab046`  | chipyard graphics pin           |
| tacit        | `5d852bdd4`  | chipyard graphics pin           |
| radiance     | `c8d939aa6`  | user's commit (ahead of graphics pin `bdd97007`) |
| saturn       | `ea3738001`  | `opu-int8` branch (preserved)   |

## Build

```bash
source env.sh
cd sims/vcs
make CONFIG=RadianceMuonConfig -j8
```

Produces `sims/vcs/simv-chipyard.harness-RadianceMuonConfig`. First build
~5–10 min including Scala compile, FIRRTL elaboration, and VCS compile.

## Run a binary

Pre-built fused SoC ELFs live at
`generators/radiance/cyclotron/test/fused/` (101 of them). Example:

```bash
source env.sh
cd sims/vcs
make run-binary CONFIG=RadianceMuonConfig \
  BINARY=/scratch2/agustin/chipyard/generators/radiance/cyclotron/test/fused/rv32ui-p-simple
```

Per-test logs land in
`sims/vcs/output/chipyard.harness.TestHarness.RadianceMuonConfig/<name>.log`.

### Known caveat

`RadianceMuonConfig` has Cyclotron differential testing enabled. The fused
test ELFs were matched against the graphics-pin radiance `bdd97007`; the
radiance checked out here is `c8d939a` (10 commits ahead), so diff-test trips
with `pc mismatch: rtl reached instruction unseen by model`. Either:

- move radiance to `bdd97007` for clean pass/fail,
- use a non-diff-test config (the README mentions
  `SUB_PROJECT=muon CONFIG=MuonCoreNoDiffTestConfig`),
- or rebuild Cyclotron from radiance `c8d939a`.

## Building your own kernel (vecadd) from radiance-kernels

The pre-built fused ELFs above are fine for RTL sanity, but you may want to
build your own kernel.  The radiance-kernels repo (plus the muon LLVM) is the
path.  None of the machine-wide prebuilt toolchains are accessible
(`/nscratch/yrh/vortex-install/llvm-muon` is mode `0700`), so build locally.

### Clone

```bash
cd /scratch2/agustin
git clone --depth=1 https://github.com/ucb-bar/radiance-kernels.git
```

### Build llvm-muon from source

The repo's `./scripts/llvm_prebuilt.sh` expects an `llvm/llvm-muon.tar.xz`
that isn't in the repo — use the from-source path (`./scripts/llvm.sh`) but
with a few tweaks:

1. Use the `merlin-dev` conda env for the bootstrap clang/clang++/cmake/ninja
   (the system clang-18 at `/usr/bin/clang++` picks GCC-14 for libstdc++
   which isn't installed as `-dev`, so its `-lstdc++` link fails).
2. Override `LLVM_AR`/`LLVM_NM`/`LLVM_RANLIB` → `/usr/lib/llvm-18/bin/llvm-*`
   (merlin-dev's `clangdev-feedstock` clang doesn't ship llvm-ar/nm/ranlib).
3. Patch `llvm/llvm-src/build.sh`:
   - Drop `libc` and `libcxx`/`libcxxabi` from `LLVM_ENABLE_RUNTIMES`
     (upstream libc target is broken for rv32 baremetal here, and libc++
     can't build without a libc sysroot — we get C++ headers from the conda
     riscv64 GCC instead, see below).
   - Add `-DLLVM_INCLUDE_BENCHMARKS=OFF -DLLVM_BUILD_BENCHMARKS=OFF` (clang
     22 rejects `__COUNTER__` in the bundled google-benchmark with
     `-Werror,-Wc2y-extensions`).
   - Make the `llvm-ar`/`nm`/`ranlib` paths env-overridable:
     `-DCMAKE_AR=${LLVM_AR:-$LLVM_PREBUILT/llvm-ar}` etc.

Kick off the build:

```bash
source /scratch2/agustin/miniforge3/etc/profile.d/conda.sh
conda activate merlin-dev
cd /scratch2/agustin/radiance-kernels/llvm/llvm-src
rm -rf build && mkdir build && cd build
unset CFLAGS CXXFLAGS
export LLVM_AR=/usr/lib/llvm-18/bin/llvm-ar
export LLVM_NM=/usr/lib/llvm-18/bin/llvm-nm
export LLVM_RANLIB=/usr/lib/llvm-18/bin/llvm-ranlib
bash ../build.sh /scratch2/agustin/miniforge3/envs/merlin-dev/bin \
                 /scratch2/agustin/radiance-kernels/llvm/llvm-muon
ninja -j $(nproc)
ninja install
```

On 48 cores this takes ~25–30 min. Result: `radiance-kernels/llvm/llvm-muon/`
with `bin/clang`, `bin/clang++`, `bin/llvm-objdump`, and
`lib/clang/18/lib/riscv32-unknown-elf/libclang_rt.builtins.a`.

### Build libmuonrt.a

radiance-kernels' `lib/` uses C++ headers (`<type_traits>` etc.) but no
rv32 GCC toolchain is installed.  Trick: point `RISCV_TOOLCHAIN_PATH` at the
conda riscv-tools dir (which has rv64 GCC 13.2.0 headers) and add the
target-arch-specific `bits/c++config.h` via `CPATH`:

```bash
cd /scratch2/agustin/radiance-kernels/lib
export CPATH=/scratch2/agustin/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/include/c++/13.2.0/riscv64-unknown-elf
make RISCV_TOOLCHAIN_PATH=/scratch2/agustin/chipyard/.conda-env/riscv-tools \
     RISCV_PREFIX=riscv64-unknown-elf
```

Produces `lib/libmuonrt.a`.  (clang still compiles for `-march=rv32im_zfinx
-mabi=ilp32`; only headers come from the rv64 tree.)

### Build a kernel (vecadd)

```bash
source /scratch2/agustin/chipyard/env.sh
cd /scratch2/agustin/radiance-kernels/kernels/vecadd
export CPATH=/scratch2/agustin/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/include/c++/13.2.0/riscv64-unknown-elf
make RISCV_TOOLCHAIN_PATH=/scratch2/agustin/chipyard/.conda-env/riscv-tools \
     RISCV_PREFIX=riscv64-unknown-elf
```

Produces `kernel.radiance.elf` (GPU-only) and `kernel.soc.elf` (host+GPU
fused) in the kernel dir.

### Run the kernel

```bash
source /scratch2/agustin/chipyard/env.sh
cd /scratch2/agustin/chipyard/sims/vcs
make run-binary CONFIG=RadianceMuonConfig \
  BINARY=/scratch2/agustin/radiance-kernels/kernels/vecadd/kernel.soc.elf
```

### Which CONFIG to use

`RadianceMuonConfig` and `RadianceSingleClusterConfig` **do not work** on
radiance `c8d939a`.  Any binary — our `kernel.soc.elf`, pre-built
`cyclotron/test/fused/bandwidth_smem_lw`, etc. — faults at exactly 5071000 ps
with `SimpleHellaCacheIF_1.sv:139` / `s2_xcpt` in `l0d.tlnbdCache.inIF`.
Verbose LSU trace shows both Muon cores issuing coalesced loads to address 0
with `inst=0000000000000000` before the fault, which points to a reset-time
init bug specific to those configs on this radiance commit.  Radiance CI
doesn't exercise them — it tests `RadianceTapeoutSimConfig` family instead.

**Use `RadianceTapeoutSimConfig`** (or `RadianceTapeoutSimTraceConfig`) and
pass `LOADMEM=1`:

```bash
source /scratch2/agustin/chipyard/env.sh
cd /scratch2/agustin/chipyard/sims/vcs

# Build — needs big heap, otherwise elaboration OOMs
export JAVA_TOOL_OPTIONS="-Xmx64g" JAVA_HEAP_SIZE="64G"
make CONFIG=RadianceTapeoutSimConfig -j8

# Run — LOADMEM=1 routes program loading through the right path for tapeout
make run-binary CONFIG=RadianceTapeoutSimConfig LOADMEM=1 \
  BINARY=/scratch2/agustin/radiance-kernels/kernels/vecadd/kernel.soc.elf
```

A successful run terminates at ~36.8 ms with:
```
$finish called from ... GPUResetAggregator.sv, line 117.
```
That path (`CanHaveGPUReset.scala:107`) prints `"no more active warps for 1k
cycles"` and `$finish`es — the normal completion signal when all warps go
idle.  No cache assertion.

## Restore path — back to chipyard main

```bash
cd /scratch2/agustin/chipyard
git checkout saved-main-before-graphics-switch           # or: git checkout main

git -C generators/rocket-chip checkout 8f1e33b253e3bce741861c0a2e3ba8b7ff85b292
git -C generators/gemmini     checkout 6ad65b90b1eb270c20ce4f04109e3dde0180b36f
git -C generators/testchipip  checkout bfe7aa36fc570ee17e3f461c1ed48525684b95ff
git -C generators/tacit       checkout fe61365292ef1fa60410a2a91c48a3e3d22b1a75

# Restore the nested gemmini-rocc-tests include/gemmini_params.h edit:
git -C generators/gemmini/software/gemmini-rocc-tests stash pop
```
