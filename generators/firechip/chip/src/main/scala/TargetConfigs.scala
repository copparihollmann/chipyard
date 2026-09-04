// See LICENSE for license details.

package firechip.chip

import java.io.File

import chisel3._
import chisel3.util.{log2Up}

import org.chipsalliance.cde.config.{Config}
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink.{BootROMLocated}
import freechips.rocketchip.devices.debug.{DebugModuleKey}
import freechips.rocketchip.prci.{AsynchronousCrossing}
import testchipip.cosim.{TracePortKey}
import icenet._

import chipyard.clocking.{ChipyardPRCIControlKey}
import chipyard.harness.{HarnessClockInstantiatorKey}

// Disables clock-gating; doesn't play nice with our FAME-1 pass
class WithoutClockGating extends Config((site, here, up) => {
  case DebugModuleKey => up(DebugModuleKey).map(_.copy(clockGate = false))
  case ChipyardPRCIControlKey => up(ChipyardPRCIControlKey).copy(enableTileClockGating = false)
})

// Use the firesim clock bridge instantiator. this is required
class WithFireSimHarnessClockBridgeInstantiator extends Config((site, here, up) => {
  case HarnessClockInstantiatorKey => () => new FireSimClockBridgeInstantiator
})

// Testing configurations
// This enables printfs used in testing
class WithScalaTestFeatures extends Config((site, here, up) => {
  case TracePortKey => up(TracePortKey).map(_.copy(print = true))
})

// Multi-cycle regfile for rocket+boom
class WithFireSimMultiCycleRegfile extends Config((site, here, up) => {
  case FireSimMultiCycleRegFile => true
})

// Model multithreading optimization
class WithFireSimFAME5 extends Config((site, here, up) => {
  case FireSimFAME5 => true
})

class WithNIC extends icenet.WithIceNIC(inBufFlits = 8192, ctrlQueueDepth = 64)

// Adds a small/large NVDLA to the system
class WithNVDLALarge extends nvidia.blocks.dla.WithNVDLA("large")
class WithNVDLASmall extends nvidia.blocks.dla.WithNVDLA("small")

// Minimal set of FireSim-related design tweaks - notably discludes FASED, TraceIO, and the BlockDevice
class WithMinimalFireSimDesignTweaks extends Config(
  // Required*: Punch all clocks to FireSim's harness clock instantiator
  new WithFireSimHarnessClockBridgeInstantiator ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(1000.0) ++
  new chipyard.harness.WithClockFromHarness ++
  new chipyard.harness.WithResetFromHarness ++
  new chipyard.config.WithNoClockTap ++
  new chipyard.clocking.WithPassthroughClockGenerator ++
  // Required: Existing FAME-1 transform cannot handle black-box clock gates
  new WithoutClockGating ++
  // Optional: Do not support debug module w. JTAG until FIRRTL stops emitting @(posedge ~clock)
  new chipyard.config.WithNoDebug ++
  // Required*: Removes thousands of assertions that would be synthesized (* pending PriorityMux bugfix)
  new WithoutTLMonitors
)

// Non-frequency tweaks that are generally applied to all firesim configs
class WithFireSimDesignTweaks extends Config(
  new WithMinimalFireSimDesignTweaks ++
  // Required: Remove the debug clock tap, this breaks compilation of target-level sim in FireSim
  new chipyard.config.WithNoClockTap ++
  // Optional: reduce the width of the Serial TL interface
  new testchipip.serdes.WithSerialTLWidth(4) ++
  // Required*: Scale default baud rate with periphery bus frequency
  new chipyard.config.WithUART(
    baudrate=BigInt(3686400L),
    txEntries=256, rxEntries=256) ++        // FireSim requires a larger UART FIFO buffer,
  new chipyard.config.WithNoUART() ++       // so we overwrite the default one
  // Optional: Adds IO to attach tracerV bridges
  new chipyard.config.WithTraceIO ++
  // Optional: Request 16 GiB of target-DRAM by default (can safely request up to 64 GiB on F1)
  new freechips.rocketchip.subsystem.WithExtMemSize((1 << 30) * 16L) ++
  // Optional: Removing this will require using an initramfs under linux
  new testchipip.iceblk.WithBlockDevice
)

