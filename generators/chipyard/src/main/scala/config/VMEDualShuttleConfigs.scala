package chipyard

import org.chipsalliance.cde.config.Config
import saturn.common.VectorParams

class VMEV256D128DualShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(256, 128, VectorParams.opuParams) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(2) ++
  new chipyard.config.AbstractConfig
)

// Same as VMEV256D128DualShuttleConfig, but the OPU also supports FP8 (OFP8 E4M3/E5M2)
// matrix ops (Saturn useMxOPU, ISA extension xsfmm32a8f).
class VMEV256D128DualShuttleMxConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(256, 128, VectorParams.opuMxParams) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(2) ++
  new chipyard.config.AbstractConfig
)
