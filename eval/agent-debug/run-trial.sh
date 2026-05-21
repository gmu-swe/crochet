#!/usr/bin/env bash
# eval/agent-debug/run-trial.sh — Trial harness for the agent-debugging benchmark.
#
# Usage:
#   ./run-trial.sh --bug Lang-1 --condition C2 --out /tmp/trial-out/Lang-1-C2.json
#
# Options:
#   --bug <id>          Bug ID from corpus.json (e.g. Lang-1, Math-5)
#   --condition <C>     C1 | C2 | C3
#   --out <path>        Output JSON file path
#   --max-tool-calls N  Cap agent tool calls (default: 80)
#   --workdir <path>    Override trial workdir (default: /tmp/trial-<bug>-<condition>)
#   --keep-workdir      Do not delete workdir on exit
#   --dry-run           Set up worktree + verify bug reproduces, then exit (skip agent)
#
# Environment:
#   DEFECTS4J_HOME      Path to defects4j checkout (default: ~/defects4j)
#   JAVA_HOME           JDK to use (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   CROCHET_REPO        Path to crochet repo root (default: auto-detected from script location)
#   ANTHROPIC_API_KEY   Required for claude CLI (condition C1/C2/C3)
#   PERL5LIB            Set if defects4j needs additional Perl libs

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
DEFECTS4J_HOME="${DEFECTS4J_HOME:-$HOME/defects4j}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
MAX_TOOL_CALLS=80
KEEP_WORKDIR=false
DRY_RUN=false
WORKDIR_OVERRIDE=""
CONDITION=""
BUG_ID=""
OUT_PATH=""

# Auto-detect crochet repo root (script lives at eval/agent-debug/run-trial.sh)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CROCHET_REPO="${CROCHET_REPO:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

# ── Argument parsing ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --bug)         BUG_ID="$2";         shift 2 ;;
        --condition)   CONDITION="$2";       shift 2 ;;
        --out)         OUT_PATH="$2";        shift 2 ;;
        --max-tool-calls) MAX_TOOL_CALLS="$2"; shift 2 ;;
        --workdir)     WORKDIR_OVERRIDE="$2"; shift 2 ;;
        --keep-workdir) KEEP_WORKDIR=true;   shift ;;
        --dry-run)     DRY_RUN=true;         shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

[[ -z "$BUG_ID" ]]    && { echo "Error: --bug required" >&2; exit 1; }
[[ -z "$CONDITION" ]] && { echo "Error: --condition required (C1|C2|C3)" >&2; exit 1; }
[[ -z "$OUT_PATH" ]]  && { echo "Error: --out required" >&2; exit 1; }

case "$CONDITION" in
    C1|C2|C3) ;;
    *) echo "Error: --condition must be C1, C2, or C3" >&2; exit 1 ;;
esac

# ── Paths ─────────────────────────────────────────────────────────────────────
CORPUS_JSON="$SCRIPT_DIR/corpus.json"
PROMPTS_DIR="$SCRIPT_DIR/prompts"
JUDGE_PROMPT="$SCRIPT_DIR/judge-prompt.md"
D4J_BIN="$DEFECTS4J_HOME/framework/bin/defects4j"

export JAVA_HOME
export PERL5LIB="${PERL5LIB:-$HOME/perl5/lib/perl5:$HOME/perl5/lib/perl5/x86_64-linux-gnu-thread-multi}"
export PATH="$JAVA_HOME/bin:$PATH"

# ── Helpers ───────────────────────────────────────────────────────────────────
log() { echo "[run-trial] $*" >&2; }
die() { echo "[run-trial] ERROR: $*" >&2; exit 1; }

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

json_field() {
    # Extract a field from corpus.json for our bug
    # Usage: json_field .field_name
    python3 -c "
import json, sys
with open('$CORPUS_JSON') as f:
    corpus = json.load(f)
bug = next((b for b in corpus['bugs'] if b['id'] == '$BUG_ID'), None)
if bug is None:
    print('', end='')
    sys.exit(1)
val = bug$(echo "$1" | sed "s/\./['/g" | sed "s/$/']/" | sed "s/\['/['/g")
print(val if val is not None else '', end='')
" 2>/dev/null || python3 -c "
import json, sys
with open('$CORPUS_JSON') as f:
    corpus = json.load(f)
bug = next((b for b in corpus['bugs'] if b['id'] == '$BUG_ID'), None)
if bug is None:
    sys.exit(1)
keys = '$1'.lstrip('.').split('.')
val = bug
for k in keys:
    val = val[k]
print(val if val is not None else '', end='')
"
}