// Tweaks to modify target clock frequencies / crossings to legacy firesim defaults
class WithFireSimHighPerfClocking extends Config(
  // Create clock group for uncore that does not include mbus
  new chipyard.clocking.WithClockGroupsCombinedByName(("uncore", Seq("sbus", "pbus", "fbus", "cbus", "implicit"), Nil)) ++
  // Optional: This sets the default frequency for all buses in the system to 3.2 GHz
  // (since unspecified bus frequencies will use the pbus frequency)
  // This frequency selection matches FireSim's legacy selection and is required
  // to support 200Gb NIC performance. You may select a smaller value.
  new chipyard.config.WithPeripheryBusFrequency(3200.0) ++
  new chipyard.config.WithControlBusFrequency(3200.0) ++
  new chipyard.config.WithSystemBusFrequency(3200.0) ++
  new chipyard.config.WithFrontBusFrequency(3200.0) ++
  new chipyard.config.WithControlBusFrequency(3200.0) ++
  // Optional: These three configs put the DRAM memory system in it's own clock domain.
  // Removing the first config will result in the FASED timing model running
  // at the pbus freq (above, 3.2 GHz), which is outside the range of valid DDR3 speedgrades.
  // 1 GHz matches the FASED default, using some other frequency will require
  // runnings the FASED runtime configuration generator to generate faithful DDR3 timing values.
  new chipyard.config.WithMemoryBusFrequency(1000.0) ++
  new chipyard.config.WithAsynchrousMemoryBusCrossing
)

// Tweaks that are generally applied to all firesim configs setting a single clock domain at 1000 MHz
class WithFireSimConfigTweaks extends Config(
  // 1 GHz matches the FASED default (DRAM modeli realistically configured for that frequency)
  // Using some other frequency will require runnings the FASED runtime configuration generator
  // to generate faithful DDR3 timing values.
  new chipyard.config.WithSystemBusFrequency(1000.0) ++
  new chipyard.config.WithControlBusFrequency(1000.0) ++
  new chipyard.config.WithPeripheryBusFrequency(1000.0) ++
  new chipyard.config.WithControlBusFrequency(1000.0) ++
  new chipyard.config.WithMemoryBusFrequency(1000.0) ++
  new chipyard.config.WithFrontBusFrequency(1000.0) ++
  new WithFireSimDesignTweaks
)

// Tweaks to use minimal design tweaks
// Need to use initramfs to use linux (no block device)
class WithMinimalFireSimHighPerfConfigTweaks extends Config(
  new WithFireSimHighPerfClocking ++
  new freechips.rocketchip.subsystem.WithNoMemPort ++
  new testchipip.soc.WithMbusScratchpad ++
  new WithMinimalFireSimDesignTweaks
)

/**
  * Adds BlockDevice to WithMinimalFireSimHighPerfConfigTweaks
  */
class WithMinimalAndBlockDeviceFireSimHighPerfConfigTweaks extends Config(
  new WithFireSimHighPerfClocking ++
  new freechips.rocketchip.subsystem.WithNoMemPort ++ // removes mem port for FASEDBridge to match against
  new testchipip.soc.WithMbusScratchpad ++ // adds backing scratchpad for memory to replace FASED model
  new testchipip.iceblk.WithBlockDevice(true) ++ // add in block device
  new WithMinimalFireSimDesignTweaks
)

/**
  *  Adds Block device to WithMinimalFireSimHighPerfConfigTweaks
  */
class WithMinimalAndFASEDFireSimHighPerfConfigTweaks extends Config(
  new WithFireSimHighPerfClocking ++
  new WithMinimalFireSimDesignTweaks
)

// Tweaks for legacy FireSim configs.
class WithFireSimHighPerfConfigTweaks extends Config(
  new WithFireSimHighPerfClocking ++
  new WithFireSimDesignTweaks
)

