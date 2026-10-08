#!/bin/bash
set -e
cd /scratch2/agustin/wt/chipyard-stock/sims/firesim
source sourceme-manager.sh --skip-ssh-setup
export FS_DIR=/scratch2/agustin/wt/chipyard-stock/sims/firesim
export CY_DIR=/scratch2/agustin/wt/chipyard-stock
cd sim
make driver \
  TARGET_PROJECT=firesim \
  TARGET_PROJECT_MAKEFRAG=/scratch2/agustin/wt/chipyard-stock/generators/firechip/chip/src/main/makefrag/firesim \
  DESIGN=FireSim \
  DESIGN_PACKAGE=firechip.chip \
  TARGET_CONFIG=FireSimGemminiRocketConfig \
  TARGET_CONFIG_PACKAGE=firechip.chip \
  PLATFORM_CONFIG=BaseXilinxAlveoU250Config \
  PLATFORM_CONFIG_PACKAGE=firechip.chip \
  PLATFORM=xilinx_alveo_u250
echo "BUILD_DRIVER_EXIT_CODE=$?"
