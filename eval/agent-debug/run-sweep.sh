#!/usr/bin/env bash
# eval/agent-debug/run-sweep.sh — Run all 33 trials (11 bugs × 3 conditions)
#
# Usage:
#   bash eval/agent-debug/run-sweep.sh [--dry-run] [--jobs N]
#
# Options:
#   --dry-run    Pass --dry-run to each trial (verify setup only, no agent)
#   --jobs N     Max concurrent jobs (default: 3)
#   --timeout N  Per-trial timeout in seconds (default: 600)
#
# Environment:
#   JAVA_HOME    JDK path (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   DEFECTS4J_HOME  Path to defects4j checkout (default: ~/defects4j)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_JSON="$SCRIPT_DIR/corpus.json"
RESULTS_DIR="$SCRIPT_DIR/results"
TRIAL_SCRIPT="$SCRIPT_DIR/run-trial.sh"

# Defaults
MAX_JOBS=3
TRIAL_TIMEOUT=600
DRY_RUN_FLAG=""

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export DEFECTS4J_HOME="${DEFECTS4J_HOME:-$HOME/defects4j}"

# Use the main repo for crochet artifacts (jars are built there, not in worktree).
# run-trial.sh auto-detects CROCHET_REPO from its own script location; we override
# it to point to the main repo where crochet-agent and crochet-debug targets exist.
MAIN_CROCHET_REPO="${MAIN_CROCHET_REPO:-$HOME/crochet}"
if [[ -f "$MAIN_CROCHET_REPO/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar" ]]; then
    export CROCHET_REPO="$MAIN_CROCHET_REPO"
fi

# Parse args
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN_FLAG="--dry-run"; shift ;;
        --jobs)    MAX_JOBS="$2"; shift 2 ;;
        --timeout) TRIAL_TIMEOUT="$2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

log() { echo "[run-sweep] $(date '+%H:%M:%S') $*" >&2; }

mkdir -p "$RESULTS_DIR"

