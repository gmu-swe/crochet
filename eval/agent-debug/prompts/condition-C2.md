# Debugging Task — Condition C2 (JDI/jdb Debugger)

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
2. Identify the root cause of the failure using jdb or print-style debugging.
3. Apply a minimal fix.
4. Verify the fix by running the failing test.

## Available tools

You have access to: `Read`, `Write`, `Edit`, `Bash`.

**Bash can run:** `defects4j test`, `javac`, `mvn`, `grep`, `find`, `jdb`, standard Unix utilities.

## Debugging strategy for this condition

You may use **jdb** (the standard Java debugger) for interactive debugging, or use print-style debugging as a fallback.

### Using jdb

jdb is a command-line Java debugger included with the JDK. Typical workflow:

1. Compile the test class if needed.
2. Find the test runner's main class or use the Defects4J test runner.
3. Launch jdb with the target class:
   ```bash
   # Example: attach to a running process
   jdb -attach 5005
   # Or launch directly
   jdb -classpath <cp> <MainClass>
   ```
4. Set breakpoints: `stop at <ClassName>:<lineNumber>`
5. Run: `run`
6. Inspect locals: `locals`
7. Step through code: `step`, `next`, `step up`
8. Print values: `print <expr>`

**Note:** For Defects4J projects, it's often easier to add targeted print statements than to configure jdb from scratch. Use whichever approach is faster.

## Running the failing test

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

You have a maximum of {{MAX_TOOL_CALLS}} tool calls. Use them efficiently.
