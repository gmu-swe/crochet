#!/usr/bin/env bash
# Replicated three-mode sweep — fork, no-fork, crochet — N runs each.
#
# Args:
#   $1 — runs (default 3)
set -eu
cd "$(dirname "$0")/.."
RUNS="${1:-3}"

# Smoke / single-target run
for i in $(seq 1 "$RUNS"); do
    echo "=== run $i / $RUNS ==="
    bash scripts/run-baseline-fork.sh r${i}
    bash scripts/run-baseline-nofork.sh r${i}
    bash scripts/run-crochet.sh r${i}
done

bash scripts/aggregate.sh
