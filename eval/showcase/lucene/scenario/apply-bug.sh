#!/usr/bin/env bash
# apply-bug.sh — apply (or unapply) the synthetic subtraction-comparator bug
# to the Lucene 9.11.0 source tree.
#
# Usage:
#   bash apply-bug.sh [--lucene-src DIR] [--revert]
#
# Default LUCENE_SRC: /tmp/lucene-9.11.0

set -euo pipefail

LUCENE_SRC="${LUCENE_SRC:-/tmp/lucene-9.11.0}"
REVERT=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --lucene-src) LUCENE_SRC="$2"; shift 2 ;;
    --revert)     REVERT=1; shift ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

TARGET="${LUCENE_SRC}/lucene/core/src/java/org/apache/lucene/index/IndexSorter.java"

if [[ ! -f "$TARGET" ]]; then
  echo "ERROR: IndexSorter.java not found at $TARGET"
  echo "Set LUCENE_SRC or pass --lucene-src."
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH="${SCRIPT_DIR}/patches/subtraction-comparator-bug.patch"

if [[ $REVERT -eq 1 ]]; then
  echo "[apply-bug.sh] Reverting bug patch..."
  patch -R -p1 -d "${LUCENE_SRC}" < "$PATCH"
  echo "[apply-bug.sh] Reverted."
else
  echo "[apply-bug.sh] Applying bug patch to ${TARGET}..."
  patch -p1 -d "${LUCENE_SRC}" < "$PATCH"
  echo "[apply-bug.sh] Done.  IndexSorter.IntSorter.getDocComparator() now uses subtraction."
fi
