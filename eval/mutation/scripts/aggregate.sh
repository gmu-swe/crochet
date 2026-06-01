#!/usr/bin/env bash
# Aggregate JSON results into a markdown table.
set -eu
cd "$(dirname "$0")/.."
python3 scripts/aggregate.py
