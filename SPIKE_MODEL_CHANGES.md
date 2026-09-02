# Spike functional-model changes (MX-Gemmini bring-up)

Running changelog of every modification to the Gemmini **spike functional model**, kept
PR-ready. All changes live in the `libgemmini` submodule:

- `generators/gemmini/software/libgemmini/gemmini.cc`
- `generators/gemmini/software/libgemmini/gemmini.h`

Submodule baseline (HEAD these diffs apply on top of): `cedc820 update readme`.
For a PR these go to the **libgemmini repo** as their own PR, then the gemmini → libgemmini
submodule pointer is bumped.

**Theme.** Stock spike is a *permissive* ISA functional model: it silently accepts encodings
the elaborated `RadianceGemminiOnlyConfig` RTL rejects (`$fatal`) or computes differently.
That lets autocomp "winners" pass on spike and then fatal / produce wrong results on VCS.
These changes make spike **faithful to the elaborated RTL** for the constructs the MX search
exercises, so spike legality ⇒ RTL legality. Every guard is gated by env vars so an illegal
kernel can still be traced end-to-end.

**Env toggles (all changes):**
- `GEMMINI_RTL_ASSERTS_OFF` — disable the `exit(1)` legality guards (still prints the
  diagnostic); lets an illegal kernel run to completion for tracing. Default: guards ON.
- `GEMMINI_MVIN_TRACE` — dump decoded mvin fields (state/cols/rows/stride/blocks/addr) per call.

> ⚠️ **Config-specific constants.** The `6`-bit field widths below are read from the
> *elaborated* `RadianceGemminiOnlyConfig` RTL. They are **not** universal — re-derive from the
> generated `LoadController.sv` / `StoreController.sv` `rs2[hi:lo]` slice widths for any other
> Gemmini config before reusing this model. `gemmini_params.h` `MAX_BYTES=64` is *inconsistent*
> with the 6-bit field (it implies cols up to 64); the hardware field width is authoritative.

---

## 1. mvin: model 6-bit `num_cols` / `num_rows` field truncation + legality guards
`gemmini.cc` — `gemmini_t::mvin()`

**What:** Decode `cols`/`rows` as `raw & ((1<<6)-1)` instead of the full 16-bit field, then
guard.
- RTL reads `cols = rs2[37:32]` and `rows = rs2[53:48]` — both **6-bit** fields. The mvin
  macros pack a full 16-bit `cols<<ADDR_LEN` / `rows<<(ADDR_LEN+16)`, so any value > 63 is
  **silently truncated** by hardware. Modeled here so spike diverges exactly as the RTL does.
- **Root cause of the original "winner fatal":** `MAX_BLOCK_LEN=4` → `cols=64=2^6` →
  `rs2[37:32] = 64 & 0x3F = 0` → 0 bytes → `$fatal` at `LoadController.scala:191`.

**Guards added (each prints a diagnostic; `exit(1)` unless `GEMMINI_RTL_ASSERTS_OFF`):**
- **cols/rows field overflow** (`cols_raw != cols`, `rows_raw != rows`): the value didn't fit
  the 6-bit field. Tells the user the legal envelope (cols ≤ 63 ⇒ max block batch 3).