// Tweak more representative of testchip configs
class WithFireSimTestChipConfigTweaks extends Config(
  // Frequency specifications
  new chipyard.config.WithTileFrequency(1000.0) ++       // Realistic tile frequency for a test chip
  new chipyard.config.WithSystemBusFrequency(500.0) ++   // Realistic system bus frequency
  new chipyard.config.WithMemoryBusFrequency(1000.0) ++  // Needs to be 1000 MHz to model DDR performance accurately
  new chipyard.config.WithPeripheryBusFrequency(500.0) ++  // Match the sbus and pbus frequency
  new chipyard.config.WithFrontBusFrequency(500.0) ++      // Match the sbus and fbus frequency
  new chipyard.config.WithControlBusFrequency(500.0) ++    // Match the sbus and cbus frequency
  new chipyard.clocking.WithClockGroupsCombinedByName(("uncore", Seq("sbus", "pbus", "fbus", "cbus", "implicit"), Seq("tile"))) ++
  //  Crossing specifications
  new chipyard.config.WithCbusToPbusCrossingType(AsynchronousCrossing()) ++ // Add Async crossing between PBUS and CBUS
  new chipyard.config.WithSbusToMbusCrossingType(AsynchronousCrossing()) ++ // Add Async crossings between backside of L2 and MBUS
  new freechips.rocketchip.rocket.WithRationalCDCs ++   // Add rational crossings between RocketTile and uncore
  new boom.v3.common.WithRationalBoomTiles ++ // Add rational crossings between BoomTile and uncore
  new WithFireSimDesignTweaks
)

/*******************************************************************************
* Full TARGET_CONFIG configurations. These set parameters of the target being
* simulated.
*
* In general, if you're adding or removing features from any of these, you
* should CREATE A NEW ONE, WITH A NEW NAME. This is because the manager
* will store this name as part of the tags for the AGFI, so that later you can
* reconstruct what is in a particular AGFI. These tags are also used to
* determine which driver to build.
 *******************************************************************************/

//*****************************************************************
// Rocket configs, base off chipyard's RocketConfig
//*****************************************************************
// DOC include start: firesimconfig
class FireSimRocketConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.RocketConfig)
// DOC include end: firesimconfig

class FireSimRocket1GiBDRAMConfig extends Config(
  new freechips.rocketchip.subsystem.WithExtMemSize((1 << 30) * 1L) ++
  new FireSimRocketConfig)

class FireSimRocketMMIOOnly1GiBDRAMConfig extends Config(
  new freechips.rocketchip.subsystem.WithExtMemSize((1 << 30) * 1L) ++
  new FireSimRocketMMIOOnlyConfig)

class FireSimRocket4GiBDRAMConfig extends Config(
  new freechips.rocketchip.subsystem.WithExtMemSize((1 << 30) * 4L) ++
  new FireSimRocketConfig)

class FireSimRocketMMIOOnly4GiBDRAMConfig extends Config(
  new freechips.rocketchip.subsystem.WithExtMemSize((1 << 30) * 4L) ++
  new FireSimRocketMMIOOnlyConfig)

class FireSimQuadRocketConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.QuadRocketConfig)

// A stripped down configuration that should fit on all supported hosts.
// Flat to avoid having to reorganize the config class hierarchy to remove certain features
class FireSimSmallSystemConfig extends Config(
  new WithDefaultFireSimBridges ++
  new chipyard.config.WithPeripheryBusFrequency(3200.0) ++
  new chipyard.config.WithControlBusFrequency(3200.0) ++
  new chipyard.config.WithSystemBusFrequency(3200.0) ++
  new chipyard.config.WithFrontBusFrequency(3200.0) ++
  new chipyard.config.WithMemoryBusFrequency(3200.0) ++
  new WithoutClockGating ++
  new WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithExtMemSize(1 << 28) ++
  new testchipip.serdes.WithSerialTL(Seq(testchipip.serdes.SerialTLParams(
    client = Some(testchipip.serdes.SerialTLClientParams(totalIdBits = 4)),
    phyParams = testchipip.serdes.DecoupledExternalSyncSerialPhyParams(phitWidth=32, flitWidth=32)
  ))) ++
  new testchipip.iceblk.WithBlockDevice ++
  new chipyard.config.WithUARTInitBaudRate(BigInt(3686400L)) ++
  new freechips.rocketchip.subsystem.WithInclusiveCache(nWays = 2, capacityKB = 64) ++
  new chipyard.RocketConfig)

class FireSimDmiRocketConfig extends Config(
  new chipyard.harness.WithSerialTLTiedOff ++ // (must be at top) tieoff any bridges that connect to serialTL so only DMI port is connected
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.dmiRocketConfig)

//*****************************************************************
// Boom config, base off chipyard's LargeBoomV3Config
//*****************************************************************
class FireSimLargeBoomConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.LargeBoomV3Config)

//********************************************************************
// Heterogeneous config, base off chipyard's LargeBoomAndRocketConfig
//********************************************************************
class FireSimLargeBoomAndRocketConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.LargeBoomAndRocketConfig)

