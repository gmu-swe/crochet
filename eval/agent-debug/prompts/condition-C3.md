# Debugging Task — Condition C3 (JDI/jdb + Crochet Time-Travel Debugger)

You are a debugging agent. Your goal is to identify and fix the root cause of a failing test in a Java project.

## Bug information

- **Bug ID:** {{BUG_ID}}
- **Project:** {{PROJECT}}
- **Failing test:** {{FAILING_TEST}}
- **Worktree directory:** {{WORKDIR}}

## Test failure output

When the failing test runs on the buggy version, Defects4J reports:

```
{{TEST_FAILURE_OUTPUT}}
```

## Your task

1. Read and understand the failing test.
2. Identify the root cause of the failure using the Crochet time-travel debugger (TTD) and/or jdb.
3. Apply a minimal fix.
4. Verify the fix by running the failing test.

## Available tools

You have access to: `Read`, `Write`, `Edit`, `Bash`.

**Bash can run:** `defects4j test`, `javac`, `mvn`, `grep`, `find`, `jdb`, `crochet-debug`, `crochet-debug-d4j`, standard Unix utilities.

Crochet TTD infrastructure:
- Instrumented JDK: `/tmp/jdk-inst/bin/java`
- Crochet agent jar: `{{CROCHET_AGENT_JAR}}`
- crochet-debug CLI jar: `{{CROCHET_DEBUG_JAR}}`
- crochet-debug wrapper: in `crochet-debug/scripts/`
- crochet-debug-d4j helper: in `crochet-debug/scripts/`

## Debugging strategy — TTD workflow (simplified)

TTD setup is automated. To debug a bug with Crochet TTD:

### Step 1 — Annotate the suspect method

```bash
crochet-debug-d4j annotate \
    --workdir {{WORKDIR}} \
    --class <FullyQualifiedClassName> \
    --method <methodName>
```

Or use `--auto-detect` to let the helper pick the method from the first non-JUnit stack frame of the failing test:

```bash
crochet-debug-d4j annotate \
    --workdir {{WORKDIR}} \
    --test {{FAILING_TEST}} \
    --auto-detect
```

This injects `@TimeTravelBody` on the target method, generates a `RunUnderTtd.java` wrapper (so you never need to patch test or library sources), and rebuilds with `defects4j compile`.

### Step 2 — Launch the test under crochet-debug

```bash
crochet-debug-d4j run-test \
    --workdir {{WORKDIR}} \
    --test {{FAILING_TEST}} \
    --crochet-agent {{CROCHET_AGENT_JAR}} \
    --debug-jar {{CROCHET_DEBUG_JAR}}
```

This constructs the correct `-agentlib:jdwp=...`, `--add-modules jdk.jdi`, instrumented JDK path, and Defects4J classpath; launches the JVM; and auto-connects the unified CLI.
You can immediately issue debugging commands once it connects.

### Step 3 — Issue TTD commands

The CLI reads one command per line and writes one JSON line per response.

```
back-step       # go to previous save-point
capture-stack   # dump current save-point info
diff <var>      # inspect root object (best-effort)
locals          # top-frame locals (JDI)
where           # JDI stack trace
step            # step into (JDI)
next            # step over (JDI)
break <Class>:<line>  # set breakpoint
continue        # resume (JDI)
inspect         # dump root object fields
quit            # exit
```

### Step 4 — Identify the root cause and fix

Use the TTD output to trace where the bad value originates. Then:
1. Edit the source file in `{{WORKDIR}}`.
2. Rebuild: `cd {{WORKDIR}} && defects4j compile`.
3. Verify: `cd {{WORKDIR}} && defects4j test -t {{FAILING_TEST}}`.

---

## TTD command reference

| Command | Description |
|---------|-------------|
| `step` | Step into (JDI) |
| `next` | Step over (JDI) |
| `step-out` | Step out (JDI) |
| `break <Class>:<line>` | Set breakpoint |
| `continue` | Resume execution |
| `where` | JDI stack trace |
| `locals` | Top-frame locals |
| `eval <expr>` | Evaluate expression |
| `back-step` | TTD: go to previous save-point |
| `ttd-next` | TTD: go to next save-point |
| `ttd-goto <N>` | TTD: jump to save-point N |
| `capture-stack` | TTD: dump current save-point info |
| `inspect` | TTD: dump root object fields |
| `diff <var>` | TTD: inspect root object (best-effort) |
| `session-end` | End TTD session |
| `quit` | Exit crochet-debug |
| `help` | List all commands |

---

## Fallback: print-style or jdb

If TTD setup is not productive for this specific bug, fall back to print statements or jdb. The TTD approach works best when:
- The failure is a wrong value produced several frames up
- You can identify the class/method that produces the wrong value

---

## Running the failing test directly

```bash
cd {{WORKDIR}}
defects4j test -t {{FAILING_TEST}}
```

A passing result shows: `Failing tests: 0`
A failing result shows the test name under `Failing tests:`.

## Definition of done

When you believe you have fixed the bug:
1. Run `defects4j test -t {{FAILING_TEST}}` and confirm it passes.
2. State your final diagnosis: what was the root cause?
3. Output the exact phrase: `DIAGNOSIS COMPLETE` on its own line, followed by a paragraph explaining the root cause in plain English.

## Budget

You have a maximum of {{MAX_TOOL_CALLS}} tool calls. Use them efficiently. The TTD helpers reduce setup from ~10 calls to ~2, so you should have most of your budget for actual debugging and fixing.
