# MX-Gemmini optimization results — spike vs **real RTL** (silicon truth)

Canonical record. **Bottom line: spike speedups are inflated ~1.5–2×; the only silicon-true numbers are the VCS
cycle measurements below.** Use the RTL column, not the spike column, when claiming a speedup.

---

## 1. WHY spike and VCS differ so greatly (the root cause)

Spike is a **functional, CPI=1 instruction simulator — not a timing model.**
- `read_cycles()` reads the `cycle` CSR; spike advances it by **retired-instruction count**, not real cycles —
  `riscv-isa-sim/riscv/execute.cc:373-376` literally comments *"Model a hart whose CPI is 1"* (`mcycle->bump(instret)`).
- Gemmini ROCC ops (mvin/mvout/compute/loop_ws/fence) execute **functionally and retire in ~1 instruction with ZERO
  latency** — `libgemmini/gemmini.cc` (`CUSTOMFN(XCUSTOM_ACC)` ~2170-2289). No DMA-transfer time, no systolic-pipeline
  depth, no bandwidth, no bank conflicts are modeled.
- **So the spike "cycle" number ≈ the Rocket instruction count, each accelerator op = 1 instruction regardless of the
  data it moves/computes.** A transform that issues FEWER instructions (batch-2 mvin = half the mvins, fence removal,
  unrolling) shrinks that count a lot — but on RTL the **same bytes** still flow through the DMA (`LoadController.scala`:
  latency ∝ `bytes_to_read`) and the **same K** still flows through the 16×16 mesh (`Mesh.scala`: latency ∝ K + pipeline
  depth). RTL barely moves. **That is the entire gap.** RTL is the only timing truth; spike cannot be made
  cycle-accurate without modeling DMA+mesh latency.

