# HW MX softmax — status (spike-proven, RTL staged + blocked on Normalizer redesign)

Goal: move the attention softmax off the no-FPU host onto the MX accelerator (QK → `iexp`(softmax)
→ fp8-P encode in one accelerator pass), eliminating the ~97% host scalar-softmax cost.

## Proven on spike (end-to-end) ✅
- **Spike model** (`libgemmini/gemmini.cc`, `mx_loop_ws_spad`): added an I-BERT softmax pass — when
  `acc_act==SOFTMAX`, per output row over `N_DIM` cols, apply max → integer `iexp` (z≥63 guard) →
  normalize, in-place on the bf16 scores in `mx_smem`, BEFORE the fp8 requant. Constants from
  `gemmini_config_norm` (qln2/qln2_inv/qb/qc; S_q=1/1024 → 710/92/1385/1006165). Models the RTL
  Normalizer(MAX/SUM_EXP/iexp) + AccumulatorScale(iexp) path.
- **Kernel** (`gemmini-rocc-tests-ref/bareMetalC/attn_fp8_hwsm.c`): QK `loop_ws_spad` with `act=SOFTMAX`
  + `gemmini_config_norm` → P in smem; host does only an **integer** (no-FPU) fp8 group-encode of P
  (`mx_ibf16_to_q` unpack + `mx_ienc_fp8`, target 2^4 to avoid the e4 PV-accumulator overflow); then PV.
  The sticky `acc_act` (config_st preserves SOFTMAX=4) is cleared with a RELU-config_st break before PV.
- **Validation:** softmax-probe P vs true softmax corr 0.9991; full O vs true fp32 **corr 0.9989,
  within-5% 95.7%** — identical to the validated host-softmax path. `main()` has **0 float ops**, 0 NaN.

## RTL changes (staged; flags OFF pending Normalizer redesign)
In the gemmini submodule (`src/main/scala/gemmini/`):
- **ConfigsFP.scala** `defaultMxFPConfig`: `has_normalizations` / `has_nonlinear_activations` — the enable
  switch (currently `false`; flip both `true` to build HW softmax once the Normalizer is MX-ready).
- **AccumulatorScale.scala**: feed full-width `activated_data` (= `iexp(e-max)·inv_sum_exp` = P) to the
  MxRequantizer on SOFTMAX/LAYERNORM/IGELU via a Mux (was always raw `acc_read_data`); gated on
  `has_normalizations`, harmless when off. **The MX branch already computes the iexp inline** — this just
  wires it through instead of discarding it.
- **Scratchpad.scala**: `num_reduce_lanes = half_t.flatten.size` for MX (was -1) — the MX acc feeds the
  Normalizer half-rows (`half_t`) per cycle via the is_last_half double-pump; full-width lane sizing
  mismatched (Vec[16] vs [8]).
- **Normalizer.scala**: `divider`/`reciprocal` `.get` → `getOrElse{dummy}` (MX float type has no HW
  integer divider/reciprocal — those are layernorm-only; softmax uses the separate FP exp_divider); sqrt
  stddev=0 fallback SInt-assert guarded for non-SInt.
- LoopMatmul already orchestrates the softmax norm_cmd multipass (`sm_norm_cmds={{MAX,MAX},{SUM_EXP,
  INV_SUM_EXP},{RESET,RESET}}`) including the spad-store path — no loop-controller change needed.

## BLOCKER: Normalizer softmax arithmetic is integer-I-BERT, not MX-float
After the 4 type/assert fixes above, elaboration still cannot produce a *correct* MX softmax: the
Normalizer's softmax datapath assumes **integer** operands —
- exp_divider computes `1/sum_exp` via `in_to_float(sum_exp_to_inv.asUInt.asSInt)` (Normalizer.scala:578):
  reinterprets the accumulated sum as an **integer**, but the MX acc sum is a **float** (bf16).
- the `iexp` reduction (AccumulationLanes) uses integer-style ops on `acc_t`.

Reconciling this with the MX float accumulator is a **softmax-datapath numerical redesign**, not type
patching — estimate multi-week RTL. Two viable directions: (a) redesign the exp_divider/iexp to consume
the MX float acc directly; (b) arrange the QK output to land in the accumulator as q-domain integers so
the existing integer I-BERT path applies as-is (matches how the spike model + kernel already work, S_q=
1/1024). (b) is likely smaller and aligns with the validated spike numerics.

## Net
Spike: HW softmax attention proven correct end-to-end (corr 0.999), kernel no-FPU-deployable. RTL: the
host-softmax attention remains the VCS-validated path (checksum `0x06b6e0bd26337b04`); HW softmax on RTL
awaits the Normalizer-MX softmax redesign. All RTL edits are staged behind the OFF config flag so the
current config elaborates unchanged.
