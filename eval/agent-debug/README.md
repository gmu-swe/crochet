# eval/agent-debug — Agent Debugging Benchmark

Harness for the agent-debugging benchmark (Phase I, Stage 3). Each "trial" pairs
one bug from `corpus.json` with one condition (C1/C2/C3), spawns a debugging agent
(Claude via `claude -p`), and scores the outcome.

## Prerequisites

- `defects4j` at `~/defects4j` (or `$DEFECTS4J_HOME`)  
- Java 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (or `$JAVA_HOME`)  
- `ANTHROPIC_API_KEY` set (required by `claude` CLI)  
- Perl module `String::Interpolate` (`cpanm String::Interpolate`)  
- `claude` CLI on PATH (`which claude` should succeed)  
- For C3: instrumented JDK at `/tmp/jdk-inst` and crochet jars built (`mvn install -DskipTests`)

## Running one trial

```bash
cd eval/agent-debug
./run-trial.sh --bug Lang-1 --condition C2 --out /tmp/trial-out/Lang-1-C2.json
```

Options:

| Flag | Default | Description |
|------|---------|-------------|
| `--bug <id>` | (required) | Bug ID from corpus.json (e.g. `Lang-1`, `Math-5`) |
| `--condition C1\|C2\|C3` | (required) | Condition (see below) |
| `--out <path>` | (required) | Output JSON file |
| `--max-tool-calls N` | 80 | Cap agent tool calls |
| `--workdir <path>` | `/tmp/trial-<bug>-<cond>` | Override trial worktree path |
| `--keep-workdir` | false | Keep worktree on exit |
| `--dry-run` | false | Set up + verify bug reproduces; skip agent |

## Conditions

| Condition | Tools available | Debugging strategy |
|-----------|----------------|--------------------|
| **C1** | Bash, Read, Write, Edit | Print-style debugging only (no interactive debugger) |
| **C2** | Same + `jdb` via Bash | Standard JDI/JDWP debugger; step/inspect/breakpoints |
| **C3** | Same + `crochet-debug` via Bash | Crochet time-travel debugger: `back-step`, `capture-stack`, `diff` |

Each condition gets a different system prompt from `prompts/condition-C{1,2,3}.md`.
The only differences are: which tools are mentioned as available, and the
debugging strategy section.

## Output JSON schema

```json
{
  "bug": "Lang-1",
  "condition": "C2",
  "started_at": "2026-05-21T...",
  "duration_seconds": 743,
  "tool_calls": 47,
  "test_pass": true,
  "regressed_tests": [],
  "diagnosis_quality": 4,
  "agent_patch": "<diff>",
  "agent_log": "<full session log (stream-json)>",
  "agent_stderr": "<stderr from claude CLI>",
  "judge_reasoning": "<llm-as-judge output>",
  "verify_log": "<defects4j test output before agent>",
  "agent_exit_code": 0,
  "bug_reproduced_pretest": true
}
```

`diagnosis_quality` is 1–5 (LLM-as-judge against `fix_summary` from corpus.json).
`test_pass` is binary: did the originally-failing test pass after the agent's changes?

## Agent approach: headless claude CLI

The harness uses `claude -p` (headless / print mode) to run each trial.
This was chosen because `claude` CLI is available and configured on this machine.

Command shape:
```bash
claude -p \
  --dangerously-skip-permissions \
  --allowed-tools "Bash,Read,Write,Edit" \
  --max-turns 80 \
  --output-format stream-json \
  --no-session-persistence \
  --add-dir <buggy-workdir> \
  < prompt.md
```

`--dangerously-skip-permissions` is required for non-interactive Bash execution.
`--max-turns` is the tool-call budget cap.

## Full Stage 3 sweep (30 trials)

The full sweep is 10 bugs × 3 conditions = 30 trials. Run them in parallel:

```bash
mkdir -p /tmp/trial-out

BUGS="Lang-1 Lang-10 Lang-26 Time-4 Time-11 Math-5 Math-27 Math-3 Math-10 Closure-1"
CONDITIONS="C1 C2 C3"

for bug in $BUGS; do
  for cond in $CONDITIONS; do
    outfile="/tmp/trial-out/${bug}-${cond}.json"
    if [[ -f "$outfile" ]]; then
      echo "Skipping $bug-$cond (already done)"
      continue
    fi
    echo "Launching $bug $cond ..."
    ./run-trial.sh --bug "$bug" --condition "$cond" --out "$outfile" \
      > "/tmp/trial-out/${bug}-${cond}.log" 2>&1 &
  done
done

wait
echo "All trials complete."
```

**Caution:** Running all 30 in parallel may exceed API rate limits. Consider
batching by condition or throttling with `sem` / `xargs -P 5`.

## Smoke test

See `smoke-test-output.json` for the Lang-1 × C2 smoke-test result.

Run it yourself:
```bash
./run-trial.sh --bug Lang-1 --condition C2 \
  --out /tmp/smoke-test.json \
  --max-tool-calls 80 \
  --keep-workdir
```

## File layout

```
eval/agent-debug/
├── corpus.json               # 10 verified bugs (from I.2)
├── run-trial.sh              # Main trial harness
├── judge-prompt.md           # LLM-as-judge prompt template
├── smoke-test-output.json    # Smoke-test result (Lang-1 × C2)
├── README.md                 # This file
└── prompts/
    ├── condition-C1.md       # No-debugger prompt template
    ├── condition-C2.md       # jdb prompt template
    └── condition-C3.md       # Crochet TTD prompt template
```

## Crochet TTD gaps observed during C3 dry-runs

See the builder's final report for a summary of I.1 gaps that surfaced during
C3 prompt authoring. Key items:

1. **No automated `@TimeTravelBody` injection** — the agent must manually annotate
   and rebuild, which costs tool calls. A `crochet-debug --wrap-method` flag would
   help here.

2. **SocketRepl requires code change** — the target program must call
   `Ttd.sessionWithRepl(...)` instead of `Ttd.session(...)`. For Defects4J projects
   this means patching a library class, not just a test. Workaround: agent patches
   the library source; adds friction.

3. **No JDWP launch helper** — for C3, the agent must construct the full
   `-agentlib:jdwp=...` command and find the right classpath. A
   `crochet-debug --launch-test <d4j-workdir> <test>` flag would eliminate this.

4. **crochet-debug jar requires `--add-modules jdk.jdi`** — easy to forget; should
   be baked into a wrapper script.
