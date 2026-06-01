# crochet-ttd

Time-travel debugger primitive on top of Crochet's checkpoint/rollback.
Single-threaded; deterministic body required.

- **Phase 0** — programmatic `Ttd.breakpoint()` calls in user code.
- **Phase 1** — `@TimeTravelBody` annotation + javaagent that
  auto-instruments every line of the annotated method as an implicit
  pause point. No source edits beyond the one annotation.

## Use

### Phase 0 — explicit breakpoints

```java
import edu.neu.ccs.prl.crochet.ttd.Ttd;

class MyDebugSession {
    static final class State {
        int value;
        String tag;
    }

    static void main(String[] args) {
        State state = new State();
        Ttd.session(state, () -> {
            state.value = 1;
            state.tag = "first";
            Ttd.breakpoint();        // pause here, REPL takes stdin

            state.value = 2;
            state.tag = "second";
            Ttd.breakpoint();        // pause here

            state.value = 3;
            state.tag = "third";
            Ttd.breakpoint();        // pause here
        });
    }
}
```

Run with the Crochet agent attached:

```bash
java -javaagent:crochet-agent.jar --add-reads java.base=jdk.unsupported MyDebugSession
```

### Phase 1 — auto-instrumented every-line stepping

```java
import edu.neu.ccs.prl.crochet.ttd.Ttd;
import edu.neu.ccs.prl.crochet.ttd.TimeTravelBody;

class MyDebugSession {
    static final class State { int value; String tag; }

    @TimeTravelBody
    static void instrumentedBody(State state) {
        state.value = 1;        // line marker fires here
        state.tag = "first";    // line marker fires here
        state.value = 2;        // line marker fires here
        state.tag = "second";   // line marker fires here
    }

    static void main(String[] args) {
        State state = new State();
        Ttd.session(state, () -> instrumentedBody(state));
    }
}
```

Run with BOTH agents (TTD first so it transforms before Crochet sees the bytecode):

```bash
java -javaagent:crochet-ttd.jar \
     -javaagent:crochet-agent.jar \
     --add-reads java.base=jdk.unsupported \
     MyDebugSession
```

The TTD agent inserts a `Ttd.lineHit` call at every entry in the
method's `LineNumberTable`. The REPL announces `at step N
ClassName.method(desc):line` instead of the Phase-0 `at breakpoint K`.
Outside a `Ttd.session`, `lineHit` is a silent no-op so production
code (or other tests) loaded with the TTD agent attached pays only
the cost of a static call per line.

At each `Ttd.breakpoint()` the REPL takes over:

```
[ttd] at breakpoint 1
(ttd) inspect
[ttd] State {
  value = 1
  tag = "first"
}
(ttd) next
[ttd] at breakpoint 2
(ttd) inspect
[ttd] State {
  value = 2
  tag = "second"
}
(ttd) back
[ttd] at breakpoint 1
(ttd) inspect
[ttd] State {
  value = 1
  tag = "first"          <-- Crochet rolled back state to BP 1
}
(ttd) quit
```

## REPL commands

| command | shorthand | description |
|---|---|---|
| `next` | `n` | continue to next breakpoint |
| `back` | `b` | rollback heap, replay to previous breakpoint |
| `goto N` | `g N` | jump to breakpoint N (forward continues, backward replays) |
| `inspect` | `i` | dump tracked root's fields via reflection |
| `where` | `w` | print current breakpoint index |
| `quit` | `q` | exit session |
| `help` | `h` | this list |

## Back-step mechanism

As of Phase B (units B.3–B.4), back-stepping is driven by a
CPS (continuation-passing style) bytecode transformation emitted by the
`LineMarkerTransformer`. Each `@TimeTravelBody` method receives a
**dispatch prelude** at method entry and a **save-frame snippet** at
every save-point (one per source line / callsite). On back-step, the
session snapshots the current resume-frame deque, performs rollback,
pre-stages the frames on the deque, and re-invokes the body. The body's
dispatch prelude table-jumps directly to the target save-point BCI,
restoring live locals from the frame, and execution resumes at the
correct source line without re-running earlier lines.

### Deprecated: `Restart`-throw back-step path

The legacy `Restart`-throw back-step path (active when
`-Dcrochet.ttd.backstep=restart` is set) is **deprecated** as of Phase B
and will be removed in unit C.1. Under the legacy path, back-stepping
throws `Restart` to unwind the body, then replays the body from the
beginning, silently skipping breakpoints until the target index. The CPS
path (the new default) is more efficient: it restores locals from the
save-frame and resumes at the exact target BCI without re-running any
code.

The `restart` system-property override exists only to let pre-B.3 tests
continue to pass during Phase B. **Do not use** `-Dcrochet.ttd.backstep=restart`
in new code; it will not exist in C.1.

## Mechanism

`Ttd.session(root, body)`:
1. Take `Crochet.checkpoint(root)` on entry.
2. Run `body.run()`. Each `Ttd.breakpoint()` call increments a step
   counter and either pauses (if the counter has reached the REPL's
   target stop index) or returns silently (if the body is being
   replayed past an earlier point).
3. On `back` / `goto N` (with N less than current): the REPL throws a
   `Restart` exception; `session` catches it, rolls back `root` to the
   checkpoint, sets the new target stop, and re-runs `body`.
4. Forward stepping just sets a higher target and returns from the
   current `breakpoint()` call.

The body re-executes fully on each rollback, but the heap state of
`root` is restored, so subsequent breakpoints see consistent values.

## Limitations (Phase 0)

- **Single-threaded body only.** Multi-thread requires Fray-style
  deterministic scheduling; out of scope for this prototype.
- **Body must be deterministic on replay.** No `currentTimeMillis`,
  `Random`, IO, native calls. (Crochet's `hashCodeMapper` covers
  identity-hashcode determinism if integrated through the JDK
  pre-instrumented build path.)
- **Backward stepping cannot cross out of `Ttd.session()`'s lambda.**
  Crochet rolls back the heap, not the call stack.
- **Only the explicitly tracked root is checkpointed.** Mutations to
  other reachable objects are NOT rolled back unless they're inside
  `root`'s reachable graph (which Crochet's `checkpoint(root)` walks).
  For full-program checkpointing, use a higher-level harness that
  combines per-object checkpoint with `checkpointAll()` for static
  state.
- **No expression evaluator.** `inspect` dumps the root's fields by
  reflection; for non-root state, print from inside the body via
  `System.out` (Phase 1 will add an `inspect <expr>` evaluator).

## Future phases

- **Phase 1**: bytecode-instrumented step counter (no need for explicit
  `Ttd.breakpoint()` calls — each line of the target method becomes a
  pause point).
- **Phase 2**: structured timeline output for IDE consumption (Debug
  Adapter Protocol or similar).
- **Phase 3**: multi-threaded session under Fray's scheduler (the
  Tapestry-flavored "Architecture A" alternative — see
  `~/tapestry/docs/scope-and-applications.md`).
