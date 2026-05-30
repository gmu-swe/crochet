#!/usr/bin/env bash
# Thin wrapper: run baseline_shared at the configured budget/iters/seed.
set -euo pipefail
BUDGET_SEC="${BUDGET_SEC:-300}"
SEED="${SEED:-107}"
WIDGET_INIT_ITERS="${WIDGET_INIT_ITERS:-50}"
OUTDIR="${OUTDIR:-results/shared-$(date +%Y%m%d-%H%M%S)}"
bash "$(dirname "$0")/run-one.sh" baseline_shared "$BUDGET_SEC" "$SEED" \
    "$WIDGET_INIT_ITERS" "$OUTDIR"