- **zero-byte mvin** (`rows==0 || cols==0`): mirrors `LoadController.scala:191` ("a single
  mvin must load > 0 bytes"); uses the *truncated* cols so cols=64→0 trips it like VCS.
- **block-stride underflow** (`cols>DIM && load_block_stride < rows`): mirrors
  `LoadController.scala:138` `assert(block_stride >= rows)`. *Predicate still being calibrated
  against VCS (task #35)* — refine if VCS shows a wider/narrower envelope.

**Trace:** `GEMMINI_MVIN_TRACE` prints the decoded fields per call (used to calibrate the
envelope against VCS).

## 2. mvout: model 6-bit `num_cols` / `num_rows` field truncation + guard
`gemmini.cc` — `gemmini_t::mvout()`

**What:** Same 6-bit truncation (`cols = rs2[37:32]`, `rows = rs2[53:48]` per
`StoreController.sv`) + a single overflow guard (`exit(1)` unless `GEMMINI_RTL_ASSERTS_OFF`).
All current MX kernels mvout `cols=rows=DIM(16)` (safe); this guards future block-mvout search
transforms from silently truncating.

## 3. loop_ws dual-port input guard (task #38)
`gemmini.cc` — `gemmini_t::mvin()`, `gemmini_t::mx_loop_ws_spad()`; `gemmini.h` — new state field

**What:** Reject a `loop_ws` input that was loaded via a non-zero mvin port (mvin2/mvin3).
- **RTL behavior:** mvin2/mvin3 (port≠0) route to a distinct physical scratchpad region; the
  `loop_ws` A/B read ports (`a_spad_id`/`b_spad_id`, both 0 in the MX path) don't address it.
  So a loop_ws input via port≠0 reads stale/wrong data on RTL — **wrong result**, while spike's
  unified port-agnostic scratchpad reads it fine. This is exactly the fp8-128 "dual-port-B"
  autocomp winner: spike-correct, VCS-wrong.
- **Implementation:**
  - `gemmini.h`: added `bool loop_input_alt_port;` to `gemmini_state_t`.
  - `mvin()`: `if (state_id != 0) loop_input_alt_port = true;`
  - `mx_loop_ws_spad()` entry: if `loop_input_alt_port`, print diagnostic and `exit(1)` unless
    `GEMMINI_RTL_ASSERTS_OFF`.
  - Reset in `reset()` and in `flush_funct` (see #4) so it's per-matmul.

## 4. flush clears MX shared memory (correctness fix, not a guard)
`gemmini.cc` — `flush_funct` branch of `CUSTOMFN(XCUSTOM_ACC)`

**What:** `flush` now `std::fill`s `mx_smem` to 0 (and clears `loop_input_alt_port`).
**Why:** MX shared memory accumulates (`bf16_accum_add`). Software-tiled matmuls issue one
`loop_ws_spad` per output block; without clearing on flush, each block piles onto the previous
block's smem residue → wrong tiled results. `reset()` clears it but runs only once at startup.
This is a genuine functional-model **bug fix** (independent of the RTL-fidelity guards).

## 5. `gemmini.h` state field
Added `bool loop_input_alt_port;` to `struct gemmini_state_t` (supports #3).

---

## Diffstat
`gemmini.cc | 137 +++++-`, `gemmini.h | 1 +` (vs submodule HEAD `cedc820`).

## Verification done
- Port-0 kernels (matmul LEGAL/baseline, integer attention) still PASS — no false positives.
- cols=64 mvin now fatals on spike exactly as on VCS (`LoadController.scala:191`).
- fp8-128 dual-port-B winner now `correct=False` on spike, matching VCS.
- Tiled matmul correct after the flush/`mx_smem` clear (#4).

## Known spike-model gaps NOT yet addressed (found, for the PR)
- **MX `mvout` readout throws `std::out_of_range`** (vector idx 1024 ≥ size 1024) when used to read
  a `loop_ws_spad` MX result out of `mx_smem` (the RTL-deployable readout path). Spike's MX output is
  read via `gemmini_mx_read_smem`; the `gemmini_mvout` path is not modeled for MX smem, so spike
  **cannot cross-check the VCS mvout readout** — it must be validated directly on VCS. Worth fixing so
  spike can validate VCS-deployable kernels (would have caught the attn `config_st` readout bug on spike
  instead of after a VCS run). Not a regression from these changes — pre-existing.

## Open / caveats for the PR
- #2 block-mvin stride predicate (`LoadController.scala:138`) is **calibrated against limited
  VCS data** — note as "best-effort, refine against more VCS runs."
- The `6`-bit constants are `RadianceGemminiOnlyConfig`-specific (see warning above).
- Consider promoting the env-gated `exit(1)` guards to a build-time flag for upstream, so the
  generic spike stays permissive by default and the RTL-faithful mode is opt-in.

## Faithful-cycle model (opt-in via `GEMMINI_FAITHFUL_CYCLES=1`) — 2026-06-11
**Problem:** base spike retires every accelerator op in ~1 instruction (CPI=1, no latency), so
`read_cycles` ≈ host instruction count and UNDERCOUNTS real RTL by ~20–40× for accel-bound kernels.
This made autocomp chase instruction-count tricks (mvin batching, fence removal) that don't help silicon
(VCS-proven: batch-2 mvin = 1.78–2.39× on spike → 1.0–1.56× on RTL).
**Change (`gemmini.cc`):** a DATA-driven latency that bumps the `mcycle` CSR (`p->get_state()->mcycle->bump`)
— cycle-only, NEVER affects functional correctness. Two hooks, gated by `gmx_faithful_cycles()` (env):
- in `mvin`: `bump(K_DMA · cols·rows)` — summed over all mvins this is total load bytes **independent of
  batching** (batch-1 and batch-2 cost the same → the instruction-count "win" vanishes).
- in `mx_loop_ws_spad`: `bump(K_CMP · MACs/peak)` where MACs/peak = `TI·TJ·TK·16` (fp8) or `·32` (sub-byte),
  placed before the early returns so it counts on every datapath.
- Coefficients `GMX_K_DMA_MILLI=143` (0.143 cyc/byte), `GMX_K_CMP_MILLI=1804` (1.804 cyc per MAC/peak),
  fit to VCS cycle data (fp8-64/256, fp4/fp6-512).
**Validation:** spike-faithful vs RTL — fp8-64 +9%, fp8-256 +3%, fp4-512 +9%, fp6-512 −23% (LUT overhead
unmodeled). Mirage killed: batch-1 37,617 vs batch-2 37,515 (0.3%) vs base-spike fake 1.13×.
**Not yet modeled:** fp6 LUT-decode overhead; host CPI≈2 (attention is host-bound, RTL 2.05× spike — the
real Rocket runs the integer softmax at ~CPI 2 vs spike's CPI 1); mvout/readout latency (minor).
**Recalibrate** `K_DMA`/`K_CMP` from a VCS shape-suite whenever the RTL changes (see automation below).
