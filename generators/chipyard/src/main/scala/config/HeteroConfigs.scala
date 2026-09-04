package chipyard

import org.chipsalliance.cde.config.{Config}

// ---------------------
// Heterogenous Configs
// ---------------------

class LargeBoomAndRocketConfig extends Config(
  new boom.v3.common.WithNLargeBooms(1) ++                    // single-core boom
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++         // single rocket-core
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class DualLargeBoomAndDualRocketConfig extends Config(
  new boom.v3.common.WithNLargeBooms(2) ++             // add 2 boom cores
  new freechips.rocketchip.rocket.WithNHugeCores(2) ++  // add 2 rocket cores
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

// DOC include start: DualBoomAndSingleRocket
class DualLargeBoomAndSingleRocketConfig extends Config(
  new boom.v3.common.WithNLargeBooms(2) ++             // add 2 boom cores
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++  // add 1 rocket core
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)
// DOC include end: DualBoomAndSingleRocket

class LargeBoomAndRocketWithControlCoreConfig extends Config(
  new freechips.rocketchip.rocket.WithNSmallCores(1) ++    // Add a small "control" core
  new boom.v3.common.WithNLargeBooms(1) ++                 // Add 1 boom core
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++      // add 1 rocket core
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

// Heterogeneous 2-Shuttle SoC: tile 0 = Shuttle + Gemmini RoCC; tile 1 = Shuttle + Saturn OPU vector unit (vLen=128)
class GemminiAndOPUShuttleConfig extends Config(
  new saturn.shuttle.WithShuttleVectorUnit(
    vLen = 128, dLen = 64,
    params = saturn.common.VectorParams.opuParams,
    cores = Some(Seq(1))) ++                                  // OPU only on tile 1
  new chipyard.config.WithMultiRoCCGemmini(0)(gemmini.GemminiConfigs.defaultConfig) ++  // Default Gemmini (full systolic array, FP scaler) only on tile 0
  new chipyard.config.WithMultiRoCC ++                         // enable per-tile RoCC dispatch
  new shuttle.common.WithNShuttleCores(2) ++                   // 2 Shuttle tiles -> tileIds 0,1
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)
