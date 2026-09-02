# MX-Gemmini × autocomp — Status

> **See [`AUTOCOMP_PLAYBOOK.md`](AUTOCOMP_PLAYBOOK.md)** for the single source of truth on how the search
> works, produced-file locations, tuning decisions + reasoning, the run recipe, and the HW future track.
> This file is the chronological results/findings log.

_Last updated: 2026-06-05. Branch: `mx-gemmini-setup`. Model: Claude Sonnet 4.6 via AWS Bedrock._

End-to-end pipeline: **PyTorch/MLIR → MX-Gemmini kernel → autocomp optimization**, validated on the
spike MX functional model, with live USD cost tracking under a $100 cap.

## 1. Operator × datatype coverage

All cells generate, golden-check through the real eval, and are wired into `run_mx_pipeline.PROBLEMS`.
Each datatype is dispatched by `_FMT_TAG` to a `_gen_*_fp64` path; the fp8 paths are untouched.

| operator | fp8:e4m3 | fp6:e3m2 | fp4:e2m1 |
|---|---|---|---|
| matmul (fitting) | ✅ | ✅ | ✅ |
| matmul (non-square M≠N) | ✅ | ✅ | ✅ |
| matmul (outer-tiled) | ✅ | ✅ | ✅ |
| attention (non-flash) | ✅ | ✅ | ⚠️ coarse |
| flash attention | ✅ | ✅ | ⚠️ coarse |
| conv (patch-embed) | ✅ | ✅ | ✅ |

True-correctness vs an independent fp32 reference (the % is the fp8/bf16 quant level, not error):
matmul fp8 58% / fp4 99% / fp6 55%; attention fp8 88% / fp6 84%; flash fp8 94%(S128)/84%(S256) / fp6 90%.

## 2. Spike-model (libgemmini) changes

Two changes total (both in `generators/gemmini/software/libgemmini/gemmini.cc`):
1. `flush_funct`: clear `mx_smem` on flush (detailed below).
2. `mvin()`: reject zero-byte and block/wide (`cols>DIM`) mvin to match the RTL load controller
   (see sec 4c — added 2026-06-05 after VCS revealed RTL-illegal spike "wins").

### 2.1 smem-clear-on-flush

`generators/gemmini/software/libgemmini/gemmini.cc`, `flush_funct` (5 lines):
clear `mx_smem` on flush, because the MX shared memory accumulates (`bf16_accum_add`) and
software-tiled matmuls issue one `loop_ws_spad` per output block — without the clear, each block piles
onto the previous block's residue. Rebuild after a clean checkout:
`make -C generators/gemmini/software/libgemmini install`. Everything else lives in the autocomp
generators, not the hardware model.

## 3. Correctness bugs found & fixed (several masked by golden-check)

| bug | symptom | fix |
|---|---|---|
| N≠K matmul B-slot transpose | non-square matmuls garbage | scratchpad slot `(k·tiles_J+j)` |
| smem not cleared on flush | tiled GEMM cross-block residue | `std::fill` in `flush_funct` |
| P@V accumulator overflow | attention/flash ~½ outputs inf/nan | P-requant divisor 448→16 |
| flash per-tile KT-scale slice | flash ~½ rows wrong | repack tile scales group-major |
| fp4 P zero-collapse | fp4 attn/flash all-zero output | P-quant target T 0.5→2.0 |
| non-square LUT granularity | fp6 M>N rows past first garbage | `gl = max(M,N).bit_length()` |

## 4. Performance

**autocomp search on fitting matmuls** (score = spike cycles):

| shape / dtype | initial → best | speedup |
|---|---|---|
| 64×64×64 fp8 | 279 → 92 | 3.03× |
| 64×128×128 fp8 | 611 → 166 | 3.68× |
| 128×128×256 fp8 | 1149 → 382 | 3.01× |
| 128×128×256 fp4 | 675 → 163 | 4.14× |
| 128×128×256 fp6 | 680 → 168 | 4.05× |

**Accelerator-centric tiled rewrite** (manual; tiled was 99% CPU output-assembly, now streams smem→DRAM
by hardware DMA; applies to fp8/fp6/fp4):

