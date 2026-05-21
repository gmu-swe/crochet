#!/usr/bin/env bash
# eval/agent-debug/run-sweep-hard.sh — Phase II hard-corpus sweep
#
# 12 bugs × {C1, C2, C3} × 1 seed = 36 trials, 900s per-trial timeout.
#
# Usage:
#   bash eval/agent-debug/run-sweep-hard.sh [--dry-run] [--jobs N] [--timeout N] [--model <id>]
#
# Options:
#   --dry-run      Pass --dry-run to each trial (verify setup only, no agent)
#   --jobs N       Max concurrent jobs (default: 3)
#   --timeout N    Per-trial timeout in seconds (default: 900)
#   --model <id>   Claude model ID (default: empty = CLI default = Opus 4.7).
#                  Examples: claude-sonnet-4-6, claude-haiku-4-5, claude-opus-4-7
#                  Results are written to results-hard-<short-id>/ (e.g. results-hard-sonnet-4-6/).
#                  Without --model, results go to results-hard/ (backward-compat).
#
# Environment:
#   JAVA_HOME         JDK path (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   DEFECTS4J_HOME    Path to defects4j checkout (default: ~/defects4j)
#   MAIN_CROCHET_REPO Crochet repo with built jars (default: ~/crochet)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_JSON="$SCRIPT_DIR/corpus-hard.json"
TRIAL_SCRIPT="$SCRIPT_DIR/run-trial.sh"

# Defaults
MAX_JOBS=3
TRIAL_TIMEOUT=900
DRY_RUN_FLAG=""
MODEL=""
MODEL_FLAG=""

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export DEFECTS4J_HOME="${DEFECTS4J_HOME:-$HOME/defects4j}"
export CORPUS_JSON

# Use the main repo for crochet artifacts
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
        --model)   MODEL="$2"; MODEL_FLAG="--model $2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# Derive results directory from model: results-hard-sonnet-4-6/, results-hard-haiku-4-5/, etc.
# Without --model, keep the legacy results-hard/ directory for backward-compat.
if [[ -n "$MODEL" ]]; then
    MODEL_SHORT="${MODEL#claude-}"
    RESULTS_DIR="$SCRIPT_DIR/results-hard-${MODEL_SHORT}"
else
    RESULTS_DIR="$SCRIPT_DIR/results-hard"
fi

log() { echo "[run-sweep-hard] $(date '+%H:%M:%S') $*" >&2; }

mkdir -p "$RESULTS_DIR"

