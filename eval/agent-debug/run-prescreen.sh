#!/usr/bin/env bash
# eval/agent-debug/run-prescreen.sh — Phase II prescreen: C1 on all 25 candidates × 2 seeds
#
# Usage:
#   bash eval/agent-debug/run-prescreen.sh [--jobs N] [--timeout N] [--dry-run]
#
# Options:
#   --jobs N       Max concurrent jobs (default: 3)
#   --timeout N    Per-trial timeout in seconds (default: 600)
#   --dry-run      Verify checkout/compile only, skip agent
#
# Environment:
#   JAVA_HOME         Path to JDK (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   DEFECTS4J_HOME    Path to defects4j (default: ~/defects4j)
#
# Output:
#   eval/agent-debug/prescreen-results/<bug>-c1-seed<N>.json for each trial

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Ensure the prescreen runs from a stable cwd that won't be cleaned up.
# The trial workdirs under /tmp get rm-rf'd by run-trial.sh's cleanup trap.
# If the parent shell's cwd is inside one of those (or any other transient dir),
# subsequent trials inherit a deleted cwd and defects4j's `java -version` parsing
# fails with "shell-init: error retrieving current directory: getcwd: cannot access
# parent directories". Pin to $HOME (or /tmp which always exists) to avoid this.
cd "$HOME" || cd /tmp
CANDIDATES_JSON="$SCRIPT_DIR/candidates.json"
RESULTS_DIR="$SCRIPT_DIR/prescreen-results"
TRIAL_SCRIPT="$SCRIPT_DIR/run-trial.sh"

MAX_JOBS=3
TRIAL_TIMEOUT=600
DRY_RUN_FLAG=""

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export DEFECTS4J_HOME="${DEFECTS4J_HOME:-$HOME/defects4j}"

# Point run-trial.sh at candidates.json (which has the 'candidates' top-level key)
export CORPUS_JSON="$CANDIDATES_JSON"

# Use main repo for crochet artifacts if available
MAIN_CROCHET_REPO="${MAIN_CROCHET_REPO:-$HOME/crochet}"
if [[ -f "$MAIN_CROCHET_REPO/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar" ]]; then
    export CROCHET_REPO="$MAIN_CROCHET_REPO"
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jobs)    MAX_JOBS="$2";    shift 2 ;;
        --timeout) TRIAL_TIMEOUT="$2"; shift 2 ;;
        --dry-run) DRY_RUN_FLAG="--dry-run"; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

log() { echo "[prescreen] $(date '+%H:%M:%S') $*" >&2; }

mkdir -p "$RESULTS_DIR"

