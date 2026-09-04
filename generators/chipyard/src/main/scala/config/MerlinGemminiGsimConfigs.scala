package chipyard

import chisel3._
import chisel3.reflect.DataMirror

import org.chipsalliance.cde.config.{Config}

import freechips.rocketchip.subsystem.{BaseSubsystem}
import testchipip.serdes.{CanHavePeripheryTLSerial, SerialTLKey}

import chipyard.iobinders.{OverrideIOBinder, IOCellKey, SerialTLPort}
import chipyard.iocell.{IOCell}

// ---------------------------------------------------------------------------
// GSIM-friendly harness clocking for the Gemmini SoCs.
//
// GSIM (FIRRTL -> C++ simulator) can only drive a clock that is a genuine
// top-level input of the elaborated design.  The default Chipyard harness
// clocking (AbsoluteFreqHarnessClockInstantiator, see
// generators/chipyard/src/main/scala/harness/HarnessClocks.scala) *generates*
// every requested harness clock inside an unsynthesizable
// `ClockSourceAtFreqMHz` Verilog blackbox (`always #(PERIOD/2.0) ...`).  GSIM
// cannot model that blackbox: its AST2Graph pass claims the blackbox's
// Clock-typed *output* as the enclosing module's clock *input* without checking
// direction, which orphans the whole clock net and then panics in the
// splitArray pass.
//
// This instantiator instead drives every requested harness clock directly from
// the TestHarness reference clock (a real top-level input port), with no
// blackbox in between.  It is chipyard.harness.AllClocksFromHarnessClockInstantiator
// minus the per-domain `freqMHz == refClockFreqMHz` requirement: the Gemmini SoC
// asks for a 500 MHz uncore clock and a 100 MHz harness-binder clock, and the
// stock instantiator would `require`-fail on both.  Dropping the assertion makes
// the entire design one top-level-driven clock domain, which is what GSIM needs.
// Simulated frequency is meaningless under GSIM (it is a cycle-accurate FIRRTL
// interpreter, not a timing simulator), so collapsing the ratios is sound; the
// only knob it perturbs is the UART baud divider, which is derived from
// HarnessBinderClockFrequencyKey and therefore unchanged.
//
// This is the exact transformation that was previously applied by hand to the
// emitted .fir (rewriting the two `connect ..., source*.clk` lines in
// TestHarness to `connect ..., clock`); expressing it as a config makes the
// FIRRTL re-derivable instead of hand-edited bytes.
class GsimHarnessClockInstantiator extends chipyard.harness.HarnessClockInstantiator {
  def instantiateHarnessClocks(refClock: chisel3.Clock, refClockFreqMHz: Double): Unit = {
    for ((_, (_, clock)) <- clockMap) {
      clock := refClock
    }
  }
}

class WithGsimHarnessClockInstantiator extends Config((site, here, up) => {
  case chipyard.harness.HarnessClockInstantiatorKey => () => new GsimHarnessClockInstantiator
})

// A GSIM-clean variant of GemminiRocketConfig:
//  - WithoutTLMonitors strips the 284 TileLink protocol-checker monitors, which
//    are verification-only assertion logic and which the prior investigation
//    reported as tripping GSIM's splitArray pass with a cyclic-dependency panic.
//  - WithGsimHarnessClockInstantiator replaces the ClockSourceAtFreqMHz blackbox
//    clock generators with the top-level TestHarness clock input.
class GemminiGsimConfig extends Config(
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new WithGsimHarnessClockInstantiator ++
  new GemminiRocketConfig)

// Same clock fix, but keeping the TLMonitors.  This is the minimal delta from
// the stock GemminiRocketConfig and reproduces the known-good hand-edited .fir
// exactly (that artifact still contained all 284 monitors and compiled under
// GSIM), so it is the conservative fallback if the monitor-stripped variant
// regresses.
class GemminiGsimMonitorsConfig extends Config(
  new WithGsimHarnessClockInstantiator ++
  new GemminiRocketConfig)