# ── Extract bug IDs from corpus-hard.json ────────────────────────────────────
readarray -t BUG_IDS < <(python3 -c "
import json
with open('$CORPUS_JSON') as f:
    corpus = json.load(f)
for bug in corpus['bugs']:
    print(bug['id'])
")

CONDITIONS=(C1 C2 C3)
SWEEP_START=$(date +%s)
BRANCH_NAME=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unit/II.3-sweep")

log "Phase II hard-corpus sweep — branch: $BRANCH_NAME"
log "Starting sweep: ${#BUG_IDS[@]} bugs × ${#CONDITIONS[@]} conditions = $(( ${#BUG_IDS[@]} * ${#CONDITIONS[@]} )) trials"
log "Max concurrent: $MAX_JOBS | Per-trial timeout: ${TRIAL_TIMEOUT}s"
log "Model: ${MODEL:-<default = claude-opus-4-7>}"
log "Corpus: $CORPUS_JSON"
log "Results dir: $RESULTS_DIR"
log "Bugs: ${BUG_IDS[*]}"

# ── Trial runner helper ───────────────────────────────────────────────────────
run_trial() {
    local bug="$1"
    local condition="$2"
    local out_file="$RESULTS_DIR/${bug}-${condition}.json"

    # Skip if already completed
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
            log "  SKIP (already done): $bug × $condition"
            return 0
        fi
    fi

    log "  START: $bug × $condition → $out_file"
    local trial_start
    trial_start=$(date +%s)

    local exit_code=0
    timeout "$TRIAL_TIMEOUT" bash "$TRIAL_SCRIPT" \
        --bug "$bug" \
        --condition "$condition" \
        --out "$out_file" \
        $DRY_RUN_FLAG \
        $MODEL_FLAG \
        2>&1 | while IFS= read -r line; do
            echo "[sweep-hard/$bug/$condition] $line" >&2
        done || exit_code=$?

    local trial_end
    trial_end=$(date +%s)
    local duration=$(( trial_end - trial_start ))

    if [[ $exit_code -eq 124 ]]; then
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
print('[run-sweep-hard] Timeout JSON written: $out_file')
"
    elif [[ $exit_code -ne 0 && ! -f "$out_file" ]]; then
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
print('[run-sweep-hard] Harness-error JSON written: $out_file')
"
    elif [[ $exit_code -ne 0 ]]; then
        log "  HARNESS WARNING (exit $exit_code): $bug × $condition — output file exists, continuing"
    else
        log "  DONE: $bug × $condition in ${duration}s"
    fi
}

export -f run_trial log
export RESULTS_DIR TRIAL_SCRIPT TRIAL_TIMEOUT DRY_RUN_FLAG MODEL_FLAG CORPUS_JSON

# ── Generate all 36 (bug, condition) pairs ────────────────────────────────────
declare -a TRIAL_PAIRS=()
for bug in "${BUG_IDS[@]}"; do
    for condition in "${CONDITIONS[@]}"; do
        TRIAL_PAIRS+=("$bug $condition")
    done
done

log "Total trials: ${#TRIAL_PAIRS[@]}"

# ── Incremental push helper ───────────────────────────────────────────────────
push_and_commit() {
    local worktree_root
    worktree_root="$(cd "$SCRIPT_DIR/../.." && pwd)"
    local current_branch
    current_branch=$(git -C "$worktree_root" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unit/III.4-phase-ii-sonnet-haiku")
    (
        cd "$worktree_root"
        git add -f eval/agent-debug/results-hard/ 2>/dev/null || true
        git add eval/agent-debug/results-hard/ 2>/dev/null || true
        git add -f eval/agent-debug/results-hard-sonnet-4-6/ 2>/dev/null || true
        git add eval/agent-debug/results-hard-sonnet-4-6/ 2>/dev/null || true
        git add -f eval/agent-debug/results-hard-haiku-4-5/ 2>/dev/null || true
        git add eval/agent-debug/results-hard-haiku-4-5/ 2>/dev/null || true
        local count
        count=$(git diff --cached --name-only | wc -l)
        if [[ "$count" -gt 0 ]]; then
            git commit -m "feat(III.4): sweep results — incremental push ($(date '+%Y-%m-%d %H:%M'))" 2>/dev/null || true
            git push origin "$current_branch" 2>/dev/null || true
            log "  Incremental push: $count result file(s) committed"
        fi
    ) 2>&1 | while IFS= read -r line; do echo "[sweep-hard/push] $line" >&2; done || true
}

# ── Run with job-control parallelism ─────────────────────────────────────────
ACTIVE_JOBS=0
declare -A JOB_PIDS=()
COMPLETED=0
BATCH_SIZE=0

for pair in "${TRIAL_PAIRS[@]}"; do
    bug=$(echo "$pair" | cut -d' ' -f1)
    condition=$(echo "$pair" | cut -d' ' -f2)

    # Wait if at max jobs
    while [[ $ACTIVE_JOBS -ge $MAX_JOBS ]]; do
        wait -n 2>/dev/null || { sleep 2; }
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

# ── Score with fix-locality ───────────────────────────────────────────────────
log "Scoring all trials with fix-locality.py ..."
python3 "$SCRIPT_DIR/fix-locality.py" --batch "$RESULTS_DIR" --out-dir "$RESULTS_DIR" 2>&1 | \
    while IFS= read -r line; do log "  $line"; done || true

# ── Aggregate results ─────────────────────────────────────────────────────────
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

# ── Generate sweep-summary.md ─────────────────────────────────────────────────
log "Generating sweep-summary.md ..."

SWEEP_DURATION_VAL="$SWEEP_DURATION"
RESULTS_DIR_VAL="$RESULTS_DIR"
CORPUS_JSON_VAL="$CORPUS_JSON"

python3 -c "
import json, os, sys, glob

results_dir = os.environ.get('RESULTS_DIR_VAL', '$RESULTS_DIR')
corpus_file = os.environ.get('CORPUS_JSON_VAL', '$CORPUS_JSON')
sweep_duration = int(os.environ.get('SWEEP_DURATION_VAL', '0'))
sweep_file = os.path.join(results_dir, 'sweep-results.json')

try:
    with open(sweep_file) as f:
        results = json.load(f)
except Exception as e:
    print(f'ERROR: Could not load sweep-results.json: {e}', file=sys.stderr)
    sys.exit(1)

index = {}
for r in results:
    key = (r.get('bug', '?'), r.get('condition', '?'))
    index[key] = r

with open(corpus_file) as f:
    corpus = json.load(f)
bug_ids = [b['id'] for b in corpus['bugs']]

def cell(r):
    if r is None: return 'MISS'
    if r.get('timeout'): return 'TOUT'
    if r.get('harness_error') or r.get('setup_error'): return 'ERR'
    if r.get('compile_fail'): return 'CFAIL'
    return 'PASS' if r.get('test_pass') else 'FAIL'

def cell_strict(r):
    if r is None: return 'MISS'
    if r.get('timeout'): return 'TOUT'
    if r.get('harness_error') or r.get('setup_error'): return 'ERR'
    if r.get('compile_fail'): return 'CFAIL'
    return 'PASS' if r.get('test_pass_strict', False) else ('PASS*' if r.get('test_pass') else 'FAIL')

def locality(r):
    if r is None: return '-'
    return str(r.get('fix_locality_score', '-'))

def fmt_dur(r):
    if r is None: return '-'
    d = r.get('duration_seconds', 0)
    return f'{d}s'

def fmt_tc(r):
    if r is None: return '-'
    return str(r.get('tool_calls', 0))

def fmt_dq(r):
    if r is None: return '-'
    return str(r.get('diagnosis_quality', 0))

lines = []
lines.append('# Phase II Unit II.3 — Hard Corpus Sweep Summary')
lines.append('')
lines.append(f'**36-trial sweep** (12 bugs × C1/C2/C3, 900s timeout, parallelism=3)')
lines.append(f'**Wall-clock:** {sweep_duration}s ({sweep_duration//60}m {sweep_duration%60}s)')
lines.append('')

# Per-bug × per-condition table
lines.append('## Per-Bug × Per-Condition Results')
lines.append('')
lines.append('| Bug | C1 pass | C1 strict | C2 pass | C2 strict | C3 pass | C3 strict | C1 loc | C2 loc | C3 loc |')
lines.append('|-----|---------|-----------|---------|-----------|---------|-----------|--------|--------|--------|')

c1_pass_list = []
c2_pass_list = []
c3_pass_list = []
c1_strict_list = []
c2_strict_list = []
c3_strict_list = []
c1_loc_list = []
c2_loc_list = []
c3_loc_list = []
c1_tc_list = []
c2_tc_list = []
c3_tc_list = []
c1_dur_list = []
c2_dur_list = []
c3_dur_list = []
c1_dq_list = []
c2_dq_list = []
c3_dq_list = []

for bug in bug_ids:
    r1 = index.get((bug, 'C1'))
    r2 = index.get((bug, 'C2'))
    r3 = index.get((bug, 'C3'))

    p1 = r1.get('test_pass', False) if r1 else False
    p2 = r2.get('test_pass', False) if r2 else False
    p3 = r3.get('test_pass', False) if r3 else False
    s1 = r1.get('test_pass_strict', False) if r1 else False
    s2 = r2.get('test_pass_strict', False) if r2 else False
    s3 = r3.get('test_pass_strict', False) if r3 else False
    l1 = r1.get('fix_locality_score', 0.0) if r1 else 0.0
    l2 = r2.get('fix_locality_score', 0.0) if r2 else 0.0
    l3 = r3.get('fix_locality_score', 0.0) if r3 else 0.0

    c1_pass_list.append(int(p1))
    c2_pass_list.append(int(p2))
    c3_pass_list.append(int(p3))
    c1_strict_list.append(int(s1))
    c2_strict_list.append(int(s2))
    c3_strict_list.append(int(s3))
    c1_loc_list.append(l1)
    c2_loc_list.append(l2)
    c3_loc_list.append(l3)

    def safe_int(v):
        try: return int(v)
        except: return 0

    c1_tc_list.append(safe_int(r1.get('tool_calls', 0)) if r1 else 0)
    c2_tc_list.append(safe_int(r2.get('tool_calls', 0)) if r2 else 0)
    c3_tc_list.append(safe_int(r3.get('tool_calls', 0)) if r3 else 0)
    c1_dur_list.append(safe_int(r1.get('duration_seconds', 0)) if r1 else 0)
    c2_dur_list.append(safe_int(r2.get('duration_seconds', 0)) if r2 else 0)
    c3_dur_list.append(safe_int(r3.get('duration_seconds', 0)) if r3 else 0)
    c1_dq_list.append(safe_int(r1.get('diagnosis_quality', 0)) if r1 else 0)
    c2_dq_list.append(safe_int(r2.get('diagnosis_quality', 0)) if r2 else 0)
    c3_dq_list.append(safe_int(r3.get('diagnosis_quality', 0)) if r3 else 0)

    def yesno(v):
        return 'YES' if v else 'no'

    row = f'| {bug:<20} | {cell(r1):<7} | {yesno(s1):<9} | {cell(r2):<7} | {yesno(s2):<9} | {cell(r3):<7} | {yesno(s3):<9} | {l1:<6} | {l2:<6} | {l3:<6} |'
    lines.append(row)

n = len(bug_ids)
lines.append('')
lines.append('### Legend')
lines.append('- PASS: test_pass=true (primary test passes + no agent-induced regressions)')
lines.append('- YES (strict): PASS + fix_locality_score >= 0.5 (modified correct production files)')
lines.append('- PASS*: test_pass=true but test_pass_strict=false (bad locality)')
lines.append('- TOUT: timed out at 900s')
lines.append('- ERR: harness error')
lines.append('- loc: fix_locality_score (1.0=exact, 0.5=partial, 0.0=miss)')
lines.append('')

# Per-condition aggregate
lines.append('## Per-Condition Aggregate')
lines.append('')
lines.append('| Metric | C1 | C2 | C3 |')
lines.append('|--------|----|----|-----|')

def pct(lst):
    n = len(lst)
    return f'{sum(lst)}/{n} ({100*sum(lst)//n if n else 0}%)'

def avg(lst):
    n = len(lst)
    return f'{sum(lst)/n:.2f}' if n else '-'

lines.append(f'| % test_pass | {pct(c1_pass_list)} | {pct(c2_pass_list)} | {pct(c3_pass_list)} |')
lines.append(f'| % test_pass_strict | {pct(c1_strict_list)} | {pct(c2_strict_list)} | {pct(c3_strict_list)} |')
lines.append(f'| avg fix_locality | {avg(c1_loc_list)} | {avg(c2_loc_list)} | {avg(c3_loc_list)} |')
lines.append(f'| avg tool_calls | {avg(c1_tc_list)} | {avg(c2_tc_list)} | {avg(c3_tc_list)} |')
lines.append(f'| avg duration (s) | {avg(c1_dur_list)} | {avg(c2_dur_list)} | {avg(c3_dur_list)} |')
lines.append(f'| avg diagnosis_quality | {avg(c1_dq_list)} | {avg(c2_dq_list)} | {avg(c3_dq_list)} |')
lines.append('')

# Headline findings
lines.append('## Headline Findings')
lines.append('')

# Jsoup-87 analysis
r87 = {c: index.get(('Jsoup-87', c)) for c in ['C1','C2','C3']}
jsoup87_line = 'Jsoup-87 (marquee bug): '
jsoup87_parts = []
for c in ['C1','C2','C3']:
    r = r87[c]
    if r:
        s = 'PASS' if r.get('test_pass') else ('TOUT' if r.get('timeout') else 'FAIL')
        jsoup87_parts.append(f'{c}={s}')
    else:
        jsoup87_parts.append(f'{c}=MISS')
lines.append('**' + jsoup87_line + ', '.join(jsoup87_parts) + '**')
lines.append('')

# Did C3 beat C1 on test_pass_strict?
c3_beats = sum(c3_strict_list) > sum(c1_strict_list)
c3_ties = sum(c3_strict_list) == sum(c1_strict_list)
headline = f'C3 test_pass_strict={sum(c3_strict_list)}/{n} vs C1={sum(c1_strict_list)}/{n} — '
if c3_beats:
    headline += 'C3 WINS on strict score.'
elif c3_ties:
    headline += 'C3 TIES C1 on strict score.'
else:
    headline += 'C3 does NOT beat C1 on strict score.'
lines.append(headline)
lines.append('')

# Fix-locality on multi-file bugs
multi_bugs = ['Jsoup-22','Jsoup-28','Jsoup-52','Jsoup-56','Jsoup-58','Jsoup-71',
              'JacksonDatabind-79','Closure-137','Closure-155']
c1_multi = [l1 for bug, l1 in zip(bug_ids, c1_loc_list) if bug in multi_bugs]
c2_multi = [l2 for bug, l2 in zip(bug_ids, c2_loc_list) if bug in multi_bugs]
c3_multi = [l3 for bug, l3 in zip(bug_ids, c3_loc_list) if bug in multi_bugs]
if c1_multi and c3_multi:
    lines.append(f'Fix-locality on 9 multi-file bugs: C1_avg={sum(c1_multi)/len(c1_multi):.2f}, C2_avg={sum(c2_multi)/len(c2_multi):.2f}, C3_avg={sum(c3_multi)/len(c3_multi):.2f}')
    lines.append('')

# Jsoup-56 (5-file richest)
r56 = {c: index.get(('Jsoup-56', c)) for c in ['C1','C2','C3']}
lines.append('Jsoup-56 (5 canonical files):')
for c in ['C1','C2','C3']:
    r = r56[c]
    if r:
        overlap = r.get('file_overlap', [])
        missed = r.get('missed_canonical', [])
        loc = r.get('fix_locality_score', '-')
        lines.append(f'  {c}: loc={loc}, overlap={len(overlap)}/5, missed={len(missed)}')
    else:
        lines.append(f'  {c}: MISS')
lines.append('')

# Timeout list
timeouts = [(r.get('bug'), r.get('condition')) for r in results if r.get('timeout')]
if timeouts:
    lines.append('## Timed-Out Trials (900s)')
    lines.append('')
    for bug, cond in sorted(timeouts):
        lines.append(f'- {bug} × {cond} — real-hard bug, document for II.4')
    lines.append('')
else:
    lines.append('No trials timed out at 900s.')
    lines.append('')

# Recommendation
lines.append('## Recommendation')
lines.append('')
if sum(c3_strict_list) >= sum(c1_strict_list):
    lines.append('C3 meets or exceeds C1 on strict score. Dispatch II.4 (analysis + writeup) now.')
else:
    lines.append('C3 underperforms C1 on strict score. Review flaky/timeout trials before dispatching II.4.')
    # Check for C3 anomalies
    anomalies = [bug for bug, p1, p3 in zip(bug_ids, c1_pass_list, c3_pass_list) if p1 and not p3]
    if anomalies:
        lines.append(f'Anomalies (C3 fails where C1 passes): {anomalies}')
lines.append('')
lines.append('---')
lines.append('*Generated by run-sweep-hard.sh / Phase II Unit II.3*')

summary_text = '\n'.join(lines)
print(summary_text)

summary_path = os.path.join(results_dir, 'sweep-summary.md')
with open(summary_path, 'w') as f:
    f.write(summary_text + '\n')
print(f'\nSummary written to: {summary_path}', file=sys.stderr)
" RESULTS_DIR_VAL="$RESULTS_DIR_VAL" CORPUS_JSON_VAL="$CORPUS_JSON_VAL" SWEEP_DURATION_VAL="$SWEEP_DURATION_VAL"

# ── Final incremental push ─────────────────────────────────────────────────────
push_and_commit

log "Sweep complete. Results in: $RESULTS_DIR"
log "Summary: $RESULTS_DIR/sweep-summary.md"