//******************************************************************
// Gemmini NN accel config, base off chipyard's GemminiRocketConfig
//******************************************************************
class FireSimGemminiRocketConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.GemminiRocketConfig)

class FireSimLeanGemminiRocketConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.LeanGemminiRocketConfig)

class FireSimLeanGemminiPrintfRocketConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.LeanGemminiPrintfRocketConfig)

// 2-tile Shuttle SoC: tile 0 = Shuttle+Gemmini, tile 1 = Shuttle+OPU(vl=128)
class FireSimGemminiAndOPUShuttleConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.GemminiAndOPUShuttleConfig)

// Multicore Saturn-vectors SoCs: EVERY Shuttle tile carries its own Saturn vector unit
// (vLen=256, dLen=128 -- matching the SpacemiT K1, so a schedule tuned on the board transfers
// without a re-tune). The 2-tile SoC above gives only tile 1 a vector unit, so it cannot host a
// multi-hart RVV workload; these can.
//
// FireSim rather than Verilator because RTL simulation runs ~10^4 cycles/s, which cannot reach a
// whole-model inference (~10^10 cycles); on the FPGA the same design runs at tens of MHz, which
// makes whole-model multicore RVV cycle counts actually obtainable.
//
// The DUAL config is the one to build first: 2 Saturn units at vLen=256/dLen=128 are already a
// large step up from the single vLen=128 OPU that fits today, and a placement failure only
// surfaces after hours of synthesis.
class FireSimDualSaturnV256D128ShuttleConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.DualSaturnV256D128ShuttleConfig)

class FireSimMultiSaturnV256D128ShuttleConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.MultiSaturnV256D128ShuttleConfig)

// Single Shuttle core carrying the OUTER-PRODUCT unit at vLen=256/dLen=128, i.e. a 32-lane int8 tile.
//
// Why this one. The two OPU bitstreams that exist are both vLen=128/dLen=64 (a 16-lane tile), and one of
// them (shuttle_gemmini_opu) puts the unit on tile 1 only -- so an image whose kernel runs on hart 0 finds
// no OPU there and takes an illegal instruction. This is a SINGLE core with the unit on it, so there is no
// hart routing to get wrong, and its 32-lane tile matches the geometry the microkernel corpus is certified
// against on Verilator -- which is the point: the same acceptance corpus and the same whole-model image run
// here in seconds instead of hours.
//
// fpga_frequency is set to 25 MHz in the build recipe rather than the 30 the vLen=128 OPU closed at: one
// vLen=256 unit is a real area step up from one vLen=128 unit, and a frequency that will not close wastes
// the entire synthesis rather than degrading it.
class FireSimOPUV256D128ShuttleConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.OPUV256D128ShuttleConfig)

// The same target with the OPU cluster array's clock gate replaced by a passthrough.
//
// The gated variant does not close timing. Its gate lowers to rocket-chip's EICG_wrapper, which
// ReplaceAbstractClockGates does not rewrite, so the gate stays combinational logic in the clock path and
// the tools add a second global clock net for the gated domain's 131,585 loads. That net comes out 3.79 ns
// behind its ungated parent while both ends share one clock name and are therefore hold-checked at zero
// skew: WHS -3.972 ns over 258,620 endpoints, which phys_opt reduced by 0.7% in 34 min. Frequency does not
// help -- the build is already at 25 MHz and the failing clock has +16.520 ns of spare SETUP slack, while
// hold and skew are both period-independent.
//
// Ungating is functionally transparent on this unit but that is a measurement, not an assumption:
// OuterProductCluster.pipe is assigned unconditionally and so does rely on the gate to hold. 14 corpus
// cases chosen to stress it come back bit-exact against both the in-image reference and the host digest.
class FireSimOPUV256D128ShuttleNoGateConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.OPUV256D128ShuttleNoGateConfig)