// ---------------------------------------------------------------------------
// GSIM-friendly serial-TL clocking for the Gemmini SoCs.
//
// The chip-side serial-TL clock is an *input pin* of the SoC: the harness's
// SimTSI adapter drives `serial_tl_0.clock_in` (chipyard.harness.
// WithSimTSIOverSerialTL), and the whole serial-TL domain -- the serdesser, the
// phy, and the reset synchronizers built on top of it -- runs off it.  The
// default IOBinder for that port, chipyard.iobinders.WithSerialTLIOCells, wraps
// *every* leaf of the port bundle in a pad cell, `clock_in` included, so the
// clock the SoC actually sees is the `i` output of a `GenericDigitalInIOCell`
// extmodule.
//
// GSIM cannot see through that extmodule.  It treats a clock net driven by a
// blackbox *output* as a constant, so the serial-TL domain never ticks: the
// serdesser stays frozen (measured on the hand-patched elaboration: beat=0,
// head=0, tail=0 at 200k cycles), the MSIP write to the CLINT never lands, and
// the core sits in the bootrom `wfi` forever.  Bypassing the pad cell on the
// clock path alone drops GSIM's constant-clock count from 8 to 5 and the design
// boots.
//
// This binder is chipyard.iobinders.WithSerialTLIOCells with exactly one
// change: a Clock-typed leaf of the serial-TL port bundle is wired straight
// through between the ChipTop pin and the SoC, and every other leaf still gets
// its pad cell from `p(IOCellKey)`, under the same instance name it had before
// (IOCell's own Record recursion names a field's cells `iocell_<port>_<field>`,
// which is what the per-element call below reproduces).  The ChipTop port
// bundle is unchanged in type, name and direction, so both harness binders that
// bind to it -- WithSimTSIOverSerialTL and WithSerialTLTiedOff -- are unaffected,
// and the data path (`in`, `out`, and their ready/valid/phit fields) keeps every
// pad cell it had.  Only a Clock leaf is treated specially, so this is inert on
// a phy whose port carries no clock.
//
// Like WithGsimHarnessClockInstantiator above, this removes a blackbox from a
// clock path and touches nothing else; it is a simulation-model config, not a
// physical-design one (a real tapeout wants the pad cell and must not use it).
// It is the transformation that was previously applied by hand to the emitted
// .fir -- rewriting `connect system.serial_tl_0.clock_in,
// _system_serial_tl_0_clock_in_T` to `connect system.serial_tl_0.clock_in,
// serial_tl_0.clock_in` -- and expressing it as a config makes the FIRRTL
// re-derivable instead of hand-edited bytes.  The one visible difference from
// that hand edit is that the bypassed pad cell is not instantiated at all,
// rather than left instantiated with its output dangling.
class WithGsimSerialTLClockPunchthrough extends OverrideIOBinder({
  (system: CanHavePeripheryTLSerial) => {
    val sys = system.asInstanceOf[BaseSubsystem]
    val typeParams = sys.p(IOCellKey)
    val (ports, cells) = system.serial_tls.zipWithIndex.map({ case (s, id) =>
      val portName = s"serial_tl_$id"
      val core: Bundle = s.getWrappedValue
      val pad: Bundle = IO(DataMirror.internal.chiselTypeClone[Bundle](core)).suggestName(portName)
      val portCells = core.elements.foldLeft(Seq.empty[IOCell]) { case (acc, (eltName, coreElt)) =>
        val padElt = pad.elements(eltName)
        (coreElt, padElt) match {
          // The one deviation from WithSerialTLIOCells: no pad cell on a clock.
          case (coreClock: Clock, padClock: Clock) =>
            DataMirror.directionOf(coreClock) match {
              case ActualDirection.Input  => coreClock := padClock
              case ActualDirection.Output => padClock := coreClock
              case d => throw new Exception(s"$portName.$eltName is a Clock with unresolved direction $d")
            }
            acc
          case _ =>
            acc ++ IOCell.generateFromSignal(
              coreElt, padElt, Some(s"iocell_${portName}_${eltName}"), typeParams, IOCell.toAsyncReset)
        }
      }
      (SerialTLPort(() => pad, sys.p(SerialTLKey)(id), system.serdessers(id), id), portCells)
    }).unzip
    (ports.toSeq, cells.flatten.toSeq)
  }
})

// GemminiGsimMonitorsConfig plus the serial-TL clock bypass.  This is the config
// that reproduces the known-good hand-edited GSIM model with no hand editing:
// WithGsimHarnessClockInstantiator takes the harness clocks off the
// ClockSourceAtFreqMHz blackbox, WithGsimSerialTLClockPunchthrough takes the
// chip's serial-TL clock off the pad-cell blackbox, and nothing else about
// GemminiRocketConfig changes.
class GemminiGsimSerialClkConfig extends Config(
  new WithGsimSerialTLClockPunchthrough ++
  new GemminiGsimMonitorsConfig)
