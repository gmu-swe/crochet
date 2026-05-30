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
#   --model <id>        Claude model ID (default: empty = claude CLI default = Opus 4.7).
#                       Examples: claude-sonnet-4-6, claude-haiku-4-5, claude-opus-4-7
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
SEED=""
MODEL=""

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
        --model)       MODEL="$2";           shift 2 ;;
        --workdir)     WORKDIR_OVERRIDE="$2"; shift 2 ;;
        --keep-workdir) KEEP_WORKDIR=true;   shift ;;
        --dry-run)     DRY_RUN=true;         shift ;;
        --seed)        SEED="$2";            shift 2 ;;
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
# Allow caller to override corpus file (e.g. corpus-hard.json for Phase II)
CORPUS_JSON="${CORPUS_JSON:-$SCRIPT_DIR/corpus.json}"
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
    # Extract a field from corpus.json (or corpus-hard.json) for our bug.
    # Both formats are supported: top-level 'bugs' array (Phase I) or
    # top-level 'candidates' array (Phase II candidates/corpus-hard).
    # Usage: json_field .field_name
    python3 -c "
import json, sys
with open('$CORPUS_JSON') as f:
    corpus = json.load(f)
# Support both 'bugs' (Phase I) and 'candidates' (Phase II) top-level keys
bug_list = corpus.get('bugs', corpus.get('candidates', []))
bug = next((b for b in bug_list if b['id'] == '$BUG_ID'), None)
if bug is None:
    sys.exit(1)
keys = '$1'.lstrip('.').split('.')
val = bug
for k in keys:
    if isinstance(val, dict):
        val = val.get(k)
    else:
        val = None
    if val is None:
        print('', end='')
        sys.exit(0)
if isinstance(val, list):
    print(' '.join(str(v) for v in val), end='')
else:
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
            # Handle attribute style: source="1.x"
            sed -i 's/source="1\.[56]"/source="1.8"/g' "$buildxml"
            sed -i 's/target="1\.[56]"/target="1.8"/g' "$buildxml"
            # Handle property style: value="1.x" on compile.source / compile.target lines
            sed -i '/compile\.source/s/value="1\.[56]"/value="1.8"/g' "$buildxml"
            sed -i '/compile\.target/s/value="1\.[56]"/value="1.8"/g' "$buildxml"
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

    # Closure projects: bump source/target in build.xml and rhino props
    if [[ "$PROJECT" == "Closure" ]]; then
        local buildxml="$workdir/build.xml"
        if [[ -f "$buildxml" ]]; then
            sed -i 's/source="1\.[56]"/source="1.8"/g' "$buildxml"
            sed -i 's/target="1\.[56]"/target="1.8"/g' "$buildxml"
            # Handle property style: value="1.x" on javac.source / javac.target lines
            sed -i '/javac\.source/s/value="1\.[56]"/value="1.8"/g' "$buildxml"
            sed -i '/javac\.target/s/value="1\.[56]"/value="1.8"/g' "$buildxml"
            # Handle ant.build.javac.source / ant.build.javac.target global properties
            # (present in some Closure bug versions as property elements with value= attribute)
            sed -i 's/ant\.build\.javac\.source" value="1\.[56]"/ant.build.javac.source" value="1.8"/g' "$buildxml"
            sed -i 's/ant\.build\.javac\.target" value="1\.[56]"/ant.build.javac.target" value="1.8"/g' "$buildxml"
        fi
        # Patch all build.properties files under lib/rhino (any depth)
        # Closure has two rhino layouts:
        #   (a) lib/rhino/build.properties (older bugs)
        #   (b) lib/rhino/src/mozilla/js/rhino/build.properties (newer bugs)
        if [[ -d "$workdir/lib/rhino" ]]; then
            find "$workdir/lib/rhino" -name "build.properties" 2>/dev/null | while read -r rhinoprops; do
                sed -i 's/source-level=1\.[56]/source-level=1.8/g' "$rhinoprops"
                sed -i 's/target-jvm=1\.[56]/target-jvm=1.8/g' "$rhinoprops"
                sed -i 's/source-level 1\.[56]/source-level 1.8/g' "$rhinoprops"
                sed -i 's/target-jvm 1\.[56]/target-jvm 1.8/g' "$rhinoprops"
            done || true
        fi
        # Also patch all nested rhino build.xml files that have source/target attrs
        if [[ -d "$workdir/lib/rhino" ]]; then
            find "$workdir/lib/rhino" -name "build.xml" 2>/dev/null | while read -r rxml; do
                sed -i 's/source="1\.[56]"/source="1.8"/g' "$rxml"
                sed -i 's/target="1\.[56]"/target="1.8"/g' "$rxml"
            done || true
        fi
    fi

    # JacksonDatabind projects: bump source/target in maven-build.xml
    if [[ "$PROJECT" == "JacksonDatabind" ]]; then
        local mvnbuild="$workdir/maven-build.xml"
        if [[ -f "$mvnbuild" ]]; then
            sed -i 's/source="1\.[5678]"/source="1.8"/g' "$mvnbuild"
            sed -i 's/target="1\.[5678]"/target="1.8"/g' "$mvnbuild"
        fi
    fi

    # Jsoup projects: bump source/target in maven-build.xml (may use 1.6 or 1.7)
    if [[ "$PROJECT" == "Jsoup" ]]; then
        local mvnbuild="$workdir/maven-build.xml"
        if [[ -f "$mvnbuild" ]]; then
            sed -i 's/source="1\.[5678]"/source="1.8"/g' "$mvnbuild"
            sed -i 's/target="1\.[5678]"/target="1.8"/g' "$mvnbuild"
        fi
    fi

    # Gson projects: bump source/target in gson/maven-build.xml
    if [[ "$PROJECT" == "Gson" ]]; then
        local mvnbuild="$workdir/gson/maven-build.xml"
        if [[ -f "$mvnbuild" ]]; then
            sed -i 's/source="1\.[5678]"/source="1.8"/g' "$mvnbuild"
            sed -i 's/target="1\.[5678]"/target="1.8"/g' "$mvnbuild"
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
# Find the actual built agent jar (supports 1.0.0-SNAPSHOT and 2.0.0-SNAPSHOT)
_find_jar() {
    local dir="$1" pattern="$2"
    # Prefer 2.x over 1.x
    local found
    found=$(find "$dir" -maxdepth 1 -name "$pattern" 2>/dev/null | sort -rV | head -1)
    echo "$found"
}
CROCHET_AGENT_JAR="$(_find_jar "$CROCHET_REPO/crochet-agent/target" "crochet-agent-*-SNAPSHOT.jar" | grep -v original || true)"
CROCHET_DEBUG_JAR="$(_find_jar "$CROCHET_REPO/crochet-debug/target" "crochet-debug-*-SNAPSHOT-standalone.jar" || true)"
# Fallback to hardcoded if find returned nothing
[[ -z "$CROCHET_AGENT_JAR" ]] && CROCHET_AGENT_JAR="$CROCHET_REPO/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar"
[[ -z "$CROCHET_DEBUG_JAR" ]] && CROCHET_DEBUG_JAR="$CROCHET_REPO/crochet-debug/target/crochet-debug-2.0.0-SNAPSHOT-standalone.jar"

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

