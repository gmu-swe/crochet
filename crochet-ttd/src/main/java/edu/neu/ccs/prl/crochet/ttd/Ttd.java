package edu.neu.ccs.prl.crochet.ttd;

import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * Time-travel debugger primitive on top of Crochet's checkpoint/rollback.
 *
 * <p><b>Usage:</b>
 * <pre>
 *   Ttd.session(state, () -&gt; {
 *       state.value = 1;
 *       Ttd.breakpoint();   // hit 1
 *       state.value = 2;
 *       Ttd.breakpoint();   // hit 2
 *       state.value = 3;
 *       Ttd.breakpoint();   // hit 3
 *   });
 * </pre>
 * The session takes a Crochet checkpoint of {@code state} on entry. On each
 * {@link #breakpoint()}, control passes to a REPL where the user can inspect
 * state and step forward, backward, or jump to an arbitrary breakpoint
 * index. Backward / goto-prior are implemented by Crochet-rolling-back to
 * the entry checkpoint and re-executing the body, silently skipping
 * breakpoints until the target index.
 *
 * <p><b>Limitations (Phase 0):</b>
 * <ul>
 *   <li>Single-threaded body only — multi-thread requires Fray-style
 *       deterministic scheduling, out of scope for this prototype.</li>
 *   <li>Body must be deterministic on replay — no
 *       {@code System.currentTimeMillis()}, {@code Random},
 *       {@code System.identityHashCode()} (unless backed by Crochet's
 *       hashcode-mapping), file/socket IO, etc.</li>
 *   <li>Backward stepping cannot cross out of {@code Ttd.session()}'s
 *       lambda boundary — Crochet rolls back the heap, not the call
 *       stack. The session's containing method's locals are NOT restored.</li>
 *   <li>Only the explicitly-tracked root is checkpointed. Mutations to
 *       static state or other objects are not rolled back; if your body
 *       mutates them, replay will see the post-mutation state, not the
 *       pre-mutation state. For full-program checkpointing, use
 *       {@link CheckpointRollbackAgent#checkpointAll()} pattern from a
 *       higher-level harness.</li>
 * </ul>
 */
public final class Ttd {

    private Ttd() {}

    private static final ThreadLocal<TtdContext> CTX = new ThreadLocal<>();

    /**
     * Run {@code body} in a TTD session anchored on a Crochet checkpoint of
     * {@code root}. The body executes normally until it calls
     * {@link #breakpoint()}, at which point a REPL takes over.
     *
     * @param root the object whose state is checkpointed/rolled back across
     *             back-stepping
     * @param body the body to execute
     */
    public static void session(Object root, Runnable body) {
        sessionWithRepl(root, Repl.fromStdin(), body);
    }

    /**
     * Same as {@link #session(Object, Runnable)} but lets the caller inject
     * a custom REPL frontend. Used by tests to script command sequences;
     * may also be used by IDE integrations to substitute a non-stdin
     * frontend.
     */
    public static void sessionWithRepl(Object root, Repl repl, Runnable body) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        if (repl == null) {
            throw new IllegalArgumentException("repl must not be null");
        }
        if (CTX.get() != null) {
            throw new IllegalStateException("Ttd.session does not nest");
        }
        TtdContext ctx = new TtdContext(root, repl);
        ctx.checkpointVersion = CheckpointRollbackAgent.checkpoint(root);
        CTX.set(ctx);
        try {
            while (true) {
                ctx.currentIdx = 0;
                try {
                    body.run();
                    // Body completed without further back-step. Tell REPL,
                    // give user a final inspect-and-quit chance.
                    ctx.repl.println("[ttd] body completed (" + ctx.currentIdx
                            + " breakpoints hit)");
                    Repl.Action a = ctx.repl.prompt(ctx, /*atEnd=*/true);
                    if (a.kind == Repl.Action.Kind.RESTART) {
                        rollbackAndRecheckpoint(ctx);
                        ctx.targetStop = a.targetIdx;
                        continue;
                    }
                    return;
                } catch (Restart r) {
                    rollbackAndRecheckpoint(ctx);
                    // ctx.targetStop has been set by the REPL prior to throw
                } catch (Quit q) {
                    return;
                }
            }
        } finally {
            CTX.remove();
        }
    }

    /**
     * Pause point. Called from inside a {@link #session} body. The first
     * call has index 1, the second has index 2, etc. On replay, calls with
     * index strictly less than the REPL's target stop index return
     * immediately without prompting.
     */
    public static void breakpoint() {
        TtdContext ctx = CTX.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "Ttd.breakpoint() called outside a Ttd.session()");
        }
        ctx.currentIdx++;
        if (ctx.currentIdx < ctx.targetStop) {
            return;  // silent replay
        }
        // Hit. Drop to REPL.
        Repl.Action a = ctx.repl.prompt(ctx, /*atEnd=*/false);
        switch (a.kind) {
            case CONTINUE:
                ctx.targetStop = a.targetIdx;
                return;
            case RESTART:
                ctx.targetStop = a.targetIdx;
                throw new Restart();
            case QUIT:
                throw new Quit();
        }
    }

    private static void rollbackAndRecheckpoint(TtdContext ctx) {
        CheckpointRollbackAgent.rollback(ctx.root, ctx.checkpointVersion);
        ctx.checkpointVersion = CheckpointRollbackAgent.checkpoint(ctx.root);
    }

    /** Internal state per TTD session. Package-visible for the REPL. */
    static final class TtdContext {
        final Object root;
        final Repl repl;
        int checkpointVersion;
        int currentIdx;          // breakpoint index of the most recent hit
        int targetStop = 1;      // next stop target (default: pause at first BP)

        TtdContext(Object root, Repl repl) {
            this.root = root;
            this.repl = repl;
        }
    }

    /** Thrown by breakpoint() to unwind the body for back-stepping. */
    static final class Restart extends RuntimeException {
        Restart() { super(); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    /** Thrown by breakpoint() when the user types `quit`. */
    public static final class Quit extends RuntimeException {
        Quit() { super("ttd quit"); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
}
