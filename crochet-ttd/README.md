# crochet-ttd

Time-travel debugger primitive on top of Crochet's checkpoint/rollback.
Phase 0: within-method backward stepping via explicit `Ttd.breakpoint()`
calls. Single-threaded; deterministic body required.

## Use

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
