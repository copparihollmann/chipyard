# tools/BUILD.md — building & running Gemmini DNN tests for triage

Environment: `source /scratch2/agustin/chipyard/env.sh` (conda `riscv-tools`).
Deps are conda/pip only (riscv64-unknown-elf-gcc, spike, verilator sim prebuilt).

## Gemmini RoCC test binaries (baremetal)

Source: `generators/gemmini/software/gemmini-rocc-tests/`.
The `transformers/` group holds the BERT test plus the triage micro-tests.

```bash
source /scratch2/agustin/chipyard/env.sh
cd generators/gemmini/software/gemmini-rocc-tests/build
make BAREMETAL_ONLY=1 transformers        # builds build/transformers/*-baremetal
```

`transformers/Makefile` `tests` list now includes:
`transformer` (BERT-base + transformer-small), and triage tests
`bert_tiny` (1 encoder layer, seq16/hidden64/exp128/4heads),
`mm_nonorm` (16×16×64 matmul, NO_ACTIVATION),
`mm_softmax` (same matmul + SOFTMAX — isolates the norm path).

## Run on the cycle-accurate Verilator sim (== FireSim bitstream config)

Sim: `sims/verilator/simulator-chipyard.harness-GemminiRocketConfig`
(chipyard `GemminiRocketConfig`; FireSim uses `FireSimGemminiRocketConfig`
which wraps the same Gemmini config, so RTL behavior matches the bitstream).

```bash
cd sims/verilator
BIN=../../generators/gemmini/software/gemmini-rocc-tests/build/transformers
# self-terminating test:
./simulator-chipyard.harness-GemminiRocketConfig $BIN/mm_nonorm-baremetal
# potentially-hanging test — cap cycles (note the +permissive wrapping,
# this harness needs it or htif treats the plusarg as the ELF path):
./simulator-chipyard.harness-GemminiRocketConfig \
    +permissive +max-cycles=10000000 +permissive-off $BIN/mm_softmax-baremetal
```

Gotchas:
- `--max-cycles=` / bare `+max-cycles=` are NOT accepted by this harness. Use
  `+permissive +max-cycles=N +permissive-off <ELF>`.
- Verilator throughput is ~10⁵–10⁶ target cycles/min; boot alone is ~700K cycles.
- Assertions are compiled IN for Verilator (a bad state `$stop`s the sim). They
  are compiled OUT for the FireSim FPGA bitstream (bad state → silent hang).

## Run on spike (functional, fast — but masks RTL-only bugs)

```bash
cd generators/gemmini/software/gemmini-rocc-tests/build/transformers
spike --extension=gemmini mm_softmax-baremetal
```
libgemmini models Gemmini functionally and tolerates the norm ops, so spike does
NOT reproduce the norm-less-hardware hang. Use spike for correctness, Verilator
for the pathology. See `modeling/runs/firesim/dnn/RUN.md` for the BERT triage.