| shape | before → after | speedup |
|---|---|---|
| 256×256×512 | 3,193,047 → 43,019 cyc | 74× |
| smolvla-act 128×320×320 | ~2,000,000 → 15,922 cyc | ~125× |
| **smolvla-proj 1024×768×768** (dominant, 60×) | **~19M → 626,801 cyc** | **~30×** |

autocomp on the rewritten tiled finds **1.00×** more → the rewrite is already optimal.

**Attention / flash:** all precisions ~1.00–1.04× (HW-bound). It's ~97% scalar softmax (~60
instr/elem: exp ~15, fp8 encode ~15, reads, scale). Lean rewrites (fast-exp + pass-fusion) cap at
~1.08× — software-irreducible; only a HW exp/softmax unit would help.

## 4b. Reference-fork cross-check (Rakanic/gemmini-rocc-tests @ dev)

Cloned to `/scratch/agustin/projects/gemmini-rocc-tests-ref` (commit `42af913`). These kernels carry
an **independent PyTorch golden** (`golden_model.py`: fp8/6/4 quant → 7-bit product mantissa → bf16
RNE accumulate → fpE8M0 scales), so passing them = TRUE correctness, not self-consistent gold.

**All reference kernels PASS on our spike** → our MX spike model is MX-spec-correct (independently
verified, not just self-consistent). Cycle counts measured with the identical `read_cycles()` region:

| shape / dtype | reference baseline (fork) | our baseline | our optimized |
|---|---|---|---|
| 64×64×64 fp8 | 292 | 279 | **92** |
| 128×128×256 fp8 | 1605 | 1149 | **382** |
| 128×128×512 fp6 (requant) | 1937 | — | — |
| 128×128×512 fp4 (requant) | 1656 | — | — |

**Conclusion — were our baselines good?** YES. On the apples-to-apples shapes our baseline was as fast
or faster than the fork's hand-written kernel (64³: 279 vs 292; 128×128×256: 1149 vs 1605), and our
optimized result is **3.2–4.2× faster than the reference baseline**. Our reported speedups were NOT
inflated by weak strawman baselines. Both use the same accelerator-centric single-`loop_ws_spad` form.

### VCS RTL cross-check (cycle-accurate)
The fp8 128×128×256 reference kernel runs on `RadianceGemminiOnlyConfig` RTL via VCS (existing
`simv-…-RadianceGemminiOnlyConfig`, no rebuild) and **PASSES against the independent gold** (`$finish`
@ 557,653,500 ps, ~260 s wall). So the spike→VCS path is verified end-to-end; spike functional
correctness is confirmed cycle-accurate on real RTL. Run pattern:
`make -C sims/vcs run-binary CONFIG=RadianceGemminiOnlyConfig LOADMEM=1 BINARY=<build/…-baremetal>`
(build the binary WITHOUT `-DSPIKE_SIM`, i.e. via `build.sh`→`build/`, not `build_spike/`).

### Re-based autocomp problems on fork gold
New module `autocomp/.../mx_pipeline/fork_problem_gen.py` turns each fork kernel + its SHIPPED header
into an autocomp problem (harness w/ independent gold + baseline sol). Registered in
`run_mx_pipeline.PROBLEMS` as `fork-mm-{fp8-64,fp8-128,fp8-256,fp6-512,fp4-512}` (run with
`--only fork-mm`). All 5 baselines validated on spike (293/852/1606/1938/1657 cyc, Correct vs
independent gold).

**Key finding — golden_model.py regen is NOT a valid oracle for arbitrary shapes.** Re-running
`golden_model.py` (as `matmul_gen.gen_input_header` does) produces a `C_out_bf16` that does NOT match
the hardware (all words differ vs spike capture), but the fork's SHIPPED headers match bit-for-bit
(0/1024). So independent gold is only trustworthy from the shipped headers — which **vindicates the
original self-capture design** for arbitrary shapes. `matmul_gen` now has `gold_src=capture|independent|
validate` but only the fork's fixed shapes can use `independent`.