# ── Extract bug IDs from corpus.json ─────────────────────────────────────────
readarray -t BUG_IDS < <(python3 -c "
import json
with open('$CORPUS_JSON') as f:
    corpus = json.load(f)
for bug in corpus['bugs']:
    print(bug['id'])
")

CONDITIONS=(C1 C2 C3)
SWEEP_START=$(date +%s)

log "Starting sweep: ${#BUG_IDS[@]} bugs × ${#CONDITIONS[@]} conditions = $(( ${#BUG_IDS[@]} * ${#CONDITIONS[@]} )) trials"
log "Max concurrent: $MAX_JOBS | Per-trial timeout: ${TRIAL_TIMEOUT}s"
log "Results dir: $RESULTS_DIR"
log "Bugs: ${BUG_IDS[*]}"

# ── Trial runner helper ───────────────────────────────────────────────────────
# Runs a single trial with timeout; writes error JSON on failure/timeout.
run_trial() {
    local bug="$1"
    local condition="$2"
    local out_file="$RESULTS_DIR/${bug}-${condition}.json"

    # Skip if already completed (allows resuming interrupted sweeps)
    if [[ -f "$out_file" ]]; then
        local already_done
        already_done=$(python3 -c "
import json
try:
    obj = json.load(open('$out_file'))
    # Valid result if it has test_pass field and no timeout/harness_error
    if 'test_pass' in obj and not obj.get('timeout') and not obj.get('harness_error'):
        print('yes')
    else:
        print('no')
except Exception:
    print('no')
" 2>/dev/null || echo "no")
        if [[ "$already_done" == "yes" ]]; then
            log "  SKIP (already done): $bug × $condition"
            return 0
        fi
    fi

    log "  START: $bug × $condition → $out_file"
    local trial_start
    trial_start=$(date +%s)

    # Run with timeout
    local exit_code=0
    timeout "$TRIAL_TIMEOUT" bash "$TRIAL_SCRIPT" \
        --bug "$bug" \
        --condition "$condition" \
        --out "$out_file" \
        --timeout "$TRIAL_TIMEOUT" \
        $DRY_RUN_FLAG \
        2>&1 | while IFS= read -r line; do
            echo "[sweep/$bug/$condition] $line" >&2
        done || exit_code=$?

    local trial_end
    trial_end=$(date +%s)
    local duration=$(( trial_end - trial_start ))

    if [[ $exit_code -eq 124 ]]; then
        # timeout killed it — write timeout JSON
        log "  TIMEOUT: $bug × $condition after ${duration}s"
        python3 -c "
import json, datetime
result = {
    'bug': '$bug',
    'condition': '$condition',
    'started_at': datetime.datetime.utcnow().isoformat() + 'Z',
    'duration_seconds': $duration,
    'tool_calls': 0,
    'test_pass': False,
    'timeout': True,
    'compile_fail': False,
    'primary_pass': False,
    'agent_induced_regressions': [],
    'regressed_tests': [],
    'diagnosis_quality': 0,
    'agent_exit_code': 124,
    'agent_patch': '',
    'agent_log': '',
    'judge_reasoning': 'Trial timed out after ${TRIAL_TIMEOUT}s'
}
with open('$out_file', 'w') as f:
    json.dump(result, f, indent=2)
print('[run-sweep] Timeout JSON written: $out_file')
"
    elif [[ $exit_code -ne 0 && ! -f "$out_file" ]]; then
        # harness error, no output file written
        log "  HARNESS ERROR (exit $exit_code): $bug × $condition"
        python3 -c "
import json, datetime
result = {
    'bug': '$bug',
    'condition': '$condition',
    'started_at': datetime.datetime.utcnow().isoformat() + 'Z',
    'duration_seconds': $duration,
    'tool_calls': 0,
    'test_pass': False,
    'harness_error': 'Trial script exited with code $exit_code',
    'compile_fail': False,
    'primary_pass': False,
    'agent_induced_regressions': [],
    'regressed_tests': [],
    'diagnosis_quality': 0,
    'agent_exit_code': $exit_code,
    'agent_patch': '',
    'agent_log': '',
    'judge_reasoning': 'Harness error: exit code $exit_code'
}
with open('$out_file', 'w') as f:
    json.dump(result, f, indent=2)
print('[run-sweep] Harness-error JSON written: $out_file')
"
    elif [[ $exit_code -ne 0 ]]; then
        log "  HARNESS WARNING (exit $exit_code): $bug × $condition — output file exists, continuing"
    else
        log "  DONE: $bug × $condition in ${duration}s"
    fi
}

export -f run_trial log
export RESULTS_DIR TRIAL_SCRIPT TRIAL_TIMEOUT DRY_RUN_FLAG

# ── Generate all 33 (bug, condition) pairs ────────────────────────────────────
declare -a TRIAL_PAIRS=()
for bug in "${BUG_IDS[@]}"; do
    for condition in "${CONDITIONS[@]}"; do
        TRIAL_PAIRS+=("$bug $condition")
    done
done

log "Total trials: ${#TRIAL_PAIRS[@]}"

# ── Run with job-control parallelism (fallback since GNU parallel not installed)
ACTIVE_JOBS=0
declare -A JOB_PIDS=()
COMPLETED=0
FAILED=0

push_and_commit() {
    # Incrementally push results after each batch
    local worktree_root
    worktree_root="$(cd "$SCRIPT_DIR/../.." && pwd)"
    (
        cd "$worktree_root"
        git add eval/agent-debug/results/ 2>/dev/null || true
        local count
        count=$(git diff --cached --name-only | wc -l)
        if [[ "$count" -gt 0 ]]; then
            git commit -m "feat(I.4): sweep results — incremental push ($(date '+%Y-%m-%d %H:%M'))" \
                --no-gpg-sign 2>/dev/null || true
            git push origin unit/I.4-trial-sweep 2>/dev/null || true
            log "  Incremental push: $count result file(s) committed"
        fi
    ) 2>&1 | while IFS= read -r line; do echo "[sweep/push] $line" >&2; done || true
}

BATCH_SIZE=0
for pair in "${TRIAL_PAIRS[@]}"; do
    bug=$(echo "$pair" | cut -d' ' -f1)
    condition=$(echo "$pair" | cut -d' ' -f2)

    # Wait if at max jobs
    while [[ $ACTIVE_JOBS -ge $MAX_JOBS ]]; do
        # Wait for any job to finish
        wait -n 2>/dev/null || {
            # wait -n not available on older bash; fall back to polling
            sleep 2
        }
        # Recount active jobs
        ACTIVE_JOBS=0
        for pid in "${!JOB_PIDS[@]}"; do
            if kill -0 "$pid" 2>/dev/null; then
                ACTIVE_JOBS=$(( ACTIVE_JOBS + 1 ))
            else
                pair_done="${JOB_PIDS[$pid]}"
                unset "JOB_PIDS[$pid]"
                COMPLETED=$(( COMPLETED + 1 ))
                BATCH_SIZE=$(( BATCH_SIZE + 1 ))
                log "Completed $COMPLETED/${#TRIAL_PAIRS[@]}: $pair_done"
            fi
        done
    done

    # Incremental push every 6 completions
    if [[ $BATCH_SIZE -ge 6 ]]; then
        push_and_commit
        BATCH_SIZE=0
    fi

    # Launch this trial in background
    run_trial "$bug" "$condition" &
    pid=$!
    JOB_PIDS[$pid]="$bug × $condition"
    ACTIVE_JOBS=$(( ACTIVE_JOBS + 1 ))
    log "Launched PID $pid: $bug × $condition (active jobs: $ACTIVE_JOBS)"
done

# Wait for all remaining jobs
log "Waiting for ${#JOB_PIDS[@]} remaining jobs..."
for pid in "${!JOB_PIDS[@]}"; do
    wait "$pid" || true
    pair_done="${JOB_PIDS[$pid]}"
    COMPLETED=$(( COMPLETED + 1 ))
    log "Completed $COMPLETED/${#TRIAL_PAIRS[@]}: $pair_done"
done

SWEEP_END=$(date +%s)
SWEEP_DURATION=$(( SWEEP_END - SWEEP_START ))
log "All trials finished in ${SWEEP_DURATION}s ($(( SWEEP_DURATION / 60 ))m)"

# ── Aggregate results into sweep-results.json ─────────────────────────────────
log "Aggregating results → $RESULTS_DIR/sweep-results.json ..."
python3 -c "
import json, os, glob

results_dir = '$RESULTS_DIR'
result_files = sorted(glob.glob(os.path.join(results_dir, '*.json')))
result_files = [f for f in result_files if os.path.basename(f) not in ('sweep-results.json',)]

all_results = []
for path in result_files:
    try:
        with open(path) as f:
            obj = json.load(f)
        all_results.append(obj)
    except Exception as e:
        print(f'  WARNING: Could not parse {path}: {e}')

with open(os.path.join(results_dir, 'sweep-results.json'), 'w') as f:
    json.dump(all_results, f, indent=2, default=str)
print(f'Aggregated {len(all_results)} trial results')
"

# ── Print summary table ───────────────────────────────────────────────────────
python3 << 'PYEOF'
import json, os, sys

results_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'results')
sweep_file = os.path.join(results_dir, 'sweep-results.json')

try:
    with open(sweep_file) as f:
        results = json.load(f)
except Exception as e:
    print(f"ERROR: Could not load sweep-results.json: {e}", file=sys.stderr)
    sys.exit(1)

# Index by (bug, condition)
index = {}
for r in results:
    key = (r.get('bug', '?'), r.get('condition', '?'))
    index[key] = r

# Load bug order from corpus
corpus_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'corpus.json')
with open(corpus_file) as f:
    corpus = json.load(f)