**Corollary (predicts which spike numbers to trust):** the RTL benefit of a transform ∝ how **mvin/DMA-bound** the
kernel is. Tiny/compute-bound kernels → ~0 RTL gain; large-K + sub-byte (more mvin) → the most. Host-bound work
(attention's integer softmax = real Rocket instructions, counted the same on spike AND RTL) should translate ~1:1 —
i.e. attention's spike speedups are likely *more* trustworthy than matmul's.

---

## 2. REAL RTL speedups (VCS cycle measurements — silicon truth)

Instrumented baseline(batch-1) vs optimized(batch-2) with `read_cycles` around the accelerator region; both run on
VCS (RadianceGemminiOnlyConfig). All correctness-PASS on RTL.

| matmul kernel | RTL base→opt cycles | **RTL speedup** | spike claimed | spike inflation |
|---|---|---|---|---|
| fp6 128×128×512 | 73,085 → 46,958 | **1.56×** | 2.08× | 1.3× |
| fp4 128×128×512 | 40,383 → 33,253 | **1.21×** | 2.39× | 2.0× |
| fp8 128×128×256 | 41,872 → 36,577 | **1.14×** | 1.78× | 1.6× |
| fp8 64×64 | 3,019 → 3,024 | **1.00×** (none) | 1.99× | ∞ |

**Read:** the batch-2 mvin recipe is real on silicon but modest — **1.0–1.56×** — and the gain scales exactly with
mvin-boundedness (K=512 sub-byte fp6 best; tiny compute-bound fp8-64 zero), confirming the root cause. **fp6-512 at
1.56× is the best confirmed silicon speedup.** (fp8 128×128 excluded: its batch-2 LEGAL2 variant asserts when instrumented.)

**Attention — clean VCS cycle measurement (corrects an earlier mislabeled number):** the integer-softmax attention
kernel (`attn_fp8_vcs.c`, S=128 D=64) runs **1,561,969 cycles on VCS vs 762,147 on spike → RTL is 2.05× SLOWER**
(checksum PASS, single execution). [An earlier "762,138 VCS" figure was a mislabeled spike read_cycles, not a real
VCS measurement — this clean run overturns the "1:1" claim.] WHY 2×: spike models CPI=1 for ALL instructions; the
real in-order Rocket runs the ~16K-element integer softmax (iexp polynomial w/ multi-cycle mul, cache stalls on the
S1/P_q arrays) at **CPI ≈ 2**. So host-bound work is ALSO ~2× optimistic on spike — just for a different reason than
matmul.

**Unified root cause (both classes ~2× optimistic on spike):** spike under-counts BOTH (a) accelerator ops (modeled
~free / 1-cyc, vs real DMA+mesh latency) and (b) host instructions (CPI=1 vs real CPI≈2). Matmul is accelerator-bound
→ its inflation shows as instruction-count tricks not translating (spike 1.78–2.39× → RTL 1.0–1.56×). Attention is
host-bound → its inflation shows as the host code being ~2× slower on the real core (762K → 1.56M). **Neither spike
speedup is silicon-truth; RTL is.** (This corrects the prior note that attention spike numbers were "trustworthy" —
they're ~2× optimistic in absolute cycles too; relative softmax-optimizations may still hold but must be RTL-checked.)

---

## 3. Spike-metric map (all kernels explored — REAL DIRECTIONS, INFLATED MAGNITUDES)

These are spike numbers; per §1/§2 expect ~1.0–1.6× on silicon (less for tiny/compute-bound; matmul-family ceiling
≈1.56× from §2). Listed for direction/coverage, NOT as silicon claims.

**Autocomp real-shape winners (Qwen3-Coder-480B + Gemini-flash):** smolvla-ffn 2.07×, pi05-ffn 2.07×, smolvla-vis 1.87×,
llm-attn 1.83×, bitvla 1.59×, groot 1.44×, attn-causal 1.31×, mha 1.19×, gemv-1024 1.18×, gemv-3072 1.00×, gqa 1.04×.
**Earlier autocomp (golden_model shapes):** 64×128×128 5.43×, fp4-64³ 4.14×, fp6-64³ 4.05×, 64³ 3.20×, 128×128×256 3.01×,
conv 1.32×, attention 1.13×; tiled/flash/smolvla 1.00× (weak-model era — likely model-limited, not real floors).
**Fork (Rakanic shipped gold):** fp8-128 3.93×, fp8-256 3.64×.
**Hand-tuned + VCS-correctness:** fp4-512 2.39×, fp6-512 2.08×, fp8-64/128/256 1.6–1.99×, attention fused-gmax 1.07× (deployable).

---

## 3b. UPDATE — the VCS-profiling pipeline works; the real blocker is header arithmetic fidelity

Built `autocomp/vcs_profile.py`: wraps ANY kernel body in a baremetal harness (read_cycles + on-device golden
check), defining the spike-only intrinsics as RTL MMIO macros so the body runs UNMODIFIED. Reuses the LEGAL
preamble. **PROVEN CORRECT:** autocomp body + a COMMITTED header (matmul_fp8_64x64.h) → **VCS_TEST_PASSED**
(CHKHW==CHKGOLD on RTL). So harness, RTL macros, readout, gold-check, and the body are all RTL-correct.

**The spike→RTL fixes the pipeline applies to a body (the convention diffs that were the original mismatch):**
`config_st(DIM*sizeof(elem_t))` not `OUT_COLS*sizeof(out_t)`; mxquant LUT-granularity `tiles_K` not `1`;
`SPAD_DEST=0` not 128; RTL macros for `gemmini_mx_load_scales` (flat copy → `GEMMINI_SF_MEM_A/B`) and
`gemmini_mx_read_smem` (set store-stride + per-tile `gemmini_mvout` loop). With these, a body is RTL-correct.

**The remaining blocker (novel shapes):** a *generated* `golden_model.py` header's `C_out_bf16` does NOT match
the RTL output (RTL matched neither my spike-capture nor golden gold), because golden_model's arithmetic
(rounding) doesn't reproduce the RTL exactly — the committed headers were hand-matched to the hardware. So:
- Shapes **with a committed header** (fp8-64/128/256, fp4-512, fp6-512) → can be VCS-profiled now (== the LEGAL
  set already measured in §2; no new info).
- **Novel real-shapes** (smolvla-ffn 50×256×512, pi05-ffn, etc.) → need an RTL-faithful header. Fix = match
  golden_model's rounding flags to the RTL arithmetic (a bounded golden_model↔RTL fidelity task), then
  vcs_profile profiles them directly. Until then their RTL speedup is unmeasured (expected in the §2 band,
  1.0–1.6×, same mvin-batch transform class).

## 3c. PE-array UTILIZATION (conservative — derived from RTL cycles, not exaggerated)

**Definition:** `util = useful_MACs / (peak_MAC_per_cycle × cycles)`. Conservative choices (each avoids inflation):
- **RTL cycles, never spike** — spike undercounts cycles → would inflate util ~2×.
- **REAL MACs** = M·N·K of the true dims (padding excluded — counting padded work would inflate "useful").
- **Per-format peak, derived empirically, choosing the HIGHER peak when unsure** (higher peak → lower util →
  no exaggeration). The 16×16 mesh = **256 MAC/cyc for fp8** (1 MAC/PE/cyc). Sub-byte (fp4/fp6, 2 elem/byte)
  measured at **2.2×/1.6× fp8 throughput** → **512 MAC/cyc** (2 sub-byte MACs/PE/cyc). Proof peak≠256 for fp4:
  at 256, fp4-512's compute alone (32,768 cyc) would exceed its whole RTL runtime (33,253) leaving ~485 cyc
  for mvin — impossible; peak=512 (compute 16,384 + mvin ~16,869) is self-consistent. Using 256 → a false 98.5%.
- **Accelerator-region cycles** (mvin+compute+store) as denominator — the *optimistic* bound. End-to-end
  (incl. host softmax/staging) is much lower (see attention below).

| kernel | peak MAC/cyc | RTL cyc | **accel-region util** |
|---|---|---|---|
| fp8 128×128×256 baseline | 256 | 41,872 | 39% |
| fp8 128×128×256 batch-2 | 256 | 36,577 | **45%** |
| fp8 64×64 | 256 | 3,019 | 34% |
| fp4 128×128×512 batch-2 | 512 | 33,253 | **49%** |
| fp6 128×128×512 batch-2 | 512 | 46,958 | **35%** |
(fp6 peak=512 assumed uniform sub-byte; if fp6 peak were 256 its util would be ~70% — needs HW confirmation.)

**Read — headroom is real but the lever is NOT double-buffering (corrected by VCS experiment).** The array runs
at **~35–49%** of peak. My first hypothesis was "idle from non-overlapping mvins → fix with double-buffering."
**VCS disproved it:** a hand-double-buffered fp8-128×128×256 (K-split, ping-pong banks, ex_accumulate) ran
**36,730 cyc vs 36,577 baseline — 0.4% WORSE**, still correct (PASS). Decomposition probe: loads-alone = 20,703
cyc, compute-alone ≈ 22,891, fully-serial sum = 43,594 — but the **baseline already runs in 36,577 < 43,594, i.e.
it already overlaps ~7,000 cyc** of load+compute. Why: the kernel issues *all* mvins then *one* `loop_ws_spad`, and
Gemmini's ROB tracks scratchpad deps, so K=0 compute fires as soon as those tiles land — the ROB extracts the
available overlap automatically. A manual K-split imposes a *stricter* order the ROB can't beat. (Also `a_spad_id`/
`b_spad_id` are inert in `spad_only` mode — bank addresses are the real control.)
**So the 45% is NOT poor scheduling — it's that loads (20.7k) are nearly as expensive as compute (22.9k), and the
ROB already hides the rest.** Overlap ceiling ≈ max(20.7k, 22.9k) ≈ 23k cyc — unreachable by reordering. **The real
lever is reducing LOAD cost** (fewer/larger DMA bursts, better DRAM layout, less mvin traffic), not double-buffering.
fp6 lowest (35%, LUT overhead); fp4 highest (49%).

**End-to-end (the brutally honest number):** for **attention**, the array is idle during the ~97% host
integer-softmax, so **end-to-end PE utilization ≈ 1–3%** (it's host-bound, not array-bound). For the big tiled
matmuls, host staging similarly drags end-to-end util well below the region numbers. So: matmul *compute regions*
are ~35–49% utilized with ~2× headroom; whole *kernels* (esp. attention) are far lower because the bottleneck is
the no-FPU host, not the array — which is why the HW-softmax/Normalizer track (offload softmax) is the big lever
there, not array scheduling.

**Winner RTL cycles (data-independent → measurable despite header-fidelity blocker):** matmul RTL cycle count
depends only on shape + instruction schedule, NOT data values, so a winner body can be cycle-measured with a
mismatching gold (correctness shows FAIL, but MMCYCLES is valid). Confirmed on the **fitting** winner:
- real-bitvla (32×256×256) winner: **31,813 RTL cyc → util 25.7%** (low: M=32 = only 2 row-tiles, skinny-M
  underutilizes the 16-row array). Spike was 1,004 cyc → RTL/spike = 31.7× (extreme accel-bound: spike counts
  accel ops ~free, so its absolute cycles are almost all host loop overhead).

**TILED winners — MEASURED on RTL** (vcs_profile.py `--tiled` path; cycles data-independent so valid; whole-BLK
drain mirrors the RTL-validated mvout so the accel cycle count is exact):
| winner | shape | RTL cyc | util |
|---|---|---|---|
| pi05-ffn | 256×256×512 | 306,698 | **42.7%** (32 full 64-blocks → array well-fed) |
| smolvla-ffn | 50×256×512 | 98,151 | **26.1%** (M=50 pads to one 64-row block → underfills) |
| (tiled baseline 128×128×128, validation) | | 35,905 | 22.8% |
Consistent with the fitting band (25–49%): well-shaped tiled (pi05) ≈ fitting fp8 (~45%); skinny-M (smolvla M=50,
bitvla M=32) drops to ~26% from row-tile underfill. Same load-bound story — headroom is in load traffic, not scheduling.

## 4. Finding: autocomp-generated kernels are NOT directly RTL-correct (superseded by 3b — it's the HEADER, not the body)

Attempting to VCS-profile the autocomp bodies (via `autocomp/vcs_profile.py`, which wraps any body in a baremetal
read_cycles + golden-check harness): the bodies **build, run, and compute on RTL (valid cycles, `C_hw` written), but
produce WRONG VALUES** (`CHKHW ≠ golden`). The spike functional model tolerates scale-format / store-config conventions
(`config_st`, `mxquant_config_mvout` granularity, scale-exp/header format, B-slot/scale layout) that RTL does **not**.
The hand-written `*_LEGAL.c` kernels exist precisely as the manual RTL ports that fix these. So **autocomp's
spike-optimized kernels can't be cheaply VCS-validated or deployed as-is** — each needs a manual RTL port. (Ledger ts=6.)

Consequence: the trustworthy matmul-family RTL numbers are the LEGAL set (§2); the autocomp spike-stars (§3) are
expected to fall in/below the same 1.0–1.6× band (same mvin-batching transform class), but confirming each requires
porting it like a LEGAL kernel.

---

## 5. The fix — a FAITHFUL spike metric (IMPLEMENTED) + RTL→spike auto-calibration (designed)

**DONE — faithful-cycle model in libgemmini (`GEMMINI_FAITHFUL_CYCLES=1`).** Instead of just flagging that spike is
2× optimistic, spike now bumps its cycle CSR by a DATA-driven latency (cycle-only, never affects correctness):
per-mvin `K_DMA·cols·rows` (= total bytes, batching-independent), per-loop_ws `K_CMP·(MACs/peak)`. Coefficients
`K_DMA=0.143`, `K_CMP=1.804` fit to VCS. **Validated: spike-faithful tracks RTL within ±9%** (fp8-256 +3%, fp8-64
+9%, fp4 +9%, fp6 −23% LUT) vs base spike off 20–40×; and **the instruction-count mirage is gone** (batch-1 37,617
≈ batch-2 37,515, vs base-spike's fake 1.13×). So `autocomp ... GEMMINI_FAITHFUL_CYCLES=1` now optimizes a
silicon-real metric — it can no longer win by batching mvins or removing fences; it must reduce bytes/compute or
exploit real overlap. (gemmini.cc; SPIKE_MODEL_CHANGES.md "Faithful-cycle model".) Coefficients are env-overridable
(`GEMMINI_K_DMA_MILLI`/`GEMMINI_K_CMP_MILLI`) so recalibration needs no recompile.

**DESIGNED — RTL→spike auto-calibration (the "automate from the .v/.sv" ask).** The elaborated RTL (what VCS runs)
IS the ground truth; calibration fits the spike model to it:
- `autocomp/calibrate_spike.py` (to build): a CALIBRATION SUITE of diverse shapes/formats → for each, build a VCS
  kernel (reuse `vcs_profile.py`), run on VCS, parse `MMCYCLES` → feature vector `[bytes, MACs/peak, fp6_LUT_flag,
  host_instr]` paired with RTL cycles. `numpy.lstsq` fit (cost_model.py already has this `fit()` scaffold) →
  emit `K_DMA`,`K_CMP` (+ optional fp6-LUT and host-CPI terms). Write them to env / a coeffs file the spike reads.
  Re-run whenever the RTL changes → spike stays faithful automatically.
- Structural params (DIM, mesh depth, DMA max-bytes, banks) are read from the Chisel config / `gemmini_params.h`,
  not hand-coded — so the harness reparameterizes per config. (Static latency extraction directly from the `.v`
  netlist = a research problem; empirical RTL-run calibration is the robust, practical path and is config-agnostic.)
- Extensions to raise fidelity past ±9%: add an **fp6 LUT-decode** term (fixes the −23%), and a **host-CPI≈2** term
  for host-bound kernels like attention (RTL 1.56M vs spike 762K = 2.05×; spike's CPI=1 host model is the gap).

**Also recommended (orthogonal):** VCS-in-the-loop validation of top-K candidates, and a generator that emits
RTL-correct (LEGAL-style) kernels so winners are deployable (§3b/§4). Faithful-spike makes the SEARCH honest;
RTL-correct codegen makes the OUTPUT deployable.

### LEARNINGS (the durable takeaways)
1. **Spike is a CPI=1 functional model, not a timing model** — it undercounts accel ops (~free) AND host instrs
   (CPI 1 vs real ≈2). Both kernel classes are ~2× optimistic, for different reasons.
2. **The spike↔RTL gap = fraction of work that's accelerator-instruction-count (inflated) vs real cost.** Matmul
   (accel-bound): instruction-count tricks don't translate. Attention (host-bound): host code 2× slower on real Rocket.
3. **Matmul is LOAD-bound, near its overlap ceiling.** Double-buffering does NOT help (VCS-tested: 0.4% worse) — the
   ROB already overlaps load+compute. Utilization 35–49% reflects loads ≈ compute, not poor scheduling. Real lever =
   cut DMA/load traffic.
4. **Autocomp bodies are spike-correct but NOT RTL-correct** (scale-format/config conventions); cycles are
   data-independent so still measurable. Deploying needs a LEGAL-style port.
5. **The fix is a faithful metric, now implemented** — so the search optimizes silicon, not spike artifacts.

---

## Files / reproduce
- RTL profiling: `autocomp/vcs_profile.py` (build + read_cycles + golden check); instrumented LEGAL pairs in
  `gemmini-rocc-tests-ref/bareMetalC/matmul_tiled_*{,_LEGAL}.c` (MMCYCLES print).
- Run: `sims/vcs/simv-…RadianceGemminiOnlyConfig +permissive +dramsim +dramsim_ini_dir=<…> +max-cycles=10000000
  +loadmem=<bin> +permissive-off <bin>` (`source env.sh` for the riscv toolchain to build).
- Faithful spike: `generators/gemmini/software/libgemmini/gemmini.cc` (`gmx_faithful_cycles`/`gmx_k_dma`/`gmx_k_cmp`),
  enable with `GEMMINI_FAITHFUL_CYCLES=1`; reinstall via `make -C .../libgemmini install` after `source env.sh`.
- Data: `autocomp/hand_tune_ledger.jsonl` (ts=5 RTL cycles, ts=6 autocomp-not-RTL-correct, ts=8/10 attention 2.05×,
  ts=11 bitvla winner, ts=13 double-buffer null result, ts=14 faithful-model validation),
  `autocomp/output/transform_ledger.jsonl` (mined decisions), `AUTOCOMP_PLAYBOOK.md`, `SPIKE_MODEL_CHANGES.md`.
- Evidence: `riscv-isa-sim/riscv/execute.cc:373-376`; `libgemmini/gemmini.cc:~2170-2289`;
  `gemmini/src/main/scala/gemmini/{LoadController,Mesh,ExecuteController}.scala`.