## 4c. ⚠️ CRITICAL: spike↔RTL divergence — mvin cols field is 6-bit (root cause FOUND 2026-06-06)

Autocomp on the fork baselines found big spike speedups (fp8-128 853→217 = 3.93×, fp8-256
1607→442 = 3.64×) whose **dominant transform is "batched/wide mvin"** (`gemmini_extended_mvin(dram,
sp, batch*DIM, DIM)`, `MAX_BLOCK_LEN=4` → `cols = 4*16 = 64`). Both winners are CORRECT on spike
against independent gold but **FATAL on the RadianceGemminiOnlyConfig RTL (VCS)**, both at
`LoadController.scala:191 assert(mvin loads > 0 bytes)`:
- fp8-256 → `:191` @189,377,000 ps (reproduced this session, see `/tmp/vcs_winner_fp8_256.log`)
- fp8-128 → `:191` (same cause; the earlier ":138 block_stride" attribution was **WRONG** — the
  fp8-128 winner has `block_stride=16 >= rows=16`, so `:138` is satisfied. Confirmed via spike trace.)

**TRUE ROOT CAUSE (definitive, from generated RTL):** the mvin `num_cols` field is only **6 bits
wide** in the elaborated hardware. Generated `LoadController.sv:220` computes
`bytes_to_read = {6'h0, rs2[37:32]} * actual_rows_read` and `:448` wires `cols = {10'h0, rs2[37:32]}`.
The mvin macro packs a full 16-bit `cols << ADDR_LEN(=32)`, so `cols=64 = 2^6` lands in bit 38 —
**outside** the 6-bit field `rs2[37:32]` — and the hardware reads `64 & 0x3F = 0`. Hence
`bytes_to_read = 0 × 16 = 0` → `:191` $fatal. Spike decoded cols as a full 16-bit value and never
modeled the field truncation, so it silently accepted cols=64.

This is a **software/hardware parameter mismatch**: `gemmini_params.h` declares `MAX_BYTES=64`
(⇒ `MAX_BLOCK_LEN = MAX_BYTES/DIM = 4` ⇒ cols up to 64), but the elaborated RTL's cols field caps at
**63** (max block batch **3**, cols≤48). The hardware field width is authoritative. Block/wide mvin
IS supported by this config (contrary to the earlier pessimistic note) — but only up to **cols=48**.

**Implications:**
1. Spike-only speedups using batched mvin with `batch=4` (cols=64) are RTL-illegal. **`batch≤3`
   (cols≤48) wide mvin is legal** and remains a real RTL speedup lever — re-run the search with the
   faithful spike (below) and it will naturally steer to legal batching.
2. The reference BASELINES are RTL-valid (cols=16); only the batch-4 optimizations are illegal.
3. FIX (IMPLEMENTED 2026-06-06): patched `libgemmini/gemmini.cc` `mvin()` to **model the 6-bit
   field truncation faithfully**: `cols = cols_raw & ((1<<MVIN_COLS_BITS)-1)` with `MVIN_COLS_BITS=6`,
   an explicit `cols field overflow` diagnostic, and the corrected `:191`(bytes>0)/`:138`(block_stride
   ≥rows) asserts evaluated on the *truncated* cols. Now spike matches RTL bit-for-bit. Verified:
   both fp8 reference baselines (cols=16) PASS; both fp8-128/256 winners (cols=64) FATAL on spike with
   the same condition VCS hits. Env gates retained: `GEMMINI_MVIN_TRACE` (dump fields),
   `GEMMINI_RTL_ASSERTS_OFF` (run illegal kernels for tracing). Rebuild:
   `make -C generators/gemmini/software/libgemmini install`.
   (`MVIN_COLS_BITS=6` is config-specific — re-derive from the generated RTL `rs2[hi:32]` slice width
   for other Gemmini configs.) Vindicates the user's instinct to validate winners on VCS.