# ── Extract bug IDs from candidates.json ─────────────────────────────────────
readarray -t BUG_IDS < <(python3 -c "
import json
with open('$CANDIDATES_JSON') as f:
    corpus = json.load(f)
for bug in corpus.get('candidates', corpus.get('bugs', [])):
    print(bug['id'])
")

SEEDS=(1 2)

log "Prescreen: ${#BUG_IDS[@]} candidates × ${#SEEDS[@]} seeds = $(( ${#BUG_IDS[@]} * ${#SEEDS[@]} )) C1 trials"
log "Max concurrent: $MAX_JOBS | Per-trial timeout: ${TRIAL_TIMEOUT}s"
log "Results dir: $RESULTS_DIR"
log "Candidates: ${BUG_IDS[*]}"

# ── Trial runner helper ───────────────────────────────────────────────────────
run_trial() {
    local bug="$1"
    local seed="$2"
    local out_file="$RESULTS_DIR/${bug}-c1-seed${seed}.json"

    # Skip if already completed successfully
    if [[ -f "$out_file" ]]; then
        local already_done
        already_done=$(python3 -c "
import json
try:
    obj = json.load(open('$out_file'))
    if 'test_pass' in obj and not obj.get('timeout') and not obj.get('harness_error'):
        print('yes')
    else:
        print('no')
except Exception:
    print('no')
" 2>/dev/null || echo "no")
        if [[ "$already_done" == "yes" ]]; then
            log "  SKIP (already done): $bug seed=$seed"
            return 0
        fi
    fi

    log "  START: $bug seed=$seed → $out_file"
    local trial_start
    trial_start=$(date +%s)

    # Always run the trial from a stable cwd ($HOME) so that defects4j's
    # `java -version` parsing works.  Without this, the child inherits the
    # parent's cwd which may have been rm-rf'd by an earlier trial's cleanup.
    # set +o pipefail so we can capture the timeout/bash exit code (PIPESTATUS[0])
    # rather than the always-zero exit of the `while read` consumer.
    local exit_code=0
    set +o pipefail
    (cd "$HOME" && timeout "$TRIAL_TIMEOUT" bash "$TRIAL_SCRIPT" \
        --bug "$bug" \
        --condition C1 \
        --out "$out_file" \
        --seed "$seed" \
        --workdir "/tmp/prescreen-${bug}-seed${seed}" \
        $DRY_RUN_FLAG \
        2>&1) | while IFS= read -r line; do
            echo "[prescreen/$bug/seed$seed] $line" >&2
        done
    exit_code=${PIPESTATUS[0]}
    set -o pipefail

    local trial_end
    trial_end=$(date +%s)
    local duration=$(( trial_end - trial_start ))

    if [[ $exit_code -eq 124 ]]; then
        log "  TIMEOUT: $bug seed=$seed after ${duration}s"
        python3 -c "
import json, datetime
result = {
    'bug': '$bug',
    'condition': 'C1',
    'seed': $seed,
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
"
    elif [[ $exit_code -ne 0 && ! -f "$out_file" ]]; then
        log "  HARNESS ERROR (exit $exit_code): $bug seed=$seed"
        python3 -c "
import json, datetime
result = {
    'bug': '$bug',
    'condition': 'C1',
    'seed': $seed,
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
"
    else
        local pass_status
        pass_status=$(python3 -c "
import json
try:
    obj = json.load(open('$out_file'))
    print('PASS' if obj.get('test_pass') else 'FAIL')
except Exception:
    print('?')
" 2>/dev/null || echo "?")
        log "  DONE: $bug seed=$seed in ${duration}s → $pass_status"
    fi
}

export -f run_trial log
export RESULTS_DIR TRIAL_SCRIPT TRIAL_TIMEOUT DRY_RUN_FLAG CORPUS_JSON

# ── Generate all (bug, seed) pairs ───────────────────────────────────────────
declare -a TRIAL_PAIRS=()
for bug in "${BUG_IDS[@]}"; do
    for seed in "${SEEDS[@]}"; do
        TRIAL_PAIRS+=("$bug $seed")
    done
done

log "Total trials: ${#TRIAL_PAIRS[@]}"

# ── Run with job-control parallelism ─────────────────────────────────────────
ACTIVE_JOBS=0
declare -A JOB_PIDS=()
COMPLETED=0

for pair in "${TRIAL_PAIRS[@]}"; do
    bug=$(echo "$pair" | cut -d' ' -f1)
    seed=$(echo "$pair" | cut -d' ' -f2)

    # Wait if at max jobs
    while [[ $ACTIVE_JOBS -ge $MAX_JOBS ]]; do
        wait -n 2>/dev/null || sleep 2
        ACTIVE_JOBS=0
        for pid in "${!JOB_PIDS[@]}"; do
            if kill -0 "$pid" 2>/dev/null; then
                ACTIVE_JOBS=$(( ACTIVE_JOBS + 1 ))
            else
                pair_done="${JOB_PIDS[$pid]}"
                unset "JOB_PIDS[$pid]"
                COMPLETED=$(( COMPLETED + 1 ))
                log "Completed $COMPLETED/${#TRIAL_PAIRS[@]}: $pair_done"
            fi
        done
    done

    run_trial "$bug" "$seed" &
    pid=$!
    JOB_PIDS[$pid]="$bug seed=$seed"
    ACTIVE_JOBS=$(( ACTIVE_JOBS + 1 ))
    log "Launched PID $pid: $bug seed=$seed (active: $ACTIVE_JOBS)"
done

log "Waiting for ${#JOB_PIDS[@]} remaining jobs..."
for pid in "${!JOB_PIDS[@]}"; do
    wait "$pid" || true
    COMPLETED=$(( COMPLETED + 1 ))
    log "Completed $COMPLETED/${#TRIAL_PAIRS[@]}: ${JOB_PIDS[$pid]}"
done

log "All prescreen trials finished."

# ── Summarize results ─────────────────────────────────────────────────────────
python3 -c "
import json, os, glob

results_dir = '$RESULTS_DIR'
candidates_file = '$CANDIDATES_JSON'

with open(candidates_file) as f:
    corpus = json.load(f)
bug_list = corpus.get('candidates', corpus.get('bugs', []))
bug_ids = [b['id'] for b in bug_list]

# Collect results per bug
per_bug = {}
for bid in bug_ids:
    per_bug[bid] = {'passes': 0, 'total': 0, 'results': []}

result_files = sorted(glob.glob(os.path.join(results_dir, '*-c1-seed*.json')))
for path in result_files:
    try:
        obj = json.load(open(path))
        bid = obj.get('bug', '')
        if bid in per_bug:
            per_bug[bid]['total'] += 1
            if obj.get('test_pass'):
                per_bug[bid]['passes'] += 1
            per_bug[bid]['results'].append({
                'seed': obj.get('seed', '?'),
                'test_pass': obj.get('test_pass', False),
                'timeout': obj.get('timeout', False),
                'harness_error': obj.get('harness_error', ''),
                'setup_error': obj.get('setup_error', ''),
                'duration_seconds': obj.get('duration_seconds', 0),
            })
    except Exception as e:
        print(f'WARNING: Could not parse {path}: {e}')

print()
print('Prescreen Summary (C1 × 2 seeds):')
print(f'{\"Bug\":<25} {\"Passes\":>8} {\"Rate\":>8}  Notes')
print('-' * 60)

dist = {0: [], 0.5: [], 1.0: []}
for bid in bug_ids:
    d = per_bug[bid]
    if d['total'] == 0:
        rate_str = '  PEND'
        rate = -1
    else:
        rate = d['passes'] / d['total']
        rate_str = f'{d[\"passes\"]}/{d[\"total\"]}'

    notes = []
    for r in d['results']:
        if r['timeout']:
            notes.append(f'seed{r[\"seed\"]}:TIMEOUT')
        elif r['harness_error']:
            notes.append(f'seed{r[\"seed\"]}:ERR')
        elif r['setup_error']:
            notes.append(f'seed{r[\"seed\"]}:SETUP_ERR')

    print(f'{bid:<25} {rate_str:>8} {(str(round(rate,2)) if rate >= 0 else \"?\"):>8}  {\" \".join(notes)}')

    if rate >= 0:
        bucket = round(rate * 2) / 2
        dist[bucket].append(bid)

print()
print('Distribution:')
print(f'  0/2 (rate=0.0): {len(dist[0])} bugs — {dist[0]}')
print(f'  1/2 (rate=0.5): {len(dist[0.5])} bugs — {dist[0.5]}')
print(f'  2/2 (rate=1.0): {len(dist[1.0])} bugs — {dist[1.0]}')

hard = [b for b in bug_ids if per_bug[b]['total'] > 0 and per_bug[b]['passes'] / per_bug[b]['total'] <= 0.5]
print()
print(f'Hard bugs (rate <= 0.5): {len(hard)}')
for bid in sorted(hard, key=lambda b: per_bug[b]['passes'] / per_bug[b]['total']):
    d = per_bug[bid]
    rate = d['passes'] / d['total']
    print(f'  {bid}: {d[\"passes\"]}/{d[\"total\"]} ({rate:.1f})')
"

log "Prescreen complete."
