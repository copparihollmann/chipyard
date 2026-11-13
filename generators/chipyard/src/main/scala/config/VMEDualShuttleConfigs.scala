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

// Hart 0: Saturn with the MX OPU (INT8 + FP8 matrix ops, xsfmm32a8f); hart 1: Saturn with the INT8 OPU.
// Otherwise identical to VMEV256D128DualShuttleConfig. Uses WithShuttleVectorUnit(cores = Some(Seq(tileId))).
class VMEV256D128ShuttleMxH0Config extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(256, 128, VectorParams.opuMxParams, cores = Some(Seq(0))) ++
  new saturn.shuttle.WithShuttleVectorUnit(256, 128, VectorParams.opuParams, cores = Some(Seq(1))) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(2) ++
  new chipyard.config.AbstractConfig
)