bug_ids = [b['id'] for b in corpus['bugs']]
conditions = ['C1', 'C2', 'C3']

def cell(r):
    if r is None:
        return 'MISS  '
    if r.get('timeout'):
        return 'TOUT  '
    if r.get('harness_error') or r.get('setup_error'):
        return 'ERR   '
    if r.get('compile_fail'):
        return 'CFAIL '
    return 'PASS  ' if r.get('test_pass') else 'FAIL  '

def cell_flag(r):
    if r is None:
        return ''
    if r.get('timeout'):
        return 't'
    if r.get('harness_error') or r.get('setup_error'):
        return 'e'
    if r.get('compile_fail'):
        return 'c'
    return ''

lines = []
header = f"| {'Bug':<11} | {'C1':^8} | {'C2':^8} | {'C3':^8} | Score |"
sep    = f"|{'-'*13}|{'-'*10}|{'-'*10}|{'-'*10}|{'-'*7}|"
lines.append(header)
lines.append(sep)

c1_pass = c2_pass = c3_pass = 0
anomalies = []

for bug in bug_ids:
    r1 = index.get((bug, 'C1'))
    r2 = index.get((bug, 'C2'))
    r3 = index.get((bug, 'C3'))

    p1 = r1.get('test_pass', False) if r1 else False
    p2 = r2.get('test_pass', False) if r2 else False
    p3 = r3.get('test_pass', False) if r3 else False

    c1_pass += int(p1)
    c2_pass += int(p2)
    c3_pass += int(p3)

    score = sum([p1, p2, p3])

    c1_str = ('PASS' if p1 else cell(r1).strip())
    c2_str = ('PASS' if p2 else cell(r2).strip())
    c3_str = ('PASS' if p3 else cell(r3).strip())

    flag = ''
    for r in [r1, r2, r3]:
        f = cell_flag(r)
        if f:
            flag += f

    row = f"| {bug:<11} | {c1_str:^8} | {c2_str:^8} | {c3_str:^8} | {score}/3   |"
    if flag:
        row += f"  [{flag}]"
    lines.append(row)

    # Anomaly: C3 worse than C1 (C1 pass, C3 fail)
    if p1 and not p3:
        anomalies.append(f"  {bug}: C1=PASS C3={cell(r3).strip()} — Crochet TTD underperforms baseline")

