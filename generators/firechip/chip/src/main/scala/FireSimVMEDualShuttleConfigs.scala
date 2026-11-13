package firechip.chip

import org.chipsalliance.cde.config.Config

class FireSimVMEV256D128DualShuttleConfig extends Config(
  new freechips.rocketchip.subsystem.WithExtMemSize(1L << 34) ++
  new WithDefaultFireSimBridges ++
  new WithFireSimConfigTweaks ++
  new chipyard.VMEV256D128DualShuttleConfig
)