# ── Load corpus entry ─────────────────────────────────────────────────────────
log "Loading corpus entry for $BUG_ID ..."

PROJECT=$(json_field .project)       || die "Bug '$BUG_ID' not found in corpus.json"
BUG_NUMBER=$(json_field .bug_number)
FAILING_TEST=$(json_field .failing_test)
FIX_SUMMARY=$(json_field .fix_summary)
CHECKOUT_CMD=$(json_field .checkout_command)
BUILD_FIX=$(json_field .build_fix)

[[ -z "$PROJECT" ]] && die "Bug '$BUG_ID' not found in corpus.json"

log "Bug: $BUG_ID | Project: $PROJECT | Test: $FAILING_TEST"

# ── Set up workdir ────────────────────────────────────────────────────────────
if [[ -n "$WORKDIR_OVERRIDE" ]]; then
    WORKDIR="$WORKDIR_OVERRIDE"
else
    WORKDIR="/tmp/trial-${BUG_ID}-${CONDITION}"
fi

mkdir -p "$(dirname "$OUT_PATH")"
mkdir -p "$WORKDIR"

# Write fix-summary to file now that workdir exists
echo "$FIX_SUMMARY" > "$WORKDIR/fix-summary.txt"

cleanup() {
    if [[ "$KEEP_WORKDIR" == "false" && -d "$WORKDIR" ]]; then
        log "Cleaning up workdir: $WORKDIR"
        rm -rf "$WORKDIR"
    fi
}
trap cleanup EXIT

# ── Step 1: Checkout the buggy version ────────────────────────────────────────
log "Step 1: Checking out buggy version of $PROJECT-$BUG_NUMBER ..."

BUGGY_WORKDIR="$WORKDIR/buggy"
if [[ -d "$BUGGY_WORKDIR/src" || -d "$BUGGY_WORKDIR/source" ]]; then
    log "  (Worktree already exists, skipping checkout)"
else
    rm -rf "$BUGGY_WORKDIR"
    "$D4J_BIN" checkout -p "$PROJECT" -v "${BUG_NUMBER}b" -w "$BUGGY_WORKDIR" \
        2>&1 | while IFS= read -r line; do log "  d4j: $line"; done
fi

# Apply build fixes required for Java 21 (from corpus.json notes)
apply_build_fix() {
    local workdir="$1"
    log "Applying build fixes: $BUILD_FIX"

    # Lang projects: bump compile.source/compile.target in default.properties or maven-build.xml
    if [[ "$PROJECT" == "Lang" ]]; then
        local props="$workdir/default.properties"
        local mvnbuild="$workdir/maven-build.xml"
        if [[ -f "$props" ]]; then
            # Handle both "compile.source=1.6" and "compile.source = 1.6" forms
            sed -i 's/compile\.source\s*=\s*1\.[56]/compile.source = 1.8/g' "$props"
            sed -i 's/compile\.target\s*=\s*1\.[56]/compile.target = 1.8/g' "$props"
        fi
        if [[ -f "$mvnbuild" ]]; then
            sed -i 's/source="1\.[56]"/source="1.8"/g' "$mvnbuild"
            sed -i 's/target="1\.[56]"/target="1.8"/g' "$mvnbuild"
        fi
    fi

    # Math projects: bump source/target in build.xml; add nashorn jar if missing
    if [[ "$PROJECT" == "Math" ]]; then
        local buildxml="$workdir/build.xml"
        if [[ -f "$buildxml" ]]; then
            sed -i 's/source="1\.[56]"/source="1.8"/g' "$buildxml"
            sed -i 's/target="1\.[56]"/target="1.8"/g' "$buildxml"
        fi
        # Add nashorn + asm jars to defects4j ant lib if not present
        local antlib="$DEFECTS4J_HOME/major/lib/ant"
        if [[ -d "$antlib" ]]; then
            if [[ ! -f "$antlib/nashorn-core-15.4.jar" ]]; then
                # Download or skip (best-effort)
                log "  nashorn-core-15.4.jar missing from $antlib — Math mutation may fail (not required for test-only runs)"
            fi
        fi
    fi

    # Time projects: bump source/target; patch ZoneInfoCompiler to fork
    if [[ "$PROJECT" == "Time" ]]; then
        local mvnbuild="$workdir/maven-build.xml"
        local timebuild="$DEFECTS4J_HOME/framework/projects/Time/Time.build.xml"
        if [[ -f "$mvnbuild" ]]; then
            sed -i 's/source="1\.[56]"/source="1.8"/g' "$mvnbuild"
            sed -i 's/target="1\.[56]"/target="1.8"/g' "$mvnbuild"
        fi
        if [[ -f "$timebuild" ]]; then
            # Patch ZoneInfoCompiler java tasks to fork if not already patched
            if ! grep -q 'fork="true"' "$timebuild" 2>/dev/null; then
                sed -i 's/<java classname="org.joda.time.tz.ZoneInfoCompiler"/<java fork="true" classname="org.joda.time.tz.ZoneInfoCompiler"/g' "$timebuild"
            fi
        fi
    fi

    # Closure projects: bump source/target in build.xml
    if [[ "$PROJECT" == "Closure" ]]; then
        local buildxml="$workdir/build.xml"
        if [[ -f "$buildxml" ]]; then
            sed -i 's/source="1\.[56]"/source="1.8"/g' "$buildxml"
            sed -i 's/target="1\.[56]"/target="1.8"/g' "$buildxml"
            # Also patch lib/rhino/build.properties if exists
            local rhinoprops="$workdir/lib/rhino/build.properties"
            if [[ -f "$rhinoprops" ]]; then
                sed -i 's/source-level=1\.[56]/source-level=1.8/g' "$rhinoprops"
            fi
        fi
    fi
}

