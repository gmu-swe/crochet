# crochet-debug — Unified JDI/JDWP + Crochet TTD Bridge

Unified command-line debugger for Java programs running under Crochet.
Standard forward-debugging commands go through JDI/JDWP; time-travel commands
go through Crochet's checkpoint/rollback engine.

Designed for use by automated benchmark agents (Phase I, condition 3): the CLI
reads one command per line from stdin and writes one JSON line per response to
stdout.

---

## Quick start

### 1. Build

```bash
mvn install -DskipTests -Dmaven.repo.local=/tmp/m2-i1
```

The standalone jar is `crochet-debug/target/crochet-debug-*-standalone.jar`.

### 2. Run the smoke test

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  bash crochet-debug/scripts/smoke-test.sh
```

### 3. Start a target program

The target JVM needs:
- JDWP enabled: `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005`
- Crochet agent: `-javaagent:crochet-agent.jar`
- TTD session with a `SocketRepl`: call `Ttd.sessionWithRepl(root, SocketRepl.onPort(5006), body)` in your code.

### 4. Attach the CLI

```bash
java --add-modules jdk.jdi \
     -jar crochet-debug/target/crochet-debug-*-standalone.jar \
     --attach \
     --jdwp-port 5005 \
     --repl-port 5006
```

Then type commands on stdin (or pipe them):

```
help
step
where
locals
back-step
inspect
quit
```

---

## Command reference

All responses are one JSON line on stdout.

### Forward commands (JDI)

| Command | Description | Response key |
|---------|-------------|--------------|
| `step` | Step into | `stepped` |
| `next` | Step over | `stepped` |
| `step-out` | Step out of current method | `stepped` |
| `break <class>:<line>` | Set breakpoint | `breakpoint-set` |
| `clear <class>:<line>` | Clear breakpoint | `breakpoint-cleared` |
| `continue` | Resume; wait for next suspend | `location` |
| `where` | JDI stack trace | `result` (JSON array) |
| `locals` | Top-frame locals | `result` (JSON array) |
| `eval <expr>` | Evaluate expression (`var`, `this.field`) | `value` |
| `print <var>` | Alias for `eval` | `value` |

### Crochet TTD commands

| Command | Description | Response key |
|---------|-------------|--------------|
| `back-step` | TTD: go back one breakpoint | `ttd-response` |
| `ttd-next` | TTD: go forward one breakpoint | `ttd-response` |
| `ttd-goto <N>` | TTD: jump to breakpoint N | `ttd-response` |
| `capture-stack` | TTD: current save-point info | `ttd-response` |
| `inspect` | TTD: dump root object fields | `ttd-response` |
| `ttd-where` | TTD: current breakpoint location | `ttd-response` |
| `diff <var>` | TTD: inspect root (best-effort) | `diff` |
| `session-end` | End TTD session | `result` |

### Meta

| Command | Response |
|---------|----------|
| `quit` | `{"ok":true,"result":"bye"}` |
| `help` | `{"ok":true,"result":[...]}` |

### Error shape

```json
{"ok":false,"error":"<human-readable message>"}
```

---

## Crochet REPL bridge

The target JVM exposes a `SocketRepl` on a TCP port. This is an extension of
Crochet's existing line-oriented text REPL, served over a socket instead of
stdin/stdout. The CLI's `CrochetBackend` connects to that port and drives the
same protocol, wrapping responses as JSON before writing to the CLI's stdout.

To use: call `Ttd.sessionWithRepl(root, SocketRepl.onPort(5006), body)` in
the target program instead of `Ttd.session(root, body)`.

---

## JDI note

JDI (`com.sun.jdi`) lives in `jdk.jdi`, which is not in the default module
graph. Always pass `--add-modules jdk.jdi` when launching the CLI:

```bash
java --add-modules jdk.jdi -jar crochet-debug.jar ...
```

---

## Architecture

See `DESIGN.md` for the full design rationale (architecture (b): out-of-process
unified CLI with two backends).
