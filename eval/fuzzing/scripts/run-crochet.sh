#!/usr/bin/env bash
# Thin wrapper: run crochet_scoped (default) or crochet_rollback at the
# configured budget/iters/seed.
set -euo pipefail
BUDGET_SEC="${BUDGET_SEC:-300}"
SEED="${SEED:-107}"
WIDGET_INIT_ITERS="${WIDGET_INIT_ITERS:-50}"
MODE="${MODE:-crochet_scoped}"  # or crochet_rollback
OUTDIR="${OUTDIR:-results/${MODE}-$(date +%Y%m%d-%H%M%S)}"
bash "$(dirname "$0")/run-one.sh" "$MODE" "$BUDGET_SEC" "$SEED" \
    "$WIDGET_INIT_ITERS" "$OUTDIR"