apply_build_fix "$BUGGY_WORKDIR"

# ── Step 2: Compile ───────────────────────────────────────────────────────────
log "Step 2: Compiling $BUG_ID ..."
(cd "$BUGGY_WORKDIR" && "$D4J_BIN" compile 2>&1) | while IFS= read -r line; do log "  d4j: $line"; done || {
    log "WARNING: compile step exited non-zero; will try running test anyway"
}

# ── Step 3: Verify the bug reproduces ─────────────────────────────────────────
log "Step 3: Verifying bug reproduces (failing test must fail) ..."

VERIFY_LOG="$WORKDIR/verify-pretest.log"
(cd "$BUGGY_WORKDIR" && "$D4J_BIN" test -t "$FAILING_TEST" 2>&1) > "$VERIFY_LOG" || true

if grep -q "Failing tests:" "$VERIFY_LOG" && ! grep -q "Failing tests: 0" "$VERIFY_LOG"; then
    log "  Bug confirmed: test fails as expected."
    BUG_REPRODUCED=true
elif grep -q "Failing tests: 0" "$VERIFY_LOG"; then
    log "  SETUP ERROR: Failing test passes on buggy version — bug does not reproduce."
    log "  $(cat "$VERIFY_LOG")"
    # Write error JSON
    python3 -c "
import json, datetime
result = {
    'bug': '$BUG_ID',
    'condition': '$CONDITION',
    'started_at': datetime.datetime.utcnow().isoformat() + 'Z',
    'duration_seconds': 0,
    'tool_calls': 0,
    'test_pass': False,
    'regressed_tests': [],
    'diagnosis_quality': 0,
    'agent_patch': '',
    'agent_log': '',
    'judge_reasoning': '',
    'setup_error': 'Failing test passes on buggy version — bug does not reproduce. Check build_fix application.',
    'verify_log': open('$VERIFY_LOG').read()
}
print(json.dumps(result, indent=2))
" > "$OUT_PATH"
    exit 2
else
    log "  WARNING: Could not confirm test failure from log. Proceeding anyway."
    log "  $(cat "$VERIFY_LOG")"
    BUG_REPRODUCED=false
fi

if [[ "$DRY_RUN" == "true" ]]; then
    log "Dry run complete. Worktree: $BUGGY_WORKDIR"
    exit 0
fi

# ── Step 4: Build the agent prompt ────────────────────────────────────────────
log "Step 4: Preparing agent prompt for condition $CONDITION ..."

PROMPT_TEMPLATE="$PROMPTS_DIR/condition-${CONDITION}.md"
[[ -f "$PROMPT_TEMPLATE" ]] || die "Prompt template not found: $PROMPT_TEMPLATE"

# Locate crochet artifacts (for C3)
CROCHET_AGENT_JAR="$CROCHET_REPO/crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar"
CROCHET_DEBUG_JAR="$CROCHET_REPO/crochet-debug/target/crochet-debug-1.0.0-SNAPSHOT-standalone.jar"

