#!/usr/bin/env bash
# Secondary IV.3 sweep: characterise the setup-vs-rollback crossover by
# running every mode at several WIDGET_INIT_ITERS values, briefly. Smaller
# budget per cell, single replication — this is for the qualitative
# crossover curve, not the headline-table numbers.
set -euo pipefail

cd "$(dirname "$0")/.."

BUDGET_SEC="${BUDGET_SEC:-180}"
REPS="${REPS:-1}"
ITER_LEVELS="${ITER_LEVELS:-1 5 15 30}"
MODES="${MODES:-baseline_perIter baseline_shared crochet_scoped crochet_rollback}"
RUN_TAG="${RUN_TAG:-crossover-$(date +%H%M)}"

BUDGET_SEC=$BUDGET_SEC REPS=$REPS ITER_LEVELS="$ITER_LEVELS" MODES="$MODES" \
    RUN_TAG="$RUN_TAG" bash scripts/run-all.sh
