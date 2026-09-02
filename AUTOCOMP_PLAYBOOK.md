# Autocomp Playbook — MX-Gemmini kernel search

Single source of truth for how the MX-Gemmini autocomp search works, where its files live, the tuning
decisions and *why*, how to run it, and the open HW track. Companion docs:
`MX_AUTOCOMP_STATUS.md` (results log), `SPIKE_MODEL_CHANGES.md` (faithful-spike RTL-fidelity guards),
`HW_SOFTMAX_STATUS.md` (HW-softmax / Normalizer blocker).

Repos: autocomp = `/scratch/agustin/projects/autocomp`; chipyard-mx = `/scratch/agustin/projects/chipyard-mx`;
hand-tuned/VCS kernels = `/scratch/agustin/projects/gemmini-rocc-tests-ref/bareMetalC`.

---

## 1. Pipeline map (how a run flows)
- **Entrypoint:** `autocomp/search/run_mx_pipeline.py`. `PROBLEMS` (28) enumerate (family, prob_type,
  prob_id, generator); `--only <substr,...>` filters. `main()` generates each problem then calls
  `optimize_problem()`.
- **Problem → baseline kernel:** generators in `autocomp/backend/gemmini/mx_pipeline/*_gen.py`
  (`matmul_gen`, `attention_gen`, `flash_attention_gen`, `conv_gen`) + `fork_problem_gen.py` (wraps the
  Rakanic fork's hand-written LEGAL kernels). They capture a spike/HW gold and write the baseline to
  `autocomp/sols/<prob_type>/sol<id>_exo_baseline.c` (+ harness `test<id>.c`). Fork problems' baseline IS
  the hand-tuned kernel's compute region.
- **Seeding:** `search.py::load_initial_code` reads `sol<id>_exo_baseline.c` as the single iteration-0
  candidate. **(NEW)** `optimize_problem` also injects `extra_seed_codes` — the most-recent prior winner
  (`_prior_winner_seeds`) — as additional iter-0 candidates so a re-run builds on the best-known kernel.
- **Search:** `BeamSearchStrategy.optimize(iterations)` — per iter: propose plans (`--num-plans`) →
  implement → eval → keep top `--beam`. `--iterations` = rounds. Score = latency cycles (lower better).
- **Eval:** `gemmini_eval.py::evaluate_code` runs each candidate on spike (`run_spike`, per-candidate
  `timeout=120s`), parses "Correct" + "Generated implementation latency: N cycles". `MX_NOFPU_COST=1`
  adds `MX_FLOAT_PENALTY` (default 1e8) × host-float-op count → disqualifies non-deployable (float)
  kernels (no-FPU host). Faithful spike (libgemmini guards) rejects RTL-illegal encodings.
- **Agent:** `built:gemmini-mx` under `autocomp/agent_builder/.built/gemmini-mx/` (sources:
  `autocomp/agent_sources/gemmini-mx/`). `optimization_menu.yaml` = the strategy menu shown to the LLM
  (reloaded each run); rebuild via `python -m autocomp.agent_builder.run_agent_builder --agent-name
  gemmini-mx --source-dir agent_sources/gemmini-mx --rerun optimization_menu`.
- **Cost:** lifetime tally `chipyard-mx/.autocomp-spend_total.json` (`AUTOCOMP_SPEND_LOG`); per-run
  `output/.../cost_live.json` + `cost_ledger.jsonl`; gates: `--reserve-usd` (skip new problem near cap),
  `AUTOCOMP_SPEND_LIMIT_USD`. No Bedrock-specific throttle (generic backoff, 8 retries).

## 2. Produced-file locations
- Baselines: `autocomp/sols/<prob_type>/sol<id>_exo_baseline.c`; harnesses `.../harnesses/<prob_type>/test<id>.c`.
- Per-run outputs: `autocomp/output/mxpipe_<prob_type>_<id>_iters<N>/` →
  `candidates-iter-*/`, `eval-results-iter-*/`, `best_candidate_so_far.c`, `cost_live.json`, `cost_ledger.jsonl`.
- Hand-tuned seeds + VCS golds: `gemmini-rocc-tests-ref/bareMetalC/{*LEGAL*.c, attn_fp8_vcs.c, attn_fp8_hwsm.c}`.
- wandb: project `autocomp` (run links printed at start).

## 3. Search-tuning decisions (what was changed, and why)
Set up 2026-06-09 to fix why the naive search under-performed. Each is in the code now:
1. **Informative eval feedback** (`gemmini_eval.py`): failed/illegal/timed-out candidates used to be a
   silent `score=inf`. Now the spike diagnostic — the faithful-spike guard messages ("GEMMINI MVIN
   ILLEGAL: cols=64 → truncated, reduce batch so cols≤48", "LOOP_WS ILLEGAL … use single-port mvin"),
   compiler errors (recovered; were discarded at `run_spike`), and a timeout hint — is captured into
   `stats["stdout"]` (`_eval_diag`) and fed to the LLM via `reimplement_failed` (now ON). Turns dead-ends
   into a climbable gradient.
2. **Fixed + enriched the agent menu** (`.built/gemmini-mx/optimization_menu.yaml` + `agent_sources/.../
   MX_PROGRAMMING_MODEL.md`): the menu was *recommending* `mvin2`/`mvin3` for loop_ws A/B — the dual-port
   transform proven RTL-ILLEGAL (#38). Replaced with an explicit DON'T, and added the verified win-list:
   batch-2 `block_mvin` (cols≤48, never 64 — 6-bit field), raise `lut_update_granularity` (fp6), e4
   P-target (MX_P_TARGET_LOG2=4, not fp8-max) for attention P-encode.
3. **Multi-seed / seed-from-winners** (`search.py` `extra_seed_codes`; `run_mx_pipeline._prior_winner_seeds`):
   a re-run injects the prior `best_candidate_so_far.c` as an extra iter-0 candidate → optimizes on top of
   the 1.13× attention / batch-2 matmuls instead of rediscovering them.
4. **Per-phase models** (`--plan-model` / `--code-model`): plan-gen and implement can use different models
   (e.g. Haiku plan + Sonnet implement) to cut cost; the daily-token ceiling was the real constraint.
5. **`--dropout-menu`** exposed (was hardcoded 0.25): lower it to keep the curated menu fully in-context.
6. **Two-phase eval:** intentionally NOT added — Gemmini spike eval is sub-second and rarely hits the
   120s timeout (unlike Muon's timed runs), so a correctness-gate split buys little here. Revisit only if
   timeouts become common.

**Honest ceiling:** the host (no-FPU Rocket) parts are fixed; autocomp can only improve accelerator-side
LEGAL transforms. Matmul is already near-optimal; prior run got attention 1.13×, flash/fp4/fp6 flat
(host-softmax-bound — that bottleneck is what the HW-softmax track targets, §6).

**RTL-legal ledger (what wins must respect):** single-port mvin for loop_ws; block-mvin batch≤3 (cols≤48,
never 64); fence removal between independent loads OK; raise lut_update_granularity (fp6); attention
P-encode target ~2^4. Faithful spike auto-rejects violations (and now tells the LLM why).

**Per-winner validation gate:** faithful spike `Correct` → `verify_truth` fp32 within-tolerance → VCS on
the 1–2 best (match spike-captured gold).

## 4. Run recipe
Focused seeded run (post-setup), no-FPU honest cost, seeds auto-include prior winners:
```
cd /scratch/agustin/projects/autocomp && source /scratch/agustin/projects/chipyard-mx/autocomp-env.sh
MX_NOFPU_COST=1 python -m autocomp.search.run_mx_pipeline \
    --only attention,matmul --iterations 8 --num-plans 3 --beam 3 \
    --plan-model aws::us.anthropic.claude-sonnet-4-6 --code-model "" \
    --dropout-menu 0.1 --reserve-usd 10
```
(`--plan-model aws::us.anthropic.claude-haiku-4-5-20251001` to cut plan cost once that id is confirmed.)
Dry run (no LLM spend): add `--generate-only`.

## 5. Run / spend history
- Lifetime spend tracked in `.autocomp-spend_total.json`. As of 2026-06-09: ~$74.75 (June 3–5 fork
  searches ~$70; June 9 HW-softmax-era re-runs $4.39).
- Results: gemmini-mx-attention (fp8) **1.13×** (762147→672193); flash-attn / attn-fp4 / attn-fp6 **1.00×**
  (host-softmax-bound); matmul near-optimal (prior LEGAL batch-2 wins, VCS-validated).
- **Append each new run here** (date, command, problems, speedups, $) for future reasoning/debugging.

## 6. HW future track (scoped, NOT implemented — keep search honest vs current silicon)
Three HW levers could raise the kernel ceiling but each is a separate RTL project:
1. **HW softmax via the Normalizer** (biggest for attention): would offload the ~97% host softmax. The
   spike model + kernel are proven (corr 0.999, `attn_fp8_hwsm.c`), but enabling the RTL Normalizer needs
   a softmax-datapath redesign — it's integer-I-BERT vs the MX float acc (4 type/assert layers fixed;
   `exp_divider` still treats the sum as integer). Full writeup: `HW_SOFTMAX_STATUS.md`. RTL changes are
   staged behind an OFF config flag.
2. **Widen the mvin 6-bit `num_cols` field** → allows block-mvin batch>3 (cols>48), bigger batched loads.
   See the field analysis in `SPIKE_MODEL_CHANGES.md`.
3. **Fix dual-port `mvin2`/`mvin3` physical-region routing** → would make the currently-illegal A‖B
   overlap legal (a real speedup the search must avoid today). See #38 in `SPIKE_MODEL_CHANGES.md`.

## Hand-tuned results (2026-06-09) — 2-3x campaign across matmul/conv/attention
Measured on the autocomp spike read_cycles metric (whole solution(), host+accel); ledger: autocomp/hand_tune_ledger.jsonl.

- **matmul fp6-512: 2.08x (1939->931), VCS-PASSED.** batch-2 block_mvin on A+B + removed the baseline's
  per-mvin gemmini_fence() (it fenced after EVERY single-tile mvin -> serialized DMA). Correct on spike;
  the identical-recipe matmul_tiled_fp6_128x128x512_LEGAL kernel PASSED on RadianceGemminiOnly RTL (VCS,
  no mismatches). DEPLOYABLE TODAY. (matmul-tiled:1 batch-3 = 1.30x; fp4-512 same recipe applies.)
- **conv: NO software 2-3x (at im2col floor).** Baseline already batch-2 + memcpy im2col, no per-mvin
  fences. batch-3 = 0% gain (5920->5967, 15612->15612): the accelerator side is NOT the bottleneck; the
  ~1024 irreducible 8-byte im2col memcpys are. 2-3x requires a HW conv/im2col unit (has_max_pool=false,
  has_training_convs=false) -- a new RTL functional unit.
- **attention: NO software 2-3x (baseline is optimal).** Two EXACT (output-preserving) host-softmax
  hand-tunings BOTH regressed: (1) seed the encode's ilog2(R) from iexp's qe/z + refine -> 0.83x (linear
  refine iterates far for small R; the 6-branch binary log2 is already better); (2) fuse per-group max
  into the iexp pass -> 0.89x (per-element g=j>>5 + array store costs more than the deferred scan).
  Deployable host integer softmax = 762138 cycles is at its optimum. The genuine 2-3x+ is the HW SOFTMAX
  (full-accelerator version validated correct on spike: corr 0.9989, 0 NaN, ~198x on the spike metric --
  see HW_SOFTMAX_STATUS.md), which needs the RTL Normalizer (staged behind OFF config flags; one
  localized fix from elaborating: MxFloat .zero/.minimum need hardware-literal wrapping, Normalizer.scala:778-780).

**Net:** matmul 2-3x delivered + VCS-validated. conv & attention 2-3x are HARDWARE-gated (RTL paused per
user); software levers are exhausted (measured, not assumed). See [[no-rtl-changes-without-approval]].

## Hand-tuned results — round 2 (2026-06-10): propagate matmul recipe + deployable attention win
Two untested software levers on the CURRENT HW (no RTL). All spike-measured (autocomp metric), floats=0 (deployable).

**Lever 1 — propagate the batch-2 `block_mvin` recipe to every matmul format** (only fp6-512 was prior-validated).
Note: fp4/fp8 baselines ALREADY had no per-mvin fences, so only the batch-2 lever applies (not fence-removal).
- fp4-512: 1658 -> **695 = 2.39x** (best new matmul; **VCS-PASSED** on RadianceGemminiOnlyConfig, no mismatches)
- fp6-512: 1939 -> 931 = 2.08x (prior, VCS-PASSED)
- fp8-256: 1607 -> **903 = 1.78x**
- fp8-128: 853 -> **527 = 1.62x** (B-loop is for-j{for-k}; batch the inner k in both A and B)
- fp8-64:  294 -> **148 = 1.99x**
- **batch-3 REGRESSES** (fp4:908>695, fp8-256:1343>903) -> batch-2 is the universal sweet spot.

**Lever 2 — deployable attention softmax win (fused group-max), the FIRST host-softmax speedup that holds.**
Fuse the per-32-group max INTO the iexp pass using GROUP-NESTED loops (register-scalar gmax, reset per group),
eliminating the separate 16384-element gmax scan. Output is BIT-IDENTICAL (just reorders the scan).
- attn fp8: 762147 -> **710541 = 1.073x**
- attn fp6: 1230247 -> 1184893 = 1.038x
- attn fp4: 1229275 -> 1183137 = 1.039x
- WHY this works where the prior fuse attempt regressed (0.89x): the earlier try used per-element `g=j>>5` +
  `gmax_g[]` array store; the group-nested form keeps gmax in a register with no division/indexing, so it
  removes a whole p[] traversal at ~zero added per-element cost. **Fold into attention_gen.py / attn-fp6/fp4
  generators** so it becomes the new autocomp baseline.

**Honest ceiling reaffirmed:** coarser fixed-point precision gives ZERO cycle benefit (1-cycle int ops on the
in-order no-FPU Rocket regardless of width); only OP-COUNT reductions help. The big attention/conv 2-3x stays
RTL-gated. These are incremental deployable wins on top of the matmul 2-3x.

## Real-model shape coverage (2026-06-10): irregular-M, GEMV, causal attention
Audit of the 11 models in `../model2MLIR` (3,597 matmuls) found our generators covered only TOY shapes:
they REQUIRED M%16==0 (fp8) / M%32==0 (fp6/fp4), but **61.6% of real matmuls have M%16!=0** (M%16=1: 32.5%,
=2: 29.1%, =8: 18%, =9: 12.7%), 213 are GEMV (M=1), and attention is causal/multi-head. So nearly every
real matmul was ungeneratable. All fixes are SOFTWARE (no RTL).

**Key mechanism (verified):** the MX spike `mx_loop_ws_spad` honors `pad_I/pad_J/pad_K` (gemmini.cc:822-953,
trims the last tile's rows/cols) and the `gemmini_loop_ws_spad(...)` intrinsic takes pad args. So arbitrary
M/N/K = generate header/golden at the PADDED shape, tell the KERNEL the real dims, mvin/mvout only the real
elements, and pad fields skip the rest in compute. The real M*N sub-block of golden's C_out is correct
regardless of padding (output row i depends only on A row i) -- PROVEN: M=50 (pad_I=14) gives bit-identical
rows[0:50] to a full M=64 run on the same A.

**Delivered (all spike-validated, floats=0, correct):**
- **Irregular-M/N matmul** (`matmul_gen.py`): relaxed `check_shape` (any M,N,K; only limit = padded shape
  fits scratchpad), padded `_KERNEL_BODY` + `_OPT` (real-region compare). Fitting: M=50/113/41/8 pass. Outer-
  tiled (`gen_matmul_problem_tiled`): partial last M/N block, validated 113x128x128 / 50x256x256 / 41x512x512.
- **GEMV / M=1 decode**: M=1 via the tiled padded path (pad_i=BLK-1). 1x1024x1024, 1x512x512 correct. The
  16x16 array underutilizes at M=1, so the autocomp target is DMA-minimizing B-reuse (distinct regime).
- **Causal attention** (`attention_gen.py`, `ATT_CAUSAL` macro / `causal=True`): future cols (j>i) set to
  -2^62 -> `mx_iexp` returns 0 (z>=63 guard) -> prob 0. S=128 D=64 causal correct, lat 651887 (< full 710541).
- **9 real-shape PROBLEMS** added to `run_mx_pipeline.py` (real-groot-patch/smolvla-vis/smolvla-ffn/llm-attn/
  pi05-ffn/bitvla + real-gemv-1024/3072 + real-attn-causal), at TRACTABLE proxy sizes (exact model dims like
  1152^3 work but cost minutes/spike-eval -- too slow for the search loop; each proxy preserves the irregular-M).

**Shape -> optimization regime (answers "are kernels shape-dependent?"):** YES, one tuned kernel does NOT
transfer. (a) <=128^3 fits one fused loop (mvin-batch+fence-removal -> the 2.39x win); (b) >128^3 outer-tiled
(host-staging-bound); (c) GEMV/small-M DMA-bound (B-reuse); (d) large-K reduction-bound; (e) large-N output-
tiling-bound. The real-shape suite gives autocomp one exemplar per regime -> feeds cost_model.py shape->transform map.

**Deferred (follow-ups, both software):** real S>=256 flash problems; K%32=16 shapes (720/4304, ~10% of
matmuls) -- BLOCKED on golden_model.py partial-32-group INPUT generation (it makes inputs for K=48 but fails
K=80/720). VCS-confirm of an irregular-M baremetal kernel (the pad mechanism is standard Gemmini loop_ws,
spike-validated + fp6/fp4 LEGAL kernels already pass pad args & VCS-pass). Ledger: hand_tune_ledger.jsonl (ts=4).

## Harness hardening (2026-06-10): make it RIGHT before autocomp
Before unleashing autocomp on the new shapes, hardened the eval harness on four axes (all validated on spike):

1. **True-correctness oracle.** The autocomp harness oracle is `gold_src="capture"` (self-consistent spike) --
   it proves determinism, not truth. `verify_truth.py` (fitting) + new `verify_tiled` give an INDEPENDENT fp32
   reference (dequant fp8 A/B -> fp32 matmul, % within 5%). Both made padding-aware (ref over the REAL M*N*K
   sub-block). Irregular-M fitting (M=50/113/41/8) AND tiled (113x128x128, 50x256x256) all hit ~57% within-5%
   -- IDENTICAL to the validated 64^3 baseline => the irregular kernels are TRULY correct, not self-consistent
   garbage. Run verify_truth/verify_tiled when adding a new shape family (the capture-gold chain is sound only
   because the baseline it captures is truth-validated).
2. **Perf-metric integrity.** Found + fixed a metric distortion: my initial "mvin only real rows" used sub-DIM
   partial mvin, which the spike models as a SLOW/unaligned path (M=50 -> 326 cyc > M=64 -> 279, wrong
   direction). Fix = mvin FULL block-aligned padded tiles (1 cheap DMA/tile) and let `loop_ws pad_I/pad_J/pad_K`
   skip the padding in COMPUTE. Now monotonic: M=50 (280) ~= M=64 (279) -- the honest tile-granular cost (the
   16x16 array can't do fractional tiles). read_smem also reads only REAL rows. (LESSON for the ledger: on this
   spike model, block-aligned mvin beats byte-minimal partial mvin -- a spike-vs-RTL fidelity nuance.)
3. **Robustness to candidates.** Made the tiled GOLD_FILE dump + the fitting `full_is_equal` compare ONLY the
   real M*N sub-block. Verified: a candidate scribbling the PADDING region still PASSES (no false negative),
   while a 1-bit corruption of a REAL element FAILS (fitting + tiled + per-head MHA all caught). NOTE: clean_code
   keeps only `void solution(){...last-}`, so correctness injections must go INSIDE the body.
4. **Multi-head / GQA / MQA harness** (`gen_mha_attention_problem`, attention_gen.py): H query heads, H_kv KV
   heads (H_kv=H -> MHA; <H -> GQA; =1 -> MQA), DISTINCT per-head data via golden `--seed`, GQA KV-reuse
   indexing (kv = h/(H/H_kv)), causal supported. Reuses the single-head QK->softmax->PV pipeline in an H-loop
   with per-head pointer arrays; gold captured across all heads; full_is_equal over [H][S][D]. Validated:
   MHA H=2 (393072), GQA H=4/H_kv=2 (786929), MQA H=4/H_kv=1, causal MHA (370047, < full) -- all correct,
   floats=0; per-head corruption caught. 3 problems added (real-mha/real-gqa/real-mha-causal).

**Net:** the matmul/GEMV/attention/MHA harnesses are now correctness-sound (independent fp32 oracle), metric-
honest (block-aligned, tile-granular), and robust (real-region compare, corruption caught). 40 PROBLEMS total.
Safe to run autocomp. Remaining gaps are explicit (K%32=16, S>=256 flash, irregular-M VCS) and tracked.

## SPIKE vs RTL CALIBRATION (2026-06-10) — CRITICAL: spike overstates speedups ~1.5-2x
We instrumented baseline(batch-1) vs optimized(batch-2) matmul kernels with `read_cycles` around the
accelerator region and ran BOTH on VCS (RadianceGemminiOnlyConfig RTL). REAL silicon speedups vs what spike
claimed for the SAME transform (the batch-2 block_mvin recipe):

| kernel | RTL base->opt | **RTL speedup** | spike claimed |
|--------|---------------|-----------------|---------------|
| fp8 64x64        | 3019 -> 3024   | **1.00x** (none) | 1.99x |
| fp8 128x128x256  | 41872 -> 36577 | **1.14x**        | 1.78x |
| fp4 128x128x512  | 40383 -> 33253 | **1.21x**        | 2.39x |
| fp6 128x128x512  | 73085 -> 46958 | **1.56x**        | 2.08x |
(fp8 128x128 excluded: the LEGAL2 batch-2 variant asserts/incorrect when measured — recheck before trusting.)

**WHY:** spike's gemmini model is FUNCTIONAL — accelerator compute costs ~0 cycles, so reducing mvin
transactions looks huge; on real RTL the systolic compute dominates and the mvin saving is a smaller slice.
**The RTL gain scales with how mvin-bound the kernel is:** tiny/compute-bound (fp8 64x64) -> ~0; large-K +
sub-byte packing (fp6-512, K=512) -> the most (1.56x). All kernels remain CORRECT on RTL (PASS); it is only
the speedup MAGNITUDE that deflates.

**CONSEQUENCE for every number in this playbook + ledger:** the spike speedups (autocomp 2.07x, hand-tuned
fp4 2.39x / fp6 2.08x, the 3-5x small-matmuls) are real DIRECTIONS but INFLATED magnitudes. Confirmed-real
RTL gains for the mvin-batch family are 1.0-1.56x. DO NOT quote raw spike numbers as silicon speedups. The
host-overhead-reduction wins (attention softmax) likely translate even less; genuine compute restructurings
could translate more. Ledger ts=5 records hold the VCS-cycle measurements. To measure a new kernel's true
RTL speedup: instrument baseline+optimized with read_cycles (after config_ex / before the readout #ifdef),
build via the bareMetalC sub-make (needs `source env.sh` for the riscv toolchain), run both on simv +loadmem,
compare MMCYCLES.

## Providers, quotas & cost (2026-06-10) — Bedrock daily-quota workaround + Gemini fallback
The real-shape search hit the **AWS Bedrock per-day TOKEN quota** ("ThrottlingException: Too many tokens per
day") — it is PER inference-profile, so:
- `us.anthropic.claude-sonnet-4-6` daily quota = exhausted (the $78 lifetime ran on it).
- `global.anthropic.claude-sonnet-4-6` = SAME model/quality, separate quota pool, **~10% CHEAPER** (the
  global profile is priced below geographic; set `AUTOCOMP_BEDROCK_REGION_MULTIPLIER=0.9`). Use this first.
  Under a deep run it ALSO eventually throttles (shared-ish daily bucket).
- **Gemini 3.1 Pro Preview** (`gcp::gemini-3.1-pro-preview`, Google AI Studio, key in
  `/scratch2/agustin/ModelBlaster/.env` GOOGLE_API_KEY) — DIFFERENT PROVIDER, no Bedrock quota at all.
  Comparable quality, comparable/cheaper price (Standard $2/$12 per 1M <=200k, $4/$18 >200k vs Sonnet
  $3/$15). It's a THINKING model -> ~3x slower per call but UNTHROTTLED. autocomp's `gcp` provider supports
  its tool-calling (`_gemini_tools_from_schema`). Validated end-to-end (menu-gen + plan + eval).
- Opus/Haiku NOT enabled on this Bedrock account; eu./apac. sonnet profiles absent. Non-Claude Bedrock
  (Llama-3.3-70B/Mistral-Large/Nova-Pro) respond but are a real code-gen quality drop.
- **Flex/Batch:** Batch (~50% off) is ASYNC (submit/poll) — can't pipeline the iterative beam search (each
  round needs the prior eval). Flex isn't cleanly exposed in the genai SDK. Standard tier is the practical choice.

**Cost tracking (cost.py):** added Gemini pricing keys (`gemini-3.1-pro` $2/$12, $4/$18 long; `gemini-2.5-pro`).
The `gcp` usage extractor now sets output_tokens = `total_token_count - prompt_token_count` so Gemini's
THINKING tokens (billed as output, excluded from `candidates_token_count`) are counted -> accurate cost.
Verified: a Gemini call billed correctly incl. thinking. Lifetime spend cap = `AUTOCOMP_SPEND_LIMIT_USD=200`
(stop mode); per-run gate `--reserve-usd`. Live: `output/mxpipe_*/cost_live.json` + `cost_ledger.jsonl`.

## Data artifacts — "autocomp decisions as data" (mine later; this is the safe index)
Everything the search decides/produces is persisted; mine it from these locations:
- **Per-run dirs** `autocomp/output/mxpipe_<prob>_<id>_iters<N>/`: `candidates-iter-*/` (every kernel tried),
  `eval-results-iter-*/`, generated plans, `best_candidate_so_far.c`, `metrics-iter-*.json`,
  `cost_live.json`, `cost_ledger.jsonl` (one line/LLM call). NOT deleted between runs.
- **Mined trajectory dataset** `autocomp/output/transform_ledger.jsonl` — one row per attempted transform
  {prob_type, shape, iteration, strategy, compiled, correct, latency, parent_latency, speedup, outcome,
  plan_path}. Regenerate after each run: `python -m autocomp.backend.gemmini.mx_pipeline.transform_log`.
  This is THE dataset for learning shape->transform heuristics (the "compiler pattern" goal).
- **Hand-tuned ledger** `autocomp/hand_tune_ledger.jsonl` (manual transforms + measured deltas).
- **wandb** project `autocomp` (per-iteration metrics/cost, run links printed at start).
- **Human index** = THIS file + `MX_AUTOCOMP_STATUS.md` + `SPIKE_MODEL_CHANGES.md` (all in chipyard-mx).
- Spend log `chipyard-mx/.autocomp-spend_total.json` / `.autocomp-spend.jsonl`.
Append each run's headline speedups to §5 (Run/spend history) so the JSONL has a human companion.