PROMPT_FILE="$WORKDIR/prompt.md"
sed \
    -e "s|{{BUG_ID}}|$BUG_ID|g" \
    -e "s|{{PROJECT}}|$PROJECT|g" \
    -e "s|{{FAILING_TEST}}|$FAILING_TEST|g" \
    -e "s|{{FIX_SUMMARY}}|$FIX_SUMMARY|g" \
    -e "s|{{WORKDIR}}|$BUGGY_WORKDIR|g" \
    -e "s|{{MAX_TOOL_CALLS}}|$MAX_TOOL_CALLS|g" \
    -e "s|{{CROCHET_AGENT_JAR}}|$CROCHET_AGENT_JAR|g" \
    -e "s|{{CROCHET_DEBUG_JAR}}|$CROCHET_DEBUG_JAR|g" \
    "$PROMPT_TEMPLATE" > "$PROMPT_FILE"

# ── Step 5: Determine allowed tools per condition ─────────────────────────────
case "$CONDITION" in
    C1) ALLOWED_TOOLS="Bash,Read,Write,Edit" ;;
    C2) ALLOWED_TOOLS="Bash,Read,Write,Edit" ;;   # jdb available via Bash
    C3) ALLOWED_TOOLS="Bash,Read,Write,Edit" ;;   # crochet-debug via Bash
esac

# ── Step 6: Snapshot the worktree before agent runs ───────────────────────────
log "Step 6: Snapshotting buggy worktree state ..."
PRE_PATCH_DIR="$WORKDIR/pre-patch"
mkdir -p "$PRE_PATCH_DIR"
# Use git to capture the state (d4j checked out a clean git repo)
(cd "$BUGGY_WORKDIR" && git diff HEAD > "$PRE_PATCH_DIR/initial.diff") 2>/dev/null || true
(cd "$BUGGY_WORKDIR" && git stash list > "$PRE_PATCH_DIR/stash.txt") 2>/dev/null || true

# ── Step 7: Run the debugging agent ──────────────────────────────────────────
log "Step 7: Spawning debugging agent (condition=$CONDITION, max-tool-calls=$MAX_TOOL_CALLS) ..."

AGENT_LOG_FILE="$WORKDIR/agent-session.log"
AGENT_JSON_FILE="$WORKDIR/agent-session.json"
AGENT_START_TS=$(date +%s)

# Approach A: headless claude CLI
# claude -p reads the prompt, runs with --dangerously-skip-permissions (needed for
# Bash in non-interactive mode), caps tool use with --max-turns.
# We use --output-format stream-json to capture structured events.

CLAUDE_CMD=(
    claude
    -p
    --dangerously-skip-permissions
    --allowed-tools "$ALLOWED_TOOLS"
    --max-turns "$MAX_TOOL_CALLS"
    --output-format "json"
    --no-session-persistence
    --add-dir "$BUGGY_WORKDIR"
)

log "  Running: ${CLAUDE_CMD[*]} < '$PROMPT_FILE'"

# Run agent; capture full stream-json output; also tee to log
TOOL_CALL_COUNT=0
AGENT_FINAL_TEXT=""

set +e
"${CLAUDE_CMD[@]}" < "$PROMPT_FILE" > "$AGENT_JSON_FILE" 2>"$AGENT_LOG_FILE"
AGENT_EXIT=$?
set -e

AGENT_END_TS=$(date +%s)
DURATION=$(( AGENT_END_TS - AGENT_START_TS ))

log "  Agent exited with code $AGENT_EXIT after ${DURATION}s"

