package edu.neu.ccs.prl.crochet.ttd;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    // =========================================================================
    // B.2: ResumeFrame runtime — thread-local deque, session counter, interning
    // =========================================================================

    /**
     * Count of currently active TTD sessions across all threads.
     *
     * <p><b>Stand-in for C.1 {@code TTD_GEN}.</b>  This plain counter will be
     * replaced by C.1 with a generation integer whose parity encodes
     * checkpoint vs. rollback phase, mirroring the {@code VERSION_COUNTER}
     * convention in {@code CheckpointRollbackAgent}.  Until C.1 lands, this
     * counter is used only as a boolean test:
     * {@code TTD_ACTIVE_SESSIONS == 0} means "no session active — take the
     * zero-alloc early-return path in {@link #saveFrame} /
     * {@link #popResumeFrame}".
     *
     * <p>Incremented on session entry immediately after the session context is
     * set up; decremented unconditionally in the {@code finally} block so the
     * counter reaches 0 even if the body throws.
     *
     * <p>TODO: annotate with {@code @Internal} once unit A.4 merges.
     */
    public static volatile int TTD_ACTIVE_SESSIONS = 0;

    /**
     * Per-thread deque of {@link ResumeFrame} records pushed by
     * {@link #saveFrame}.
     *
     * <p>Using {@code withInitial(ArrayDeque::new)} so the supplier fires only
     * inside an active session (the {@code TTD_ACTIVE_SESSIONS == 0}
     * early-return guard fires before this is touched on cold paths).  The JIT
     * therefore sees a non-null {@code get()} result on every warm call site,
     * which eliminates the null-check branch from the compiled path.
     */
    private static final ThreadLocal<ArrayDeque<ResumeFrame>> FRAME_DEQUE =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Process-lifetime interning table: {@code "className.methodName(descriptor)"}
     * → dense {@code int} method id.
     *
     * <p>Populated at class-load time by the B.3 transformer via
     * {@link #internMethodId(String)}.  Ids are stable for the process
     * lifetime once assigned — a class can only be loaded once, so the same
     * key is never re-assigned a different id after the first call.
     */
    private static final ConcurrentHashMap<String, Integer> METHOD_IDS =
            new ConcurrentHashMap<>();

    /** Monotonically increasing source for dense method ids, starting at 0. */
    private static final AtomicInteger NEXT_METHOD_ID = new AtomicInteger(0);

    /**
     * Intern a method key and return a stable dense {@code int} id.
     *
     * <p>Called by the B.3 transformer at class-load time when it encounters
     * the first save point in a method.  The returned id is embedded as a
     * bytecode constant ({@code LDC}) in the emitted dispatch prelude and in
     * every {@code saveFrame} call.
     *
     * <p>Thread-safe: {@link ConcurrentHashMap#computeIfAbsent} guarantees
     * exactly one id per key even under concurrent class loading.
     *
     * @param key {@code "className.methodName(descriptor)"} as built by the
     *            B.3 ClassVisitor, e.g. {@code "com/example/Foo.doWork(I)V"}
     * @return dense {@code int} id, ≥ 0, stable for the process lifetime
     * TODO: annotate with {@code @Internal} once unit A.4 merges.
     */
    public static int internMethodId(String key) {
        return METHOD_IDS.computeIfAbsent(key, k -> NEXT_METHOD_ID.getAndIncrement());
    }

    /**
     * Push a save-point record onto the current thread's resume deque.
     *
     * <p>When {@link #TTD_ACTIVE_SESSIONS} is zero (the common case — no
     * session is running), this method returns immediately <em>without
     * allocating anything</em> (zero-alloc steady state).  The guard on
     * {@code TTD_ACTIVE_SESSIONS} comes before any {@code ThreadLocal.get()}
     * or object construction, so the cold path is a single volatile read +
     * conditional branch.
     *
     * <p>C.1 will replace the {@code == 0} check with a generation-counter
     * test that also distinguishes stale frames from prior checkpoint epochs.
     *
     * <p>Called from bytecode emitted by B.3.  The {@code prims} and
     * {@code refs} arrays are owned by the newly created {@link ResumeFrame};
     * callers must not reuse or mutate them after this call.
     *
     * @param methodId method id as returned by {@link #internMethodId}
     * @param bci      save-point bytecode index
     * @param prims    primitive locals; must not be {@code null}
     * @param refs     reference locals; must not be {@code null}
     * TODO: annotate with {@code @Internal} once unit A.4 merges.
     */
    public static void saveFrame(int methodId, int bci, long[] prims, Object[] refs) {
        // Zero-alloc early return: guard BEFORE any ThreadLocal.get() or alloc.
        // C.1 will replace this check with a TTD_GEN generation test.
        if (TTD_ACTIVE_SESSIONS == 0) return;
        FRAME_DEQUE.get().push(new ResumeFrame(methodId, bci, prims, refs));
    }

    /**
     * Peek at the top of the current thread's resume deque; if the top frame's
     * {@code methodId} matches the caller's {@code methodId}, pop and return
     * it; otherwise return {@code null} without modifying the deque.
     *
     * <p>This is the B.3 dispatch-prelude's read point.  Return-value
     * semantics:
     * <ul>
     *   <li>{@code null} — no frame for this call frame; fall through to normal
     *       forward execution.</li>
     *   <li>non-{@code null} — table-jump to {@code frame.bci}, restore locals
     *       from {@code frame.prims} and {@code frame.refs}, resume.</li>
     * </ul>
     *
     * <p>The methodId guard lets nested CPS-instrumented calls coexist on the
     * deque: each frame is consumed only by the method whose id matches the
     * top of stack, leaving outer frames intact for their own dispatch
     * prelude to consume when they return.
     *
     * @param methodId the calling method's interned id
     * @return the popped frame, or {@code null}
     * TODO: annotate with {@code @Internal} once unit A.4 merges.
     */
    public static ResumeFrame popResumeFrame(int methodId) {
        if (TTD_ACTIVE_SESSIONS == 0) return null;
        ArrayDeque<ResumeFrame> deque = FRAME_DEQUE.get();
        ResumeFrame top = deque.peek();
        if (top == null || top.methodId != methodId) return null;
        deque.pop();
        return top;
    }

    /**
     * Drain the current thread's resume deque and remove the thread-local
     * entry.  Called unconditionally from the session {@code finally} block.
     *
     * <p>This prevents {@link ResumeFrame} instances from being retained on
     * the thread-local after session end, which would cause a memory leak for
     * long-lived threads (application servers, thread pools, test runners).
     */
    private static void clearSessionState() {
        ArrayDeque<ResumeFrame> deque = FRAME_DEQUE.get();
        deque.clear();
        FRAME_DEQUE.remove();
    }

    // =========================================================================
    // Session lifecycle
    // =========================================================================

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
        // Increment active-sessions counter so saveFrame / popResumeFrame
        // take their live paths.  Decremented in the finally block below
        // (normal and exceptional exit).  C.1 will replace this plain counter
        // with a TTD_GEN generation counter.
        TTD_ACTIVE_SESSIONS++;
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
            // Drain resume deque and clear thread-local to prevent memory leaks.
            // Must run before decrementing the counter so that if a saveFrame
            // call races on another thread, clearSessionState is complete before
            // TTD_ACTIVE_SESSIONS drops to 0.
            clearSessionState();
            TTD_ACTIVE_SESSIONS--;
        }
    }

    /**
     * Pause point. Called from inside a {@link #session} body. The first
     * call has index 1, the second has index 2, etc. On replay, calls with
     * index strictly less than the REPL's target stop index return
     * immediately without prompting.
     */
    public static void breakpoint() {
        hitInternal(null);
    }

    /**
     * Auto-instrumentation entry point: emitted by
     * {@link LineMarkerTransformer} at every line of any
     * {@link TimeTravelBody}-annotated method. Same semantics as
     * {@link #breakpoint()} but carries source-location context for the
     * REPL to display. Outside a {@link #session} this is a silent
     * no-op so instrumented classes loaded outside a session pay no
     * runtime cost beyond the static call.
     */
    public static void lineHit(String ownerInternal, String methodSig, int line) {
        TtdContext ctx = CTX.get();
        if (ctx == null) return;
        hitInternal(ownerInternal + "." + methodSig + ":" + line);
    }

    private static void hitInternal(String lineCtx) {
        TtdContext ctx = CTX.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "Ttd.breakpoint() called outside a Ttd.session()");
        }
        ctx.currentIdx++;
        ctx.currentLineCtx = lineCtx;
        if (ctx.currentIdx < ctx.targetStop) {
            return;  // silent replay
        }
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
        String currentLineCtx;   // "owner.method:line" if from lineHit, else null

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