lines.append(sep)
total_row = f"| {'TOTAL':<11} | {c1_pass}/11{' ':4} | {c2_pass}/11{' ':4} | {c3_pass}/11{' ':4} | {'':5} |"
lines.append(total_row)

table = '\n'.join(lines)
print("\n" + table + "\n")
print(f"Wall-clock: {int('$SWEEP_DURATION')}s ({int('$SWEEP_DURATION')//60}m {int('$SWEEP_DURATION')%60}s)")

if anomalies:
    print("\nAnomalies (C3 underperforms C1):")
    for a in anomalies:
        print(a)
else:
    print("\nNo C3-underperforms-C1 anomalies detected.")

# Write sweep-summary.md
summary_path = os.path.join(results_dir, 'sweep-summary.md')
with open(summary_path, 'w') as f:
    f.write("# Sweep Summary — I.4 Trial Results\n\n")
    f.write(table + "\n\n")
    f.write(f"**Wall-clock:** {int('$SWEEP_DURATION')}s ({int('$SWEEP_DURATION')//60}m {int('$SWEEP_DURATION')%60}s)\n\n")
    f.write("## Legend\n")
    f.write("- PASS: test_pass=true (primary test passes, zero agent-induced regressions)\n")
    f.write("- FAIL: test_pass=false (primary test still failing)\n")
    f.write("- CFAIL: agent patch broke compilation\n")
    f.write("- TOUT: trial timed out (>600s)\n")
    f.write("- ERR: harness or setup error\n")
    f.write("- MISS: result file not found\n\n")
    f.write("## Footnote: compile_fail vs primary_fail\n")
    f.write("CFAIL = agent's patch introduced a compilation error (distinct from the test failing to pass).\n")
    f.write("FAIL without CFAIL = code compiled, but target test still fails.\n\n")
    if anomalies:
        f.write("## Anomalies\n")
        for a in anomalies:
            f.write(a.strip() + "\n")
print(f"\nSummary written to: {summary_path}")
PYEOF

# ── Final incremental push ─────────────────────────────────────────────────────
push_and_commit

log "Sweep complete."
