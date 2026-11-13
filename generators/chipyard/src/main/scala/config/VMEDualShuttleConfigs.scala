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