# If --model was specified, pass it through; otherwise probe the default model ID
# so the output JSON accurately records which model was used.
if [[ -n "$MODEL" ]]; then
    CLAUDE_CMD+=(--model "$MODEL")
    MODEL_DEFAULT_USED=false
    EFFECTIVE_MODEL="$MODEL"
else
    MODEL_DEFAULT_USED=true
    # Probe: ask claude what model it is using with --output-format json
    EFFECTIVE_MODEL=$(claude --print --output-format json -p "model id" 2>/dev/null | \
        python3 -c "
import json, sys
try:
    obj = json.load(sys.stdin)
    usage = obj.get('modelUsage', {})
    if usage:
        print(list(usage.keys())[0])
    else:
        print('claude-opus-4-7')
except Exception:
    print('claude-opus-4-7')
" 2>/dev/null || echo "claude-opus-4-7")
fi

log "  Model: $EFFECTIVE_MODEL (default_used=$MODEL_DEFAULT_USED)"

# Append seed if provided.  The claude CLI accepts --session-id with a UUID;
# we generate a deterministic UUID from the run parameters so each (bug, condition,
# seed) triple produces a distinct, reproducible session that won't reuse cached
# session state from prior runs.
if [[ -n "$SEED" ]]; then
    # Generate a UUID5-like hex string from bug+condition+seed using md5
    SEED_UUID=$(printf '%s-%s-%s' "$BUG_ID" "$CONDITION" "$SEED" | md5sum | awk '{print $1}' | \
        sed 's/^\(........\)\(....\)\(....\)\(....\)\(............\)$/\1-\2-\3-\4-\5/')
    echo "Run seed: $SEED (session: $SEED_UUID)" > "$WORKDIR/seed.txt"
    CLAUDE_CMD+=(--session-id "$SEED_UUID")
fi

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

# ── Step 9: Capture baseline failures BEFORE running the scored test ───────────
# Defects4J snapshots may have pre-existing failures under JDK 21 that are not
# caused by the agent's patch.  We must subtract these from the post-trial
# failure set so that pre-existing failures don't count as "regressions".
# Strategy: run the full suite twice (two baseline passes) and take the UNION of
# failures — any test that fails in either pass is considered a baseline failure.
# This absorbs flaky tests that fail randomly and prevents noisy false-positive
# regressions.
log "Step 9a: Capturing baseline failing tests (2× for flakiness) ..."

BASELINE_LOG1="$WORKDIR/baseline-test-1.log"
BASELINE_LOG2="$WORKDIR/baseline-test-2.log"
BASELINE_FAILING="$WORKDIR/baseline-failing-tests.txt"

# First baseline pass
(cd "$BUGGY_WORKDIR" && "$D4J_BIN" test 2>&1) > "$BASELINE_LOG1" || true

# Second baseline pass (catches flaky failures)
(cd "$BUGGY_WORKDIR" && "$D4J_BIN" test 2>&1) > "$BASELINE_LOG2" || true

# Union of both passes → conservative baseline (anything failing in either run)
python3 -c "
import re, sys
def extract_failures(logfile):
    try:
        with open(logfile) as f:
            content = f.read()
    except Exception:
        return set()
    return set(m.strip() for m in re.findall(r'^\s+- (.+)$', content, re.MULTILINE))

f1 = extract_failures('$BASELINE_LOG1')
f2 = extract_failures('$BASELINE_LOG2')
union = sorted(f1 | f2)
for t in union:
    print(t)
" > "$BASELINE_FAILING" 2>/dev/null || true

BASELINE_FAIL_COUNT=$(wc -l < "$BASELINE_FAILING" | tr -d ' ')
log "  Baseline: $BASELINE_FAIL_COUNT failing tests (union of 2 passes — will be subtracted from post-trial regressions)"

# ── Step 9b: Compile-check after agent patch ──────────────────────────────────
log "Step 9b: Compile-checking after agent's patch ..."
COMPILE_FAIL=false
COMPILE_LOG="$WORKDIR/post-compile.log"
if ! (cd "$BUGGY_WORKDIR" && "$D4J_BIN" compile 2>&1) > "$COMPILE_LOG"; then
    COMPILE_FAIL=true
    log "  COMPILE FAILED — agent's patch breaks compilation; skipping test phase."
fi

# ── Step 9c: Score — test pass/fail ───────────────────────────────────────────
log "Step 9c: Scoring — running failing test against agent's state ..."

PRIMARY_PASS=false
AGENT_INDUCED_REGRESSIONS="[]"
REGRESSION_COUNT=0
TEST_PASS=false

if [[ "$COMPILE_FAIL" == "true" ]]; then
    log "  STRICT SCORE: FAIL (compile failed; patch is invalid)."
else
    POST_TEST_LOG="$WORKDIR/post-test.log"
    (cd "$BUGGY_WORKDIR" && "$D4J_BIN" test -t "$FAILING_TEST" 2>&1) > "$POST_TEST_LOG" || true

    if grep -q "Failing tests: 0" "$POST_TEST_LOG"; then
        PRIMARY_PASS=true
        log "  PRIMARY: Test PASSES after agent intervention."
    else
        log "  PRIMARY: Test still FAILS after agent intervention."
    fi

    # Always run the full test suite to detect agent-induced regressions.
    # test_pass is STRICT: requires the originally-failing test to pass AND zero
    # *agent-induced* (previously-passing tests that now fail) regressions.
    # Pre-existing baseline failures are excluded.
    # Rationale: an agent that breaks 71 pre-existing JDK-21 incompatible tests
    # has not introduced any new regressions; an agent that passes the target by
    # breaking truly-passing tests has shifted the failure and must not score PASS.
    POSTTRIAL_FAILING="$WORKDIR/posttrial-failing-tests.txt"
    AGENT_REGRESSIONS_FILE="$WORKDIR/agent-regressions.txt"
    REGRESSION_LOG="$WORKDIR/regression.log"

    log "  Running full test suite to check for agent-induced regressions ..."
    (cd "$BUGGY_WORKDIR" && "$D4J_BIN" test 2>&1) > "$REGRESSION_LOG" || true

    # Extract post-trial failing tests
    python3 -c "
import re
with open('$REGRESSION_LOG') as f:
    content = f.read()
failing = sorted(set(m.strip() for m in re.findall(r'^\s+- (.+)$', content, re.MULTILINE)))
for t in failing:
    print(t)
" > "$POSTTRIAL_FAILING" 2>/dev/null || true

    # Agent-induced regressions = post-trial failures NOT in baseline.
    # comm -23 requires sorted input (both files are sorted by construction).
    comm -23 "$POSTTRIAL_FAILING" "$BASELINE_FAILING" > "$AGENT_REGRESSIONS_FILE" 2>/dev/null || true

    # Also remove the primary failing test itself from the regressions list:
    # if it was in baseline (expected — it's the bug's failing test) it's already
    # excluded; but if it appears in post-trial it means the primary did NOT pass,
    # which is already captured by PRIMARY_PASS=false.  Either way it's not an
    # agent-induced regression.
    AGENT_INDUCED_REGRESSIONS=$(python3 -c "
import json
with open('$AGENT_REGRESSIONS_FILE') as f:
    lines = [l.strip() for l in f if l.strip()]
# Exclude the primary failing test from the regression list
orig = '$FAILING_TEST'
lines = [l for l in lines if l != orig]
print(json.dumps(lines))
" 2>/dev/null || echo "[]")

    # Write to temp file to avoid any quoting issues with test names
    echo "$AGENT_INDUCED_REGRESSIONS" > "$WORKDIR/agent-induced-regressions-tmp.json"
    REGRESSION_COUNT=$(python3 -c "import json; print(len(json.load(open('$WORKDIR/agent-induced-regressions-tmp.json'))))" 2>/dev/null || echo "0")

    if [[ "$PRIMARY_PASS" == "true" && "$REGRESSION_COUNT" == "0" ]]; then
        TEST_PASS=true
        log "  STRICT SCORE: PASS (target test passes, zero agent-induced regressions)."
    elif [[ "$PRIMARY_PASS" == "true" ]]; then
        log "  STRICT SCORE: FAIL (target test passes but $REGRESSION_COUNT agent-induced regression(s) — fix is not clean)."
    else
        log "  STRICT SCORE: FAIL (target test still failing)."
    fi
fi

# Compat alias: regressed_tests → agent_induced_regressions (both written to output)
REGRESSED_TESTS="$AGENT_INDUCED_REGRESSIONS"
REGRESSED_TESTS_FILE="$WORKDIR/regressed-tests.json"
echo "$REGRESSED_TESTS" > "$REGRESSED_TESTS_FILE"

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
# Write the agent-induced regressions JSON array to a file for safe reading
AGENT_INDUCED_FILE="$WORKDIR/agent-induced-regressions.json"
echo "$AGENT_INDUCED_REGRESSIONS" > "$AGENT_INDUCED_FILE"

METADATA_FILE="$WORKDIR/metadata.json"
python3 -c "
import json, datetime

def read_json_file(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return default

meta = {
    'bug': '$BUG_ID',
    'condition': '$CONDITION',
    'model': '$EFFECTIVE_MODEL',
    'model_default_used': $( [[ "$MODEL_DEFAULT_USED" == "true" ]] && echo "True" || echo "False" ),
    'started_at': datetime.datetime.fromtimestamp($AGENT_START_TS, tz=datetime.timezone.utc).isoformat(),
    'duration_seconds': $DURATION,
    'tool_calls': $TOOL_CALL_COUNT,
    'compile_fail': $( [[ "$COMPILE_FAIL" == "true" ]] && echo "True" || echo "False" ),
    'primary_pass': $( [[ "$PRIMARY_PASS" == "true" ]] && echo "True" || echo "False" ),
    'test_pass': $( [[ "$TEST_PASS" == "true" ]] && echo "True" || echo "False" ),
    'baseline_failing_count': $BASELINE_FAIL_COUNT,
    'agent_induced_regressions': read_json_file('$AGENT_INDUCED_FILE', []),
    'regressed_tests': read_json_file('$REGRESSED_TESTS_FILE', []),
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
meta['baseline_failing_tests'] = [l for l in read_file('$BASELINE_FAILING').splitlines() if l.strip()]
meta['compile_log'] = read_file('$COMPILE_LOG')

with open('$OUT_PATH', 'w') as f:
    json.dump(meta, f, indent=2, default=str)

print('[run-trial] Output written to $OUT_PATH')
"

log "Done. Trial complete: bug=$BUG_ID condition=$CONDITION model=$EFFECTIVE_MODEL test_pass=$TEST_PASS duration=${DURATION}s tool_calls=$TOOL_CALL_COUNT diagnosis_quality=$DIAGNOSIS_QUALITY/5"