VCS reproduction recipe (per winner): splice `best_candidate_so_far.c`'s `solution()` body into the
matching fork baseline `.c` (preamble through `gemmini_flush(0)` + winner body + baseline
correctness-check tail), `.replace("gemmini_poll_until_ready();","")` (the LLM hallucinates that
intrinsic in the non-spike branch; fp8-128 baseline happens to define it as a static-inline, fp8-256
does not), register in `bareMetalC/Makefile`, build non-spike with `RUNNER=vcs` (so `-DSPIKE_SIM` is
NOT set) → `make -C sims/vcs run-binary CONFIG=RadianceGemminiOnlyConfig LOADMEM=1 BINARY=…` (~5 min).
Reconstructed kernels: `gemmini-rocc-tests-ref/bareMetalC/matmul_tiled_fp8_128x128{,x256}_WINNER.c`.

## 4d. RTL-LEGAL performance wins (2026-06-06) — real speedups that pass VCS

With the faithful spike (§4c), I re-derived legal optimizations of all 5 fork matmul baselines.
Transform = single-port **batched/block mvin at batch=2** (`cols = 2*DIM = 32`, fits the 6-bit field;
batch=2 also divides the 8/16/4 tile counts cleanly — beats batch=3 which leaves remainders), with
the **standard B layout** `b_base+(k*tiles_J+j)*DIM`. For fp6/fp4 the baselines also fence after
EVERY mvin; removing those (one fence before compute) is most of their win.

| kernel          | baseline | optimized | speedup | VCS RTL |
|-----------------|---------:|----------:|--------:|---------|
| fp8 64×64×64    |      294 |       146 |  2.01×  | PASS ✓  |
| fp8 128×128×128 |      853 |       525 |  1.62×  | PASS ✓  |
| fp8 128×128×256 |     1607 |       902 |  1.78×  | PASS ✓  |
| fp6 128×128×512 |     1939 |       721 |  2.69×  | PASS ✓  |
| fp4 128×128×512 |     1658 |       695 |  2.39×  | PASS ✓  |

(spike read_cycles; VCS = correctness on RadianceGemminiOnlyConfig RTL.) These are HONEST,
RTL-legal — contrast the prior spike-only "winners" (fp8 3.6–3.9×) that fatal on VCS. Kernels +
README + measure scripts: `autocomp/results/fork-legal-kernels/`, `autocomp/measure_*.py`,
`gemmini-rocc-tests-ref/bareMetalC/*_LEGAL.c`.

