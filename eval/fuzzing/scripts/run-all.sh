#!/usr/bin/env bash
# Full IV.3 benchmark sweep. Runs each of the four modes (baseline_perIter,
# baseline_shared, crochet_scoped, crochet_rollback) with N reps at the
# configured budget and one or more WIDGET_INIT_ITERS values.
#
# Env overrides:
#   BUDGET_SEC      — per-run budget (default 600 = 10 min)
#   REPS            — replications per mode (default 3)
#   ITER_LEVELS     — space-separated init-iter values (default "1 10 50")
#   MODES           — modes to run (default all four)
#   OUTDIR_BASE     — output base directory (default eval/fuzzing/results)
#   RUN_TAG         — subdirectory under OUTDIR_BASE (default ts-named)
set -euo pipefail

cd "$(dirname "$0")/.."

BUDGET_SEC="${BUDGET_SEC:-600}"
REPS="${REPS:-3}"
ITER_LEVELS="${ITER_LEVELS:-50}"
MODES="${MODES:-baseline_perIter baseline_shared crochet_scoped crochet_rollback}"
OUTDIR_BASE="${OUTDIR_BASE:-results}"
RUN_TAG="${RUN_TAG:-$(date +%Y%m%d-%H%M%S)}"

OUTDIR="$OUTDIR_BASE/$RUN_TAG"
mkdir -p "$OUTDIR"

# Persist run parameters for reproduction.
cat > "$OUTDIR/RUN_PARAMS.txt" <<EOF
BUDGET_SEC=$BUDGET_SEC
REPS=$REPS
ITER_LEVELS=$ITER_LEVELS
MODES=$MODES
EOF

echo "==> Run output: $OUTDIR"
echo "==> Budget=${BUDGET_SEC}s reps=$REPS iter_levels=$ITER_LEVELS"

START=$(date +%s)
for iters in $ITER_LEVELS; do
    for mode in $MODES; do
        for rep in $(seq 1 "$REPS"); do
            SEED=$((100 * rep + 7))
            echo "==> [$(date +%H:%M:%S)] mode=$mode iters=$iters rep=$rep seed=$SEED"
            bash scripts/run-one.sh "$mode" "$BUDGET_SEC" "$SEED" "$iters" "$OUTDIR"
        done
    done
done
END=$(date +%s)
echo "==> Total wall time: $((END - START))s ($(( (END - START) / 60 )) min)"
echo "==> Aggregate with: python3 scripts/aggregate.py $OUTDIR"
