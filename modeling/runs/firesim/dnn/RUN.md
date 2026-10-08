# DNN on FireSim (Gemmini) — Run Notes

Bitstream / target config: **`FireSimGemminiRocketConfig`** (extends chipyard
`GemminiRocketConfig` → `gemmini.DefaultGemminiConfig` → `GemminiConfigs.defaultConfig`).
16×16 systolic array, WS, INT8. Cycle-accurate RTL triage was done with the
bit-identical Verilator sim
`sims/verilator/simulator-chipyard.harness-GemminiRocketConfig`.

## Status

| Workload | Result |
|---|---|
| ResNet-50 (`imagenet/`) | Runs clean on the bitstream |
| `transformers/transformer-baremetal` (BERT-base encoder) | **>45B cycles, never completes** (~0.93B MACs ≈ 3.6M ideal cycles on 16×16) |

---

## BERT `transformer-baremetal` blow-up — ROOT CAUSE (triaged 2026-07)

**The bitstream has no LayerNorm/Softmax/I-GELU hardware. BERT's attention
softmax issues normalization commands anyway; the norm-less StoreController
wedges on the first malformed accumulator mvout and the CPU spins forever on
`gemmini_fence()`. It is not a 10⁴× slowdown — it is an unbounded deadlock that
happened to be observed at 45B cycles.**

ResNet-50 uses only matmul + ReLU + max-pool, none of which touch the norm path,
so it runs clean on the exact same bitstream. That is the differential.

### Three independent pieces of evidence

**1. Config — the norm functional units are absent.**
`gemmini/src/main/scala/gemmini/GemminiConfigs.scala:92` defaults
`has_normalizations = false`. `Configs.scala` `defaultConfig` sets
`has_nonlinear_activations = true` (RELU/GELU-lite) but **never sets
`has_normalizations`**, so it stays `false`. Only
`CustomConfigs.scala:47` turns it on. `GemminiRocketConfig` (and therefore
`FireSimGemminiRocketConfig` and the Verilator sim) all inherit `defaultConfig`
→ **no LayerNorm/Softmax/I-GELU units built into the RTL.**

**2. Software — the softmax path fires norm commands regardless.**
`gemmini_params.h` is generated **without** `HAS_NORMALIZATIONS`.
- The LayerNorm path (`tiled_norm_auto` → `sp_tiled_norm`, gemmini.h:3470) *is*
  `#ifdef HAS_NORMALIZATIONS`-guarded → prints `"Normalizations not supported in
  this Gemmini config"` and `exit(1)` (clean).
- But the **attention softmax inside `tiled_matmul_auto`** (gemmini.h:799-810)
  issues `gemmini_config_norm(...)` and norm-tagged accumulator mvouts
  **unconditionally**. This runs *before* the FFN LayerNorm, so it is what the
  hardware hits first.

**3. Hardware — the norm mvout becomes an illegal store and wedges.**
With `has_normalizations=false`, `StoreController.scala:139` forces
`DoConfigNorm=false`, so a `CONFIG_NORM` is neither a config nor a norm-config and
falls through, and the softmax mvout is issued as a full-accumulator-row store
with `blocks > 1`, violating
`StoreController.scala:225: assert(!(read_full_acc_row && blocks > 1))`.
In **Verilator** (assertions on) this aborts immediately. In **FireSim/FPGA**
(assertions compiled out) the store controller proceeds into the illegal state,
never raises `io.completed`, and the CPU's `gemmini_fence()` after the softmax
matmul spins unbounded → the observed >45B-cycle "run that never finishes".

### Reproduction (cycle-accurate, same config as the bitstream)

Reduced isolation tests in
`generators/gemmini/software/gemmini-rocc-tests/transformers/`
(built into `build/transformers/`), run on the Verilator sim:

| Test | dims | Result |
|---|---|---|
| `mm_nonorm` | 16×16×64 matmul, `NO_ACTIVATION` | **PASSED**, matmul = **628 Gemmini cycles**, 714,086 total sim cycles |
| `mm_softmax` | identical 16×16×64 matmul + **`SOFTMAX`** | **abort** at `StoreController.scala:225` "Block-mvouts are not permitted when moving out full accumulator data" |
| `bert_tiny` | 1 encoder layer, seq 16 / hidden 64 / exp 128 / 4 heads | **abort** — completes the QKV matmuls, then hits the *identical* `StoreController.scala:225` failure at the attention softmax mvout (`bert_triage/verilator_bert_tiny.log`) |

The two matmuls are byte-for-byte identical except for the activation flag →
the norm path is conclusively the cause.

**Functional cross-check (spike + libgemmini):** `mm_nonorm` and `mm_softmax`
both "complete" functionally (spike's model tolerates the norm ops), and
`bert_tiny` hits the software `#ifdef` guard ("Normalizations not supported").
The functional model masks the wedge; only cycle-accurate RTL on this config
exposes it.

### Real numbers

- **BERT on this bitstream: no finite cycle count — it deadlocks.** The 45B is
  a lower bound on a hang, not a runtime.
- Non-norm GEMM baseline is healthy: a 16×16×64 matmul = **628 Gemmini cycles**.
- BERT-base **one** encoder layer = ~0.93B MACs → **~3.64M ideal cycles** on a
  16×16 array (0.93e9 / 256). Full 12-layer ≈ **~43.6M ideal MAC cycles**;
  ONNXim reports **27.2M** for full BERT-base (larger/higher-util model — the
  ~1.6× gap is array-size/utilisation/overhead modeling, not the pathology).

### Fixes

1. **Build a norm-enabled bitstream**: use a config with
   `has_normalizations = true` (see `CustomConfigs.scala`) — this adds the
   LayerNorm/Softmax/I-GELU accumulator units. Rebuild the FireSim bitstream +
   `gemmini_params.h` with `HAS_NORMALIZATIONS`, then re-run.
2. **Or** guard the attention-softmax path the same way the LayerNorm path is
   guarded, and do softmax/layernorm on the Rocket core (slow but correct) so
   the run at least terminates.

Artifacts: `bert_triage/` (Verilator logs) and
`generators/gemmini/software/gemmini-rocc-tests/transformers/{mm_nonorm,mm_softmax,bert_tiny}.c`.
Build/run recipe: `modeling/tools/BUILD.md`.
