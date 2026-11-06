# Kodiak VME source patch

`FireSimKodiakVME1CoreConfig` selects the distinct `KodiakVME1CoreConfig` compute SoC: one Shuttle hart with a VLEN512/DLEN256 Saturn VME (`xsfmm64t`) and one scalar Rocket hart. The current Kodiak OPU ASIC and FireSim bitstream use legacy BME encodings; this target does not change that hardware.

The Chipyard gitlinks pin Saturn to `4359c36b710bb230335fc2866a28bd3f1911ed6e` and Rocket to the public Iris base `fc745a8c377fdbf1d278eea81da9ac47b4b5fbc0`. `.gitmodules` fetches Saturn from the published `miles-rusch-berkeley/saturn-vectors` branch; the original `ucb-bar/saturn-vectors` URL cannot serve that new commit. The pinned Rocket decoder eagerly reads the vector `vtype` and `mtype` registers on a scalar Rocket and fails during elaboration. This directory carries the focused fix as an exact `git show --format=` patch of local Rocket commit `8d47f5d30a33e230d9fc9d92c18fe95cd7aa65ad`. Its SHA-256 is `b616b572e249953e2e77bd69abf443db6771ac8a63b9a5be622a55637ae463aa`.

Apply it to an **editable task-managed Rocket sibling** at the exact public base, then use that sibling as `generators/rocket-chip` for source compilation and elaboration:

```sh
./patches/kodiak-vme/apply-rocket-guard.sh /path/to/task/rocket-chip
```

The script rejects a different HEAD, changed patch bytes, or a dirty tracked worktree. It leaves a visible uncommitted diff in the task-managed Rocket sibling; do not commit a symlink overlay as a Chipyard gitlink. This patch is needed only for configurations with a scalar Rocket. The vector Rocket path remains the same.

Validation on the source graph used to produce this patch: Firechip source compilation and bounded FireSim FIRRTL generation passed. A standalone Kodiak VME Verilator simulator executed a fresh 35×63×33 biased K-tail ELF on Shuttle hart 0 through direct SerialTL. Pinned Zvt Spike and RTL reported the same output digest `0x15d98143c9a17f64` with zero mismatches, unwritten outputs, or guard writes, and RTL printed `KT DONE`. The RTL kernel interval was 2,194 target cycles. These are functional RTL results, not a FireSim FPGA bitstream or physical ASIC result.
