#!/usr/bin/env bash
# Source this (NOT execute) to prepare a shell for autocomp MX-Gemmini runs:
#   source /scratch/agustin/projects/chipyard-mx/autocomp-env.sh
#
# Sets: chipyard toolchain (spike on PATH), Bedrock bearer token + W&B key,
# region, the persistent project spend log, the $100 HARD spend cap, and the
# autocomp venv.

# 1) chipyard toolchain (spike, riscv gcc, $RISCV) — needed by autocomp eval
source /scratch/agustin/projects/chipyard-mx/env.sh

# 2) credentials: AWS_BEARER_TOKEN_BEDROCK (Bedrock) + WANDB_API_KEY (auto-login)
set -a; source /scratch2/agustin/ModelBlaster/.env; set +a

# 3) Bedrock region (Sonnet 4.6 access is provisioned here)
export AWS_REGION=us-west-2

# 3b) Price multiplier. The us.anthropic.claude-sonnet-4-6 GEOGRAPHIC profile
# bills at STANDARD on-demand price (no cross-region surcharge per AWS docs), so
# 1.0 is exact. Use 0.9 only if switching to a "global." profile (~10% cheaper).
export AUTOCOMP_BEDROCK_REGION_MULTIPLIER=1.0

# 4) persistent lifetime spend ledger for THIS project
export AUTOCOMP_SPEND_LOG=/scratch/agustin/projects/chipyard-mx/.autocomp-spend.jsonl

# 5) HARD spend cap — abort the next LLM call once lifetime spend hits the limit.
#    mode=stop makes it enforced (not just a warning). Raised 100->200 on 2026-06-05.
#    NOTE: this is the USD spend cap only. The 2026-06-05 search was throttled by the
#    Bedrock *daily token-per-day* account quota (ThrottlingException "Too many tokens
#    per day"), which is a SEPARATE AWS service limit this cap does not control — it
#    resets daily or needs an AWS service-quota increase.
export AUTOCOMP_SPEND_LIMIT_USD=200
export AUTOCOMP_SPEND_LIMIT_MODE=stop

# 6) autocomp's isolated venv (pydantic2; separate from chipyard's pydantic1)
source /scratch/agustin/projects/autocomp/.venv/bin/activate

echo "autocomp env ready | region=$AWS_REGION | spend cap=\$$AUTOCOMP_SPEND_LIMIT_USD ($AUTOCOMP_SPEND_LIMIT_MODE)"
echo "  spend log: $AUTOCOMP_SPEND_LOG"
echo "  check total anytime:  python -m autocomp.common.cost --total"
