#!/usr/bin/env bash
# Produce the final IV.3 summary tables + plots used in CASE_STUDY-FUZZING.md.
#
# Usage: summary.sh <primary-results-dir> <crossover-results-dir>
set -euo pipefail

cd "$(dirname "$0")/.."

PRIMARY="${1:-results/primary-w50-3rep-5min}"
CROSSOVER="${2:-results/crossover-180s}"

echo "=== PRIMARY: $PRIMARY ==="
python3 scripts/aggregate.py "$PRIMARY" | tee "$PRIMARY/SUMMARY.md"
python3 scripts/plot.py "$PRIMARY" 50 || true

echo
echo "=== CROSSOVER: $CROSSOVER ==="
python3 scripts/aggregate.py "$CROSSOVER" | tee "$CROSSOVER/SUMMARY.md"
for w in 1 10 30; do
    python3 scripts/plot.py "$CROSSOVER" "$w" || true
done

echo
echo "Summary tables written to:"
echo "  $PRIMARY/SUMMARY.md"
echo "  $CROSSOVER/SUMMARY.md"
echo "Plots in $PRIMARY/ and $CROSSOVER/"
