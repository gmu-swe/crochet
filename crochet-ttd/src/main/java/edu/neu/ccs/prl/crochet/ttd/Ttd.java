package edu.neu.ccs.prl.crochet.ttd;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
     * <p>Backed by an {@link AtomicInteger} to prevent lost updates when multiple
     * threads start sessions concurrently. The public field exposes the backing
     * {@link AtomicInteger} directly; callers should use {@code .get()} for reads
     * and should not mutate it except through {@code sessionWithRepl}.
     * Tests may call {@code .set(0)} to reset the counter after a test.
     *
     * <p>TODO: annotate with {@code @Internal} once unit A.4 merges.
     */
    public static final AtomicInteger TTD_ACTIVE_SESSIONS = new AtomicInteger(0);

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

    // =========================================================================
    // B.5: Debug table — (methodId, bci) → MethodLineInfo
    // =========================================================================

    /**
     * Holder for per-save-point debug metadata registered by B.3 at class-load
     * time.  Package-private; accessed only from within {@code Ttd}.
     */
    static final class MethodLineInfo {
        /** {@code "InternalClassName.nameDesc:line"}, e.g. {@code "com/example/Foo.doWork(I)V:42"}. */
        final String label;

        /**
         * Local names for primitive slots, indexed by prim-array position.
         * {@code null} array or {@code null} entries fall back to {@code "$slotN"}.
         */
        final String[] primNames;

        /** JVM field descriptors for primitive slots. {@code null} entries fall back to {@code "?"}. */
        final String[] primDescs;

        /** Local names for reference slots. */
        final String[] refNames;

        /** JVM field descriptors for reference slots. */
        final String[] refDescs;

        MethodLineInfo(String label,
                       String[] primNames, String[] primDescs,
                       String[] refNames,  String[] refDescs) {
            this.label     = label;
            this.primNames = primNames;
            this.primDescs = primDescs;
            this.refNames  = refNames;
            this.refDescs  = refDescs;
        }
    }

    /**
     * Process-lifetime debug table: {@code (methodId << 32) | bci -> MethodLineInfo}.
     *
     * <p>Populated at class-load time by B.3 via {@link #registerMethodLine(int, int, String)}.
     * Key is a packed {@code long} to avoid boxing a {@code (int,int)} tuple.
     * Reads at capture time are lock-free.
     */
    private static final ConcurrentHashMap<Long, MethodLineInfo> METHOD_LINE_TABLE =
            new ConcurrentHashMap<>();

    /**
     * Register debug metadata for a single save-point inside a
     * {@link TimeTravelBody}-annotated method.  Called by B.3's class-init
     * helper at class-load time, once per save-point per class load.
     *
     * <p>The key is {@code (methodId, bci)}; a method has one entry per
     * save-point (each save-point corresponds to a distinct bci).
     *
     * <p>When {@code primNames}/{@code primDescs}/{@code refNames}/{@code refDescs}
     * are {@code null}, the {@link LocalSnapshot} for that save-point will use
     * {@code "$slotN"} and {@code "?"} fallback values — this is the correct
     * behaviour for classes compiled with {@code -g:none}.
     *
     * <p>TODO: annotate with {@code @Internal} once unit A.4 merges.
     *
     * @param methodId  dense method id as returned by {@link #internMethodId(String)}
     * @param bci       bytecode index of the save-point
     * @param label     {@code "InternalClassName.nameDesc:line"} string
     * @param primNames names of primitive locals, indexed by prim slot; may be null
     * @param primDescs JVM descriptors of primitive locals; may be null
     * @param refNames  names of reference locals, indexed by ref slot; may be null
     * @param refDescs  JVM descriptors of reference locals; may be null
     */
    public static void registerMethodLine(int methodId, int bci, String label,
                                          String[] primNames, String[] primDescs,
                                          String[] refNames,  String[] refDescs) {
        long key = ((long) methodId << 32) | (bci & 0xFFFFFFFFL);
        METHOD_LINE_TABLE.putIfAbsent(key,
                new MethodLineInfo(label, primNames, primDescs, refNames, refDescs));
    }

    /**
     * Convenience overload of
     * {@link #registerMethodLine(int, int, String, String[], String[], String[], String[])}
     * that registers only the source-location label, with no local-variable info.
     * All local snapshots for this save-point will use {@code "$slotN"} / {@code "?"}
     * fallback names.
     *
     * <p>Intended for testing and for B.3's initial integration before full
     * local-variable table emission is wired up.
     *
     * <p>TODO: annotate with {@code @Internal} once unit A.4 merges.
     *
     * @param methodId dense method id as returned by {@link #internMethodId(String)}
     * @param bci      bytecode index of the save-point
     * @param label    {@code "InternalClassName.nameDesc:line"} string
     */
    public static void registerMethodLine(int methodId, int bci, String label) {
        registerMethodLine(methodId, bci, label, null, null, null, null);
    }

    // =========================================================================
    // B.5: captureStack() — stack-as-data API
    // =========================================================================

    /**
     * Capture the current thread's resume-frame deque as a list of
     * {@link StackEntry} objects, innermost frame first.
     *
     * <p>The returned list is a <em>snapshot copy</em> — it is decoupled from
     * the live deque.  Subsequent {@link #saveFrame} / {@link #popResumeFrame}
     * calls on the current thread do not affect the returned list, and callers
     * may mutate the list freely without affecting the runtime.
     *
     * <p>If no TTD session is currently active ({@link #TTD_ACTIVE_SESSIONS}
     * {@code == 0}), returns an empty list without touching the thread-local.
     *
     * <p>For each {@link ResumeFrame} in the deque, the debug table is
     * consulted for the {@code (methodId, bci)} pair.  If an entry exists,
     * {@link StackEntry#classMethodLine()} is set to its label.  If no entry
     * exists (e.g., because B.3 has not yet been integrated, or the class was
     * not instrumented), the sentinel {@code "<methodId=N bci=M>"} is used.
     *
     * <p>Local variable snapshots are built from the frame's {@code prims} and
     * {@code refs} arrays.  Primitive slots appear first (in ascending slot
     * order), followed by reference slots.  Names and descriptors come from
     * the registered {@link MethodLineInfo}; absent info falls back to
     * {@code "$slotN"} / {@code "?"}.
     *
     * <p>TODO: annotate with {@code @Experimental} once unit A.4 merges and
     * provides the annotation.
     *
     * @return mutable snapshot list, innermost frame first; never null
     */
    public static List<StackEntry> captureStack() {
        if (TTD_ACTIVE_SESSIONS.get() == 0) return new ArrayList<>(0);
        ArrayDeque<ResumeFrame> deque = FRAME_DEQUE.get();
        if (deque.isEmpty()) return new ArrayList<>(0);

        // Iterate deque in push order (head = innermost frame).
        // ArrayDeque iterator starts at the head (addFirst side).
        List<StackEntry> result = new ArrayList<>(deque.size());
        for (ResumeFrame frame : deque) {
            result.add(buildEntry(frame));
        }
        return result;
    }

    /**
     * Convert a {@link ResumeFrame} to a {@link StackEntry} by consulting
     * the debug table.
     */
    private static StackEntry buildEntry(ResumeFrame frame) {
        long key = ((long) frame.methodId << 32) | (frame.bci & 0xFFFFFFFFL);
        MethodLineInfo info = METHOD_LINE_TABLE.get(key);

        String label = (info != null)
                ? info.label
                : "<methodId=" + frame.methodId + " bci=" + frame.bci + ">";

        List<LocalSnapshot> locals = new ArrayList<>(frame.prims.length + frame.refs.length);

        // Primitive slots first.
        for (int i = 0; i < frame.prims.length; i++) {
            String name = (info != null && info.primNames != null && i < info.primNames.length
                           && info.primNames[i] != null)
                    ? info.primNames[i]
                    : "$slot" + i;
            String desc = (info != null && info.primDescs != null && i < info.primDescs.length
                           && info.primDescs[i] != null)
                    ? info.primDescs[i]
                    : "?";
            locals.add(new LocalSnapshot(name, desc, Long.toString(frame.prims[i])));
        }

        // Reference slots after.
        for (int i = 0; i < frame.refs.length; i++) {
            String name = (info != null && info.refNames != null && i < info.refNames.length
                           && info.refNames[i] != null)
                    ? info.refNames[i]
                    : "$slot" + i;
            String desc = (info != null && info.refDescs != null && i < info.refDescs.length
                           && info.refDescs[i] != null)
                    ? info.refDescs[i]
                    : "?";
            locals.add(new LocalSnapshot(name, desc, String.valueOf(frame.refs[i])));
        }

        return new StackEntry(label, Collections.unmodifiableList(locals));
    }

    /**
     * Serialize a stack snapshot as a versioned JSON string.
     *
     * <p>Schema version 1:
     * <pre>
     * {
     *   "schemaVersion": 1,
     *   "frames": [
     *     {
     *       "classMethodLine": "com/example/Foo.doWork(I)V:42",
     *       "locals": [
     *         {"name": "x",   "descriptor": "I",               "value": "42"},
     *         {"name": "s",   "descriptor": "Ljava/lang/String;", "value": "hello"}
     *       ]
     *     }
     *   ]
     * }
     * </pre>
     *
     * <p>The serialized form is deterministic: the same {@code frames} list
     * always produces a byte-identical string.  Values are human-readable
     * strings, not round-trip-deserializable primitives.
     *
     * @param frames the list returned by {@link #captureStack()}
     * @return JSON string with {@code schemaVersion} 1; never null
     */
    public static String serializeStack(List<StackEntry> frames) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schemaVersion\":1,\"frames\":[");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(frames.get(i).toJson());
        }
        sb.append("]}");
        return sb.toString();
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
        if (TTD_ACTIVE_SESSIONS.get() == 0) return;
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
        if (TTD_ACTIVE_SESSIONS.get() == 0) return null;
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
    // @VisibleForTesting helpers — package-private, tests only
    // =========================================================================

    /**
     * Clear the current thread's resume deque without removing the thread-local.
     * For use by tests that manage the deque lifecycle manually.
     */
    static void testClearDeque() {
        FRAME_DEQUE.get().clear();
    }

    /**
     * Return a snapshot list of all frames currently in the thread-local deque,
     * HEAD first, for test assertions.  The returned list is a copy; it is
     * decoupled from the live deque.
     */
    static List<ResumeFrame> testPeekDeque() {
        return new ArrayList<>(FRAME_DEQUE.get());
    }

    /**
     * Push a frame directly onto the thread-local deque (HEAD), bypassing the
     * {@code TTD_ACTIVE_SESSIONS} guard.  For use by tests that need to stage
     * a resume frame before invoking an instrumented method.
     */
    static void testPushFrame(ResumeFrame frame) {
        FRAME_DEQUE.get().push(frame);
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
        // with a TTD_GEN generation counter.  AtomicInteger ensures the
        // increment/decrement are not lost under concurrent sessions.
        TTD_ACTIVE_SESSIONS.getAndIncrement();
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
            TTD_ACTIVE_SESSIONS.getAndDecrement();
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
