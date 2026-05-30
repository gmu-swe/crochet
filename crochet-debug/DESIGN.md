# crochet-debug: Unified JDI/JDWP + Crochet TTD Bridge — Design

*Author: unit I.1 builder agent — 2026-05-21*

---

## Architecture: (b) Out-of-process unified CLI

The CLI launches the target JVM with both JDWP and the Crochet TTD socket REPL
enabled, then connects to both from its own process. Standard forward-debugging
commands route to JDI; backward/TTD commands route to the Crochet socket REPL.

```
┌──────────────────────────────────────────────────────────────┐
│  Target JVM                                                  │
│                                                              │
│   ┌─────────────────────┐   ┌────────────────────────────┐  │
│   │  JDWP agent         │   │  SocketRepl (Ttd.session)  │  │
│   │  -agentlib:jdwp     │   │  TCP port 5006             │  │
│   │  TCP port 5005      │   │  line-oriented protocol     │  │
│   └──────────┬──────────┘   └─────────────┬──────────────┘  │
│              │                            │                  │
└──────────────┼────────────────────────────┼──────────────────┘
               │ JDI wire                   │ raw socket
┌──────────────▼────────────────────────────▼──────────────────┐
│  crochet-debug CLI (crochet-debug/target/crochet-debug.jar)  │
│                                                              │
│   UnifiedCommandRouter                                       │
│   ├── JdiBackend  ──▶  com.sun.jdi.VirtualMachine           │
│   └── CrochetBackend ──▶  SocketRepl client                 │
│                                                              │
│   stdin: one command per line                                │
│   stdout: one JSON line per response                         │
└──────────────────────────────────────────────────────────────┘
```

Rationale for architecture (b):
- Agents driving a benchmark trial run a CLI subprocess naturally via stdin/stdout.
- JDI and Crochet are independent transports with independent lifecycle; keeping
  them separate in the CLI avoids complex in-process multiplexing.
- The socket REPL is the simplest extension to the existing `Repl` abstraction:
  `SocketRepl` implements the same `Repl` contract over a TCP stream.

---

## Crochet REPL Bridge

### Existing: `Repl` (in-process stdin/stdout)

`Repl` is a package-private class in `crochet-ttd` that reads from an
`InputStream` and writes to a `PrintStream`. It implements a line-oriented
command language. `Ttd.sessionWithRepl(root, repl, body)` accepts a custom
`Repl` instance.

### New: `SocketRepl`

`SocketRepl` is a new class in `crochet-ttd` that extends `Repl`'s constructor:
it binds a `ServerSocket` on a caller-supplied port, accepts one connection, and
wraps the socket's streams as its input/output. The rest of the REPL command
loop is inherited unchanged.

```java
// In target JVM code:
Ttd.sessionWithRepl(root, SocketRepl.onPort(5006), () -> { ... });
```

The CLI's `CrochetBackend` connects to port 5006 and speaks the existing text
protocol: send one-line command, receive text lines until the next `(ttd) `
prompt. JSON wrapping happens in `CrochetBackend` before writing to the CLI's
stdout.

### Why not a new protocol?

The existing REPL text protocol is simple and already handles all TTD commands
needed. Wrapping it in JSON at the CLI boundary is cleaner than designing a
new binary wire format. The REPL prompt `(ttd) ` serves as a reliable sync
point for the client.

---

## Launch contract

```bash
java -jar crochet-debug.jar \
    --target <jar-or-classpath> \
    --jdwp-port 5005 \
    --repl-port 5006 \
    [--jvm-args "..."]
```

The CLI:
1. Spawns the target JVM with:
   ```
   -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005
   -javaagent:<crochet-agent.jar>
   -jar <target-jar>
   ```
   (The target program must call `Ttd.sessionWithRepl(root, SocketRepl.onPort(5006), body)`)
2. Connects to JDWP via JDI (polls until the port is open, up to 10 seconds).
3. On first JDI suspend event, reports `{"ok":true,"event":"suspended","reason":"start"}`.
4. Reads commands from stdin, dispatches, writes JSON responses to stdout.

Alternatively, the CLI can attach to an already-running JVM:
```bash
java -jar crochet-debug.jar --attach --jdwp-port 5005 --repl-port 5006
```

---

## Command Catalog