# Parse json (single-object) output for tool calls + final text
# --output-format json produces one JSON object with "result", "usage", etc.
TOOL_CALL_COUNT=$(python3 -c "
import json, sys
try:
    with open('$AGENT_JSON_FILE') as f:
        obj = json.load(f)
    # usage.iterations tracks tool rounds; count tool calls from num_turns
    # The most reliable field is num_turns (each turn = one tool call round)
    # But also check usage.input_tokens as a proxy — not ideal.
    # claude --output-format json does not break down per-tool-call counts.
    # Use num_turns as a proxy for now.
    print(obj.get('num_turns', 0))
except Exception as e:
    print(0)
" 2>/dev/null || echo "0")

AGENT_FINAL_TEXT=$(python3 -c "
import json, sys
try:
    with open('$AGENT_JSON_FILE') as f:
        obj = json.load(f)
    print(obj.get('result', ''))
except Exception:
    try:
        with open('$AGENT_JSON_FILE') as f:
            print(f.read())
    except Exception:
        print('')
" 2>/dev/null || echo "")

log "  Tool calls recorded: $TOOL_CALL_COUNT"

# Extract diagnosis from agent text
AGENT_DIAGNOSIS=$(echo "$AGENT_FINAL_TEXT" | python3 -c "
import sys
text = sys.stdin.read()
marker = 'DIAGNOSIS COMPLETE'
if marker in text:
    idx = text.index(marker)
    print(text[idx:].strip())
else:
    # Return last 2000 chars as best-effort diagnosis
    print(text[-2000:].strip())
" 2>/dev/null || echo "$AGENT_FINAL_TEXT" | tail -20)

# ── Step 8: Capture agent's patch ─────────────────────────────────────────────
log "Step 8: Capturing agent's final patch ..."

touch "$WORKDIR/agent.patch"
if (cd "$BUGGY_WORKDIR" && git status --short 2>/dev/null | grep -q '.'); then
    (cd "$BUGGY_WORKDIR" && git diff HEAD 2>/dev/null || true) > "$WORKDIR/agent.patch"
fi

# ── Step 9: Score — test pass/fail ────────────────────────────────────────────
log "Step 9: Scoring — running failing test against agent's state ..."

POST_TEST_LOG="$WORKDIR/post-test.log"
(cd "$BUGGY_WORKDIR" && "$D4J_BIN" test -t "$FAILING_TEST" 2>&1) > "$POST_TEST_LOG" || true

PRIMARY_PASS=false
if grep -q "Failing tests: 0" "$POST_TEST_LOG"; then
    PRIMARY_PASS=true
    log "  PRIMARY: Test PASSES after agent intervention."
else
    log "  PRIMARY: Test still FAILS after agent intervention."
fi

# Always run the full test suite to detect regressions.
# test_pass is STRICT: requires the originally-failing test to pass AND zero
# previously-passing tests to now fail.  regressed_tests is informational.
# Rationale: an agent that fixes the target test by breaking 71 others has not
# actually fixed the bug — it has shifted the failure.
REGRESSED_TESTS="[]"
REGRESSION_LOG="$WORKDIR/regression.log"
if [[ "$PRIMARY_PASS" == "true" ]]; then
    log "  Running full test suite to check for regressions ..."
    (cd "$BUGGY_WORKDIR" && "$D4J_BIN" test 2>&1) > "$REGRESSION_LOG" || true
    REGRESSED_TESTS=$(python3 -c "
import re, json
with open('$REGRESSION_LOG') as f:
    content = f.read()
# Parse failing tests list
failing = re.findall(r'^\s+- (.+)$', content, re.MULTILINE)
# Exclude the original failing test (it should pass now)
orig = '$FAILING_TEST'
# Strip project prefix variants
regressions = [t.strip() for t in failing if t.strip() != orig and not t.strip().startswith(orig.split('::')[0] + '::' + orig.split('::')[-1])]
print(json.dumps(regressions))
" 2>/dev/null || echo "[]")
fi

# Strict test_pass: primary must pass AND no regressions introduced.
# Write REGRESSED_TESTS to a temp file to avoid shell-quoting issues with
# test names that may contain apostrophes or other special characters.
REGRESSED_TESTS_FILE="$WORKDIR/regressed-tests.json"
echo "$REGRESSED_TESTS" > "$REGRESSED_TESTS_FILE"
REGRESSION_COUNT=$(python3 -c "import json; print(len(json.load(open('$REGRESSED_TESTS_FILE'))))" 2>/dev/null || echo "0")
TEST_PASS=false
if [[ "$PRIMARY_PASS" == "true" && "$REGRESSION_COUNT" == "0" ]]; then
    TEST_PASS=true
    log "  STRICT SCORE: PASS (target test passes, zero regressions)."
elif [[ "$PRIMARY_PASS" == "true" ]]; then
    log "  STRICT SCORE: FAIL (target test passes but $REGRESSION_COUNT regression(s) detected — fix is not clean)."
else
    log "  STRICT SCORE: FAIL (target test still failing)."
fi

# ── Step 10: LLM-as-judge for diagnosis quality ────────────────────────────────
log "Step 10: Running LLM-as-judge for diagnosis quality ..."

# Write diagnosis to a file to avoid shell quoting issues
echo "$AGENT_DIAGNOSIS" | head -100 > "$WORKDIR/agent-diagnosis.txt"

JUDGE_INPUT=$(python3 -c "
fix_summary = open('$WORKDIR/fix-summary.txt').read().strip()
agent_diagnosis = open('$WORKDIR/agent-diagnosis.txt').read().strip()
template = open('$JUDGE_PROMPT').read()
rendered = template.replace('{{FIX_SUMMARY}}', fix_summary).replace('{{AGENT_DIAGNOSIS}}', agent_diagnosis)
print(rendered)
" 2>/dev/null)

JUDGE_RAW_FILE="$WORKDIR/judge-raw.txt"
echo "$JUDGE_INPUT" | claude -p --dangerously-skip-permissions --no-session-persistence --tools "" > "$JUDGE_RAW_FILE" 2>/dev/null || echo '{"score":0,"reasoning":"judge failed"}' > "$JUDGE_RAW_FILE"

# Extract score and reasoning from judge output
JUDGE_PARSE=$(python3 -c "
import json, sys, re
with open('$JUDGE_RAW_FILE') as f:
    text = f.read()
m = re.search(r'\{[^{}]+\}', text, re.DOTALL)
if m:
    try:
        obj = json.loads(m.group())
        score = obj.get('score', 0)
        reasoning = obj.get('reasoning', text[:500])
        print(json.dumps({'score': score, 'reasoning': reasoning}))
    except Exception:
        print(json.dumps({'score': 0, 'reasoning': text[:500]}))
else:
    print(json.dumps({'score': 0, 'reasoning': text[:500]}))
" 2>/dev/null || echo '{"score":0,"reasoning":"parse error"}')

DIAGNOSIS_QUALITY=$(echo "$JUDGE_PARSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('score',0))" 2>/dev/null || echo "0")
echo "$JUDGE_PARSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('reasoning',''))" > "$WORKDIR/judge-reasoning.txt" 2>/dev/null || echo "" > "$WORKDIR/judge-reasoning.txt"

log "  Diagnosis quality: $DIAGNOSIS_QUALITY/5"
log "  Judge reasoning: $(cat "$WORKDIR/judge-reasoning.txt" | head -3)"

# ── Step 11: Write output JSON ─────────────────────────────────────────────────
log "Step 11: Writing output to $OUT_PATH ..."

# Write scalar fields to a JSON metadata file (no multiline string issues)
METADATA_FILE="$WORKDIR/metadata.json"
python3 -c "
import json, datetime
meta = {
    'bug': '$BUG_ID',
    'condition': '$CONDITION',
    'started_at': datetime.datetime.fromtimestamp($AGENT_START_TS, tz=datetime.timezone.utc).isoformat(),
    'duration_seconds': $DURATION,
    'tool_calls': $TOOL_CALL_COUNT,
    'test_pass': $( [[ "$TEST_PASS" == "true" ]] && echo "True" || echo "False" ),
    'regressed_tests': $REGRESSED_TESTS,
    'diagnosis_quality': $DIAGNOSIS_QUALITY,
    'agent_exit_code': $AGENT_EXIT,
    'bug_reproduced_pretest': $( [[ "$BUG_REPRODUCED" == "true" ]] && echo "True" || echo "False" ),
}
print(json.dumps(meta))
" > "$METADATA_FILE"

# Combine metadata + file contents into final JSON using python (avoids shell quoting nightmares)
python3 -c "
import json

meta = json.load(open('$METADATA_FILE'))

def read_file(path, default=''):
    try:
        with open(path) as f:
            return f.read()
    except Exception:
        return default

meta['agent_patch'] = read_file('$WORKDIR/agent.patch')
meta['agent_log'] = read_file('$AGENT_JSON_FILE')
meta['agent_stderr'] = read_file('$AGENT_LOG_FILE')
meta['judge_reasoning'] = read_file('$WORKDIR/judge-reasoning.txt')
meta['verify_log'] = read_file('$VERIFY_LOG')

with open('$OUT_PATH', 'w') as f:
    json.dump(meta, f, indent=2, default=str)

print('[run-trial] Output written to $OUT_PATH')
"

log "Done. Trial complete: bug=$BUG_ID condition=$CONDITION test_pass=$TEST_PASS duration=${DURATION}s tool_calls=$TOOL_CALL_COUNT diagnosis_quality=$DIAGNOSIS_QUALITY/5"