// Kodiak's SoC on FireSim, with and without the outer-product unit.
//
// The bus frequencies are 500 MHz rather than the 1000 MHz of WithFireSimConfigTweaks because that is what
// Kodiak's own FireSim configs use; keeping it means the DRAM model and the target clock match the design
// this is a port of. Everything else about the target comes from chipyard.WithKodiakBase.
//
// The OPU variant is the larger risk of the two by a wide margin: two cores at vLen=512/dLen=256 give an
// 8x8 cluster array each, four times the v256d128 array per core and twice as many cores, so roughly 8x
// the OPU that measured 81,335 LUTs and 131,584 FFs. It carries the clock-gate passthrough for the reason
// recorded on FireSimOPUV256D128ShuttleNoGateConfig -- with the gate left in, that much gated fabric is
// what produced 258,620 hold-failing endpoints, and no frequency setting can trade against hold.
class WithFireSim500ConfigTweaks extends Config(
  new chipyard.config.WithSystemBusFrequency(500.0) ++
  new chipyard.config.WithControlBusFrequency(500.0) ++
  new chipyard.config.WithPeripheryBusFrequency(500.0) ++
  new chipyard.config.WithMemoryBusFrequency(500.0) ++
  new chipyard.config.WithFrontBusFrequency(500.0) ++
  new WithFireSimDesignTweaks
)

class FireSimKodiakConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSim500ConfigTweaks ++
  new chipyard.KodiakConfig)

class FireSimKodiakOPUConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSim500ConfigTweaks ++
  new chipyard.KodiakOPUConfig)

// Single-core Kodiak. FireSimKodiakOPUConfig does not route on the U250 -- 86.36% post-synth LUT and
// 23,531 signals left contending for nodes after Phase 8, with the router naming the OPU's mrf_idx
// fanout as the congestion source. Dropping one tile removes 601,039 LUT of core plus 348,328 of OPU.
// See the block comment on Kodiak1CoreConfig for the measurements and for what the missing core costs
// when citing a result.
class FireSimKodiak1CoreConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSim500ConfigTweaks ++
  new chipyard.Kodiak1CoreConfig)

class FireSimKodiakOPU1CoreConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSim500ConfigTweaks ++
  new chipyard.KodiakOPU1CoreConfig)

//**********************************************************************************
// Supernode Configurations, base off chipyard's RocketConfig
//**********************************************************************************
class SupernodeFireSimRocketConfig extends Config(
  new WithFireSimHarnessClockBridgeInstantiator ++
  new chipyard.harness.WithHomogeneousMultiChip(n=4, new Config(
    new freechips.rocketchip.subsystem.WithExtMemSize((1 << 30) * 8L) ++ // 8GB DRAM per node
    new FireSimRocketConfig)))

//**********************************************************************************
//* CVA6 Configurations
//*********************************************************************************/
class FireSimCVA6Config extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.CVA6Config)

//**********************************************************************************
// System with 16 LargeBOOMs that can be simulated with Golden Gate optimizations
// - Requires MTModels and MCRams mixins as prefixes to the platform config
// - May require larger build instances or JVM memory footprints
//*********************************************************************************/
class FireSim16LargeBoomV3Config extends Config(
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new boom.v3.common.WithNLargeBooms(16) ++
  new chipyard.config.AbstractConfig)

class FireSimNoMemPortConfig extends Config(
  new WithDefaultFireSimBridges ++
  new freechips.rocketchip.subsystem.WithNoMemPort ++
  new testchipip.soc.WithMbusScratchpad ++
  new WithFireSimConfigTweaks ++
  new chipyard.RocketConfig)

class FireSimRocketMMIOOnlyConfig extends Config(
  new WithDefaultMMIOOnlyFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.RocketConfig)

class FireSimLeanGemminiRocketMMIOOnlyConfig extends Config(
  new WithDefaultMMIOOnlyFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.LeanGemminiRocketConfig)

// Disabled while radiance submodule is dropped from the SBT graph for the Shuttle+Gemmini+OPU bitstream build.
// class FireSimRadianceClusterSynConfig extends Config(
//   new chipyard.harness.WithHarnessBinderClockFreqMHz(500.0) ++
//   new chipyard.config.WithNoTraceIO ++
//   new WithDefaultFireSimBridges ++
//   new chipyard.config.WithRadBootROM ++
//   new WithFireSimConfigTweaks ++
//   new chipyard.RadianceClusterSynConfig)

class FireSimLargeBoomCospikeConfig extends Config(
  new WithCospikeBridge ++
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks++
  new chipyard.LargeBoomV3Config)

class FireSimQuadRocketSbusRingNoCConfig extends Config(
  new chipyard.config.WithNoTraceIO ++
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks++
  new chipyard.QuadRocketSbusRingNoCConfig)

class FireSimLargeBoomSV39CospikeConfig extends Config(
  new WithCospikeBridge ++
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks++
  new freechips.rocketchip.rocket.WithSV39 ++
  new chipyard.LargeBoomV3Config)