All commands are single-line, space-separated tokens on stdin.
Responses are one JSON line on stdout.

### Forward commands (JDI)

| Command | Syntax | Response |
|---------|--------|----------|
| `step` | `step` | `{"ok":true,"result":"stepped","location":"Foo:42"}` |
| `next` | `next` | `{"ok":true,"result":"stepped","location":"Foo:42"}` |
| `step-out` | `step-out` | `{"ok":true,"result":"stepped","location":"Foo:42"}` |
| `break` | `break <class>:<line>` | `{"ok":true,"result":"breakpoint-set","location":"Foo:42"}` |
| `clear` | `clear <class>:<line>` | `{"ok":true,"result":"breakpoint-cleared","location":"Foo:42"}` |
| `continue` | `continue` | `{"ok":true,"result":"running"}` |
| `where` | `where` | `{"ok":true,"result":[{"class":"Foo","method":"bar","line":42}, ...]}` |
| `locals` | `locals` | `{"ok":true,"result":[{"name":"x","type":"int","value":"5"}, ...]}` |
| `eval` | `eval <expr>` | `{"ok":true,"result":"<value>"}` |
| `print` | `print <var>` | `{"ok":true,"result":"<value>"}` |

### Crochet TTD commands (socket REPL)

| Command | Syntax | Response |
|---------|--------|----------|
| `back-step` | `back-step` | `{"ok":true,"result":"back-stepped","location":"<lineCtx>"}` |
| `capture-stack` | `capture-stack` | `{"ok":true,"result":<stack-json>}` |
| `diff` | `diff <var>` | `{"ok":true,"result":"<diff-output>"}` |
| `session-start` | `session-start` | `{"ok":true,"result":"session-started"}` |
| `session-end` | `session-end` | `{"ok":true,"result":"session-ended"}` |
| `inspect` | `inspect` | `{"ok":true,"result":"<root-fields>"}` |
| `ttd-where` | `ttd-where` | `{"ok":true,"result":"<current-breakpoint>"}` |
| `ttd-next` | `ttd-next` | `{"ok":true,"result":"<breakpoint>"}` |
| `ttd-goto` | `ttd-goto <N>` | `{"ok":true,"result":"<breakpoint>"}` |

### Meta

| Command | Response |
|---------|----------|
| `quit` | `{"ok":true,"result":"bye"}` |
| `help` | `{"ok":true,"result":[...command list...]}` |

### Error shape

```json
{"ok":false,"error":"<human-readable message>"}
```

---

## Failure modes

| Failure | CLI behaviour |
|---------|--------------|
| JDWP connection refused | `{"ok":false,"error":"jdwp-connect-failed: <detail>"}` then exit |
| Crochet socket not yet listening | CLI retries up to 10s with 100ms backoff |
| Target JVM exits unexpectedly | `{"ok":false,"error":"target-exited: <code>"}` |
| JDI eval unsupported expr | `{"ok":false,"error":"eval-unsupported: <detail>"}` |
| REPL socket closed mid-session | `{"ok":false,"error":"repl-disconnected"}` |
| Unknown command | `{"ok":false,"error":"unknown-command: <cmd>"}` |

---

## Module layout

```
crochet-debug/
  pom.xml
  DESIGN.md                         ← this file
  README.md                         ← user-facing quick-start
  scripts/
    smoke-test.sh                   ← runs HelloBuggy smoke test
  src/
    main/java/edu/neu/ccs/prl/crochet/debug/
      CrochetDebugCli.java          ← main entry; stdin→stdout command loop
      UnifiedCommandRouter.java     ← routes commands to JdiBackend or CrochetBackend
      JdiBackend.java               ← thin JDI wrapper
      CrochetBackend.java           ← socket client for Crochet REPL
    test/java/edu/neu/ccs/prl/crochet/debug/
      fixture/HelloBuggy.java       ← buggy fixture for smoke test
```

`SocketRepl` lives in `crochet-ttd` (alongside `Repl.java`) because it needs
package-private access to the `Repl` constructor.

---

## Stability annotations

- `CrochetDebugCli`, `UnifiedCommandRouter`: `@Experimental` — the command set
  may evolve as the benchmark harness is refined.
- `JdiBackend`, `CrochetBackend`: `@Internal` — implementation detail.
- `SocketRepl`: `@Experimental` — the socket protocol may change.