**Conv & attention (2026-06-06):** legality side — re-running the prior conv and attn-fp6 autocomp
winners through the FAITHFUL spike now REJECTS them (`correct=False`): they used the same illegal
`cols=64` batched mvin. (Flash winners still pass — being CPU-softmax-bound, the LLM never batched mvin
there.) Baselines are RTL-legal (cols=16, standard B layout). Legal batch-2 mvin alone buys ~nothing
(conv ~98% CPU im2col, attention ~97% CPU softmax — the accelerator is a tiny fraction), so the matmul
wins (1.6–2.7×) don't transfer via mvin batching. BUT the CPU hot code IS optimizable by hand
(autocomp can't — it only optimizes accelerator ISA), and doing so gives real wins, baked into the
generators (`conv_gen.py`/`attention_gen.py`):
- **conv 8213→5920 = 1.39×**: im2col inner `dx` span is contiguous in both A_buf and X_in (stride==
  kernel) → one `memcpy(CONV_KSZ)` instead of CONV_KSZ scalar stores (the dominant lever) + batch-2 mvin.
- **attention 824009→620635 = 1.33×**: softmax requant rewritten — unpack 4 bf16/word (kills per-element
  `j/4`,`j%4`,shift), drop redundant `f_abs` (exp≥0), fold the normalize pass into the per-group encode
  scale (numerically identical) + batch-2 mvin. NOTE: naive pass-fusion (merging gmax into the exp loop)
  REGRESSED to 0.96× — the cost is per-element soft-float (`exp_f`/`fp8_e4m3_rtz` ≈ 50 cyc/elem), not
  pass count; the 4-lane unpack is what won. Further gains need cheaper exp/encode (fixed-point) or HW
  softmax (§4e). NOTE: conv/attn kernels are spike-only (mx_read_smem readout, no `#ifndef SPIKE_SIM`
  path) → not VCS-executable as-is; their batch-2+standard-layout legality rests on the matmul VCS
  validation (identical pattern); the CPU edits don't touch the accelerator. Measure/validate scripts:
  `autocomp/measure_conv_opt.py`,`measure_attn_opt2.py`,`validate_conv_attn.py`,`recheck_conv_attn.py`.

**ROOT CAUSE of the conv/attn CPU-boundedness = HARDWARE-CONFIG GAP, not kernels (2026-06-06).**
Gemmini DOES support hardware conv (`tiled_conv_auto`/`loop_conv_ws`) and hardware softmax/layernorm
(`SOFTMAX`/`LAYERNORM` activation via the `Normalizer`) — the fork's `transformers/transformer.c`
(softmax activation) and `bareMetalC/conv.c` (hardware im2col) use exactly these. BUT this project's MX
config (`GemminiMxFPConfigs.defaultMxFPConfig`, ConfigsFP.scala:217-346) is elaborated MATMUL-ONLY:
`has_normalizations=false`, `has_nonlinear_activations=false`, `has_max_pool=false`. PROOF: running the
fork `conv-baremetal` on this MX spike → all-zeros, `*** FAILED ***`; `tiled_matmul_ws_softmax` → no
output. ARCHITECTURE: MX matmul output routes through a dedicated **`MxRequantizer`** (MxRequantizer.scala),
NOT the standard `AccumulatorScale`+`Normalizer` path; softmax is gated by
`has_nonlinear_activations && has_normalizations` in AccumulatorScale.scala:76/85/133/142 (both false here)
AND lives on the non-MX path. `LoopConv` IS elaborated, but its output isn't wired to the MxRequantizer
(so MX conv via the hw loop produces garbage). spike's `mx_loop_ws_spad` likewise applies NO softmax/norm.
=> Accelerator conv/attention for MX is a HARDWARE-FEATURE INTEGRATION (RTL: enable the flags + route MX
output through the normalizer / add softmax to MxRequantizer + wire LoopConv→MxRequantizer; then spike +
ISA + kernels + VCS), not a kernel-tuning task. The CPU im2col/softmax in our generators is a *workaround*
for the missing hardware, and autocomp (accelerator-code optimizer) cannot remove it. This corrects the
earlier "no accelerator headroom" note: the headroom EXISTS in Gemmini but is DISABLED in this MX build.

**SECOND spike↔RTL gap found (documented, not yet fixed):** the fp8-128 autocomp winner used a
*transposed* B scratchpad layout `(j*tiles_K+k)`; it is correct on spike but **produces WRONG values
on VCS** (no fatal) — i.e. spike's `mx_loop_ws_spad` tolerates a B-slot layout the RTL does not. The
faithful-spike mvin fix does NOT catch this (it's a compute-path, not a load-field, divergence). The
LEGAL kernels sidestep it by using the VCS-validated standard layout. FOLLOW-UP: make spike's
loop_ws_spad enforce the RTL B-addressing so a search can't produce this class of RTL-wrong kernel.
Until then, ANY search winner must be VCS-validated before trust.

## 5. ⚠️ Metrics caveat

Earlier logs show fp4 attention "323661×" / fp4 flash "682620×" — **bogus** (degenerate kernels gaming
the all-zero gold from the now-fixed zero-collapse bug). fp4 is too coarse for attention activations
(e2m1 = 8 levels). **Use fp8/fp6 for attention; fp4 is a matmul/weights format.**

## 6. smolvla grounding

Real GEMM shapes from `model2MLIR/workloads/smolvla/smolvla.mlir` (94 `linalg.matmul`): 1024×768×768
(60×, dominant), 1024×768×3072 (MLP), 128×320×320 (16×), 50×720×720. **All exceed the scratchpad → all
use the tiled path** → all covered by the accelerator-centric rewrite. The big GEMMs are too slow to
*search* on the functional sim (~15 min/eval) but the rewrite applies directly (1024×768×768
golden-checked).

## 7. Cost

$63.7 of $100 (Sonnet 4.6): $32.55 setup/exploration + $14.06 + $17.05 across three search rounds.

## 8. Remaining levers (all low-ROI)

- Attention softmax: ~1.08× ceiling, HW-bound — not worth it.
- fp4 attention: correct (nonzero) but numerically coarse — likely not useful.
- Tiled output is now bf16 block-row-major (was fp32) — downstream must expect bf16.

## Key files

- Generators: `autocomp/backend/gemmini/mx_pipeline/{matmul_gen,attention_gen,flash_attention_gen,conv_gen,profile}.py`
- Pipeline driver: `autocomp/search/run_mx_pipeline.py`
- Eval + cost: `autocomp/backend/gemmini/gemmini_eval.py` (`mx_cycle_phase_hint`), `autocomp/common/cost.py`
- Prototypes: `autocomp/backend/gemmini/mx_pipeline/_*_prototype.py`
- Full project memory: `~/.claude/.../memory/mx-gemmini-setup.md`

## 6. Host has NO FPU → float-free integer attention (2026-06-08, plan lucky-twirling-riddle)

DECISIVE: the host that runs the kernels in `RadianceGemminiOnlyConfig` is a Rocket **small core with
`fpu=None`** (no FPU), no RVV, scalar (rocket-chip `WithNSmallCores`); the Muon SIMT GPU is **disabled**
(`RadianceConfigs.scala:295`). Kernels build `-march=rv64gc` (hard float). **Proven on VCS (Phase 0):**
`bareMetalC/fpu_probe.c` (`a*b+c`) **TRAPS** before its printf (`*** FAILED *** exit 1337`);
`int_probe.c` PASSES (`result=22`). Same binaries run on spike (which HAS an FPU). ⇒ the attention/flash
float softmax (`exp_f`/`fp8_e4m3_rtz`/`pow2i`) is **not HW-deployable** (traps), and spike's cycle count
for float is unachievable on the chip. matmul/conv host code is integer ⇒ deployable (matmuls VCS-valid).

**Therefore the optimal AND only-deployable attention is a FLOAT-FREE integer softmax.** Implemented for
fp8 (Phase 1, `attention_gen.py`): Gemmini's own I-BERT integer exp (`gemmini.cc:2293 apply_iexp`) ported
to the host — bf16→Q(1/1024) integer decode, `mx_iexp`, integer max/sum, per-row **rounded** reciprocal
(no per-elem divide), e8m0 group scale (self-contained branchless `mx_ilog2` — `__builtin_clzll`→`__clzdi2`
is undefined under baremetal `-nostdlib`), integer fp8-e4m3 **RNE** encode. Gotcha: raw RTZ + floored
reciprocal gave only 7.8% vs fp32 (real QK scores are huge ~±6000 ⇒ near-hard-attention; floored recip put
`p/sum=1.0` just under 2²⁴ → `ilog2` off-by-one → 2× error); **rounded reciprocal + RNE → 99.8% within-5%
of true fp32, beating the float kernel (73%)**. Gate: `solution`+all `mx_*` disassemble to **0 float
instrs**. The integer softmax **runs on the no-FPU RTL** (VCS trace: integer ops, no trap — vs fpu_probe).

Phase 6 (informed search, staged not run): `gemmini_eval.py` `MX_NOFPU_COST=1` +`_count_host_float_ops`
penalizes host float (default penalty 1e8/occurrence = disqualifying, since float traps) so the optimizer
prefers the float-free kernel; seed = the integer softmax (baked baseline). Invocation + gate in
`autocomp/results/int-softmax/INFORMED_SEARCH.md`. REMAINING: flash (online integer softmax, incl integer
`O_acc`), fp6/fp4 integer-P encode, full standalone attention VCS harness. Probes: `gemmini-rocc-tests-ref/
bareMetalC/{fpu,int,int_softmax}_probe.c`.

**Phase 3 (flash) DONE + Phase 4 assessed (2026-06-08).** Flash online softmax fully integer-ized
(`flash_attention_gen.py`): integer running max `Mq`, denom `Lq`, fixed-point `O_acc` (Q16); `corr`/
rescale/encode all integer (`mx_enc_unnorm`). Validated 92.3% within-5% vs true fp32 (float flash ~94%);
spike self-consistent; kernel path 0 float instrs. Phase 4: **fp6/fp4 matmul (fitting+tiled) and conv are
ALREADY float-free** (verified — the tiled `C_acc` float is dead; output is bf16 `C_hw` via
`mx_read_smem`), so they + fp8 attention + fp8 flash are all **deployable**. **Deployability scorecard:**
✅ all matmul (fp8/6/4, incl. smolvla tiled GEMMs), ✅ all conv, ✅ fp8 attention, ✅ fp8 flash — all
float-free/HW-deployable. ❌ remaining: only **fp6/fp4 attention + flash** softmax (float dynamic-P quant;
reuse `mx_iexp` + integer fp6-LUT/fp4-e2m1 encoders; low value — fp4 attention is inherently coarse).
Plan phases complete: 0,1,2,3,5,6 + Phase-4 matmul/conv.

**fp6/fp4 attention + flash NOW WORK (float-free), 2026-06-08.** The last non-deployable kernels are
done: reused `mx_iexp` + added integer fp6 nearest-LUT-index (`MXTH6`, 15 midpoint thresholds) and fp4
e2m1 (`MXTH4`, 7 thresholds) encoders. Attention fp64 = normalized-P reciprocal + group-scale offset
(fp6 +2 / fp4 0); flash fp64 = online integer `Mq`/`Lq`/`O_acc` + unnormalized `mx_enc_P` (fp6 target 0
/ fp4 target 2). Validated in Python (int↔float agree 91–92%; int-vs-true matches float-vs-true — the
~35–40% is the coarse fp6/fp4 ceiling, not a bug). All compile/run/correct on spike; kernel paths = 0
float instrs. **FINAL deployability scorecard: EVERY MX kernel — matmul (fitting+tiled), conv, attention,
flash × {fp8,fp6,fp4} — is now float-free and HW-deployable on the no-FPU host.** The earlier `❌`
entries are cleared.

## 7. FULL numeric validation found+fixed a critical bug (2026-06-08)

Validating the *actual C kernel output* vs an independent fp32 reference (not just the Python algorithm
port + self-gold) revealed the integer attention/flash kernels were producing **garbage** (fp8 attention
4% within-5%, corr 0.02) despite passing self-consistent gold and the Python-port check at 99.8%.

ROOT CAUSE: `mx_iexp`'s final `qe >> z` is **undefined behavior when z ≥ 64** — C/RISC-V mask the shift
amount to 6 bits, so `qe >> 2282` becomes `qe >> (2282 & 63)` = nonzero garbage instead of 0. Python's
arbitrary-precision shift gives the correct 0, so the Python port passed; and the spike↔VCS checksum
matched because *both* mask identically (both wrong). On hard-attention scores (±6000, S_q=1/1024) the
far-from-max args make z≈2282 → garbage softmax weights → wrong O. FIX: `if (z >= 63) return 0;` in every
`mx_iexp` (qe < 2^22, so z≥22 is already 0; the guard avoids the UB). Applied to all 4 generator copies +
the int_softmax_probe.

FULL VALIDATION (actual C output vs true fp32 `softmax(QK_C_out_bf16)@dequant(V)`, verify_attn_full.py):
attention fp8 **95.7%/corr 0.999**, fp6 91.7%/0.949, fp4 92.7%/0.994; flash fp8 76.9%(98% w/in-20%)/0.933,
fp6 53.3%(94%)/0.928, fp4 75.5%(98%)/0.928. All numerically correct (corr 0.93–0.999). Spike==VCS is
bit-exact (proven earlier), so spike numeric validation == RTL. CORRECTION: the earlier "fp8 attention
99.8%" was the Python port; the validated **actual C** figure is 95.7% (the kernel was broken until this
fix). LESSON: validate the real kernel output against an independent reference — Python-port + self-gold
+ spike==VCS all passed while the kernel was wrong.

## 8. Spike loop_ws dual-port fidelity fixed (task #38, 2026-06-08)

Root-caused the 2nd spike↔RTL gap: spike's `mvin`/`mvin2`/`mvin3` all write to ONE unified
`gemmini_state.spad` (the port/`state_id` only selects strides), but the elaborated RTL routes
`mvin2`/`mvin3` (port≠0) to a distinct physical scratchpad region that the `loop_ws` A/B read ports
(`a_spad_id`/`b_spad_id`, =0 in the MX path) do not address. So a `loop_ws` input loaded via port≠0 is
**spike-correct but RTL-wrong** — exactly the fp8-128 autocomp "winner" (B via `mvin2`): passed spike,
mismatched on VCS. FIX (`gemmini.h`/`gemmini.cc`): `loop_input_alt_port` flag set in `mvin` when
`state_id!=0`, cleared on `flush`/`reset`; `mx_loop_ws_spad` rejects it (`exit(1)`, env-gated
`GEMMINI_RTL_ASSERTS_OFF`). Verified: all single-port kernels (matmul LEGAL/baseline, integer
attention/flash) still PASS (no false-positive); the `mvin2`-B winner is now `correct=False`. Net: the
"dual-port `mvin2` overlap" transform — a spike-only mirage for `loop_ws` inputs — is removed from the
viable search space, so an informed search can't produce a spike-correct/RTL-wrong dual-port kernel.

## 9. Full fp8 attention proven on VCS (end-to-end, no-FPU RTL)
The standalone float-free fp8 attention (`gemmini-rocc-tests-ref/bareMetalC/attn_fp8_vcs.c`:
integer I-BERT softmax + fp8 requant + QK/PV MX matmuls) now passes on `RadianceGemminiOnlyConfig`
**bit-exact**: VCS checksum `0x06b6e0bd26337b04` == spike read_smem gold. First time a float-free
attention is confirmed on real no-FPU RTL.

**Prior divergence (corr 0.009, 8/64 nonzero cols) was a readout bug, NOT compute.** The VCS mvout
readout used `gemmini_config_st(S_OUT_COLS*sizeof(out_t))`=256 (QK) / `(D_OUT_COLS*sizeof(out_t))`=128
(PV); the validated `matmul_tiled_fp8_128x128_LEGAL2.c` uses `gemmini_config_st(DIM*sizeof(elem_t))`=16.
The 16x-too-large store stride scatters the mvout rows so only ~8/64 land. `config_st` only affects the
VCS `mvout` path — spike reads via `gemmini_mx_read_smem` (stride-agnostic) — which is exactly why it was
spike-correct / VCS-garbage. Fix: both `config_st` -> `DIM*sizeof(elem_t)`; QK readout is now
byte-identical to validated LEGAL2 (same 128x128 shape, flat loop, `0x38`).

Spike could NOT cross-check this: spike's MX `mvout` readout throws `std::out_of_range` (the smem-readout
path isn't modeled for mvout). Logged in SPIKE_MODEL_CHANGES.md as a known gap. The integer softmax and
MX matmuls are bit-exact spike==VCS (int_softmax_probe, LEGAL matmuls), so the readout was the only gap.

## 10. Autocomp re-run on the corrected pipeline (2026-06-09)
Re-ran the informed search with the corrected, RTL-faithful pipeline (faithful spike: cols/rows 6-bit
truncation + dual-port loop_ws guard + flush mx_smem clear; MX_NOFPU_COST=1 host-float penalty). Any
winner that scores is RTL-legal by construction (spike rejects illegal cols=64 / mvin2-B / etc.), so
this is also an end-to-end guardrail validation.
- gemmini-mx-attention (host integer softmax, the RTL-deployable kernel): 762147 -> 672193 = **1.13x**,
  $1.41, 23 LLM calls, 2 iters. Legal accelerator-side tuning (mvin batching / fence / loop). Correct on
  faithful spike.
- flash / attn-fp4 / attn-fp6 variants: search running (5 problems).
Modest, as expected: the host integer softmax is a large fixed fraction autocomp can't touch (that is
what the HW-softmax work targets); the accelerator QK/PV levers were already near-optimal.
