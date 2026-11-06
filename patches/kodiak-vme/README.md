# Kodiak VME source patch

`FireSimKodiakVME1CoreConfig` selects the distinct `KodiakVME1CoreConfig` compute SoC: one Shuttle hart with a VLEN512/DLEN256 Saturn VME (`xsfmm64t`) and one scalar Rocket hart. The current Kodiak OPU ASIC and FireSim bitstream use legacy BME encodings; this target does not change that hardware.

The Chipyard gitlinks pin Saturn to `4359c36b710bb230335fc2866a28bd3f1911ed6e` and Rocket to the public Iris base `fc745a8c377fdbf1d278eea81da9ac47b4b5fbc0`. `.gitmodules` fetches Saturn from the published `miles-rusch-berkeley/saturn-vectors` branch; the original `ucb-bar/saturn-vectors` URL cannot serve that new commit. The pinned Rocket decoder eagerly reads the vector `vtype` and `mtype` registers on a scalar Rocket and fails during elaboration. This directory carries the focused fix as an exact `git show --format=` patch of local Rocket commit `8d47f5d30a33e230d9fc9d92c18fe95cd7aa65ad`. Its SHA-256 is `b616b572e249953e2e77bd69abf443db6771ac8a63b9a5be622a55637ae463aa`.

Apply it to an **editable task-managed Rocket sibling** at the exact public base, then use that sibling as `generators/rocket-chip` for source compilation and elaboration:

```sh
./patches/kodiak-vme/apply-rocket-guard.sh /path/to/task/rocket-chip
```

The script rejects a different HEAD, changed patch bytes, or a dirty tracked worktree. It leaves a visible uncommitted diff in the task-managed Rocket sibling; do not commit a symlink overlay as a Chipyard gitlink. This patch is needed only for configurations with a scalar Rocket. The vector Rocket path remains the same.

Validation on the source graph used to produce this patch: Firechip source compilation and bounded FireSim FIRRTL generation passed. A standalone Kodiak VME Verilator simulator executed a fresh 35×63×33 biased K-tail ELF on Shuttle hart 0 through direct SerialTL. Pinned Zvt Spike and RTL reported the same output digest `0x15d98143c9a17f64` with zero mismatches, unwritten outputs, or guard writes, and RTL printed `KT DONE`. The RTL kernel interval was 2,194 target cycles. These are functional RTL results, not a FireSim FPGA bitstream or physical ASIC result.

## FireSim build in a managed worktree

The Iris FireSim gitlink is `e1bdea8bb042b900995436ecc3d7c44100eb9da0`. Its sbt-ghpages docs plugin invokes JGit status on the task manager's `.git` file and throws `NoWorkTreeException` while assembling MIDAS. Apply the exact build-only diff from validated local FireSim commit `0bf1e8ebc71c8f9bf65f14e16fd29aea9967c63a` to an editable task-managed FireSim sibling before Golden Gate:

```sh
./patches/kodiak-vme/apply-firesim-worktree-build.sh /path/to/task/firesim
```

The patch SHA-256 is `36a72ab105a650af30c456a3a8478da2765bac7bc3a5248e2e6e4279b0ceec77`. It only removes docs publishing plugin wiring in `sim/build.sbt` and `sim/project/plugins.sbt`; it does not change target or MIDAS logic. Keep the Chipyard FireSim gitlink at the public Iris pin. With this patch, Golden Gate U250 `verilog`, Verilator metasim build, and a 35×63×33 biased K-tail ELF run passed. FireSim metasim reported the same digest `0x15d98143c9a17f64`, zero mismatches and guard failures, and `KT DONE` (2,761 kernel target cycles); a SHA-bound receipt is in the managed task. Vivado bitstream generation and FPGA execution are separate gates.

## Experimental compact U250 candidate

`KodiakVMECompact1CoreConfig` and `FireSimKodiakVMECompact1CoreConfig` keep VLEN512 and the `xsfmm64t` Zvt instruction contract while reducing DLEN from 256 to 128. The physical int8 matrix array is 16×16 (256 MAC cells) instead of 32×32 (1,024 cells). This is a distinct hardware configuration and remains **experimental until U250 implementation fits, meets timing, and produces a tested bitstream**. It does not alter the current BME OPU ASIC or bitstream.

The VLEN512/DLEN256 VME target failed U250 `place_design` utilization checks: 2,208,803 slice LUTs required versus 1,728,000 available. The compact target passed JDK17/Espresso Firechip compilation, forced FIRRTL generation, direct SerialTL Verilator execution, Golden Gate U250 generation, FireSim Verilator build, and exact ELF metasim. The same 35×63×33 biased K-tail ELF produced digest `0x15d98143c9a17f64`, zero mismatch and guard fields, and `KT DONE` in both compact RTL and metasim. The compact RTL and metasim kernel intervals were 2,695 and 3,264 target cycles, respectively. These cycle counts are functional test observations; hardware utilization requires issued-work and available-cell counters over a defined interval and cannot be inferred from speedup alone.

The compact U250 synthesis/implementation and FPGA workload remain separate gates. Use a new HWDB identity and a driver built for the compact configuration if a bitstream passes. Do not reuse the VLEN512/DLEN256 driver or an OPU HWDB entry.
