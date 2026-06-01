package edu.neu.ccs.prl.crochet.ttd.nondet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime helpers for recording and replaying nondeterministic method calls.
 *
 * <p>Each intercepted call site in user bytecode is rewritten by
 * {@link edu.neu.ccs.prl.crochet.ttd.NondetTransformer} to call one of
 * the {@code fetchOrCall*} static methods in this class. The rewrite
 * shape is:
 *
 * <pre>
 *   // original:
 *   INVOKESTATIC System.currentTimeMillis()J
 *
 *   // rewritten:
 *   LDC &lt;siteId&gt;
 *   INVOKESTATIC NondetRecorder.fetchOrCallCurrentTimeMillis(I)J
 * </pre>
 *
 * <p><b>Cold-path zero-alloc guarantee (Universal Gate 7):</b> when
 * neither recording nor replaying, each helper checks
 * {@code RECORDING_TL.get() == null &amp;&amp; REPLAYING_TL.get() == null}
 * and falls through directly to the real JDK method with no allocation.
 * The two ThreadLocal reads are the only overhead on the hot path;
 * HotSpot folds the branch as predictably not-taken once the JIT
 * profiles the call site.
 *
 * <p><b>Site IDs:</b> each unique call site gets a stable integer site ID
 * embedded as an {@code LDC} in the rewritten bytecode. The mapping from
 * site ID to a human-readable descriptor is registered lazily on first
 * recording via {@link #registerSiteDesc}.
 *
 * <p><b>Thread safety:</b> recording and replay state are
 * {@code ThreadLocal}. The site-descriptor map is a
 * {@link ConcurrentHashMap} safe for concurrent registration.
 *
 * <p>This class is {@code @Internal} — the API is not stable.
 */
public final class NondetRecorder {

    private NondetRecorder() {}

    // -------------------------------------------------------------------------
    // State: per-thread recording / replaying
    // -------------------------------------------------------------------------

    /** Non-null on the recording thread during a recording session. */
    public static final ThreadLocal<ArrayList<NondetEvent>> RECORDING_TL = new ThreadLocal<>();

    /** Non-null on the replaying thread during a replay session. */
    public static final ThreadLocal<Map<Integer, ArrayDeque<NondetEvent>>> REPLAYING_TL =
            new ThreadLocal<>();

    /** Site ID → human-readable descriptor for REPL display. */
    private static final ConcurrentHashMap<Integer, String> SITE_DESCS =
            new ConcurrentHashMap<>();

    /** Global divergence handler; default prints to stderr. */
    private static volatile NondetDivergenceHandler divergenceHandler =
            event -> System.err.println(event.toString());

    // -------------------------------------------------------------------------
    // Session management (called by TtdSession / tests)
    // -------------------------------------------------------------------------

    /** Install a custom divergence handler (e.g., REPL routing). */
    public static void setDivergenceHandler(NondetDivergenceHandler h) {
        if (h == null) throw new NullPointerException("handler must not be null");
        divergenceHandler = h;
    }

    public static NondetDivergenceHandler getDivergenceHandler() {
        return divergenceHandler;
    }

    /**
     * Start recording nondeterministic values on the current thread.
     * Any prior recording log for this thread is discarded.
     */
    public static void startRecording() {
        RECORDING_TL.set(new ArrayList<>());
        REPLAYING_TL.remove();
    }

    /**
     * Stop recording and return the accumulated log.
     * @return the recorded events in call-site order; caller retains ownership
     */
    public static List<NondetEvent> stopRecording() {
        List<NondetEvent> log = RECORDING_TL.get();
        RECORDING_TL.remove();
        return log != null ? log : new ArrayList<>();
    }

    /**
     * Start replaying from a previously recorded log on the current thread.
     * @param log the recorded events (not modified; a defensive copy is made)
     */
    public static void startReplaying(List<NondetEvent> log) {
        RECORDING_TL.remove();
        Map<Integer, ArrayDeque<NondetEvent>> map = new HashMap<>();
        for (NondetEvent ev : log) {
            map.computeIfAbsent(ev.siteId, k -> new ArrayDeque<>()).add(ev);
        }
        REPLAYING_TL.set(map);
    }

    /**
     * Stop replaying on the current thread.
     */
    public static void stopReplaying() {
        REPLAYING_TL.remove();
    }

    /** @return true iff a recording session is active on this thread */
    public static boolean isRecording() {
        return RECORDING_TL.get() != null;
    }

    /** @return true iff a replay session is active on this thread */
    public static boolean isReplaying() {
        return REPLAYING_TL.get() != null;
    }

    /**
     * Register a human-readable descriptor for a site ID. Called lazily
     * on first recording of the site. Idempotent; concurrent registrations
     * of the same siteId are safe.
     *
     * @param siteId  the integer ID embedded in the bytecode
     * @param desc    "owner/methodDesc/bci" string for REPL display
     */
    public static void registerSiteDesc(int siteId, String desc) {
        SITE_DESCS.putIfAbsent(siteId, desc);
    }

    /** Return the descriptor for a site, or "unknown" if not registered. */
    public static String siteDesc(int siteId) {
        return SITE_DESCS.getOrDefault(siteId, "site#" + siteId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void record(int siteId, long rawBits, byte kind) {
        ArrayList<NondetEvent> log = RECORDING_TL.get();
        if (log != null) {
            log.add(new NondetEvent(siteId, rawBits, kind));
        }
    }

    /**
     * Attempt to dequeue the next replay value for {@code siteId}.
     * Returns {@code null} if replay is not active.
     * Emits a divergence event and returns {@code null} if the queue
     * is empty or the site is absent (caller should use the actual value).
     */
    private static NondetEvent dequeue(int siteId, byte expectedKind, long actualBits) {
        Map<Integer, ArrayDeque<NondetEvent>> map = REPLAYING_TL.get();
        if (map == null) return null;
        ArrayDeque<NondetEvent> q = map.get(siteId);
        if (q == null) {
            emitDivergence(siteId, NondetDivergenceEvent.NO_RECORDED_VALUE,
                    actualBits, expectedKind, NondetDivergenceEvent.CAUSE_SITE_ABSENT);
            return null;
        }
        NondetEvent ev = q.poll();
        if (ev == null) {
            emitDivergence(siteId, NondetDivergenceEvent.NO_RECORDED_VALUE,
                    actualBits, expectedKind, NondetDivergenceEvent.CAUSE_QUEUE_EMPTY);
            return null;
        }
        if (ev.kind != expectedKind) {
            emitDivergence(siteId, ev.rawBits, actualBits, expectedKind,
                    NondetDivergenceEvent.CAUSE_WRONG_KIND);
            return null;
        }
        return ev;
    }

    private static void emitDivergence(int siteId, long recordedBits, long actualBits,
                                        byte kind, String cause) {
        NondetDivergenceEvent ev = new NondetDivergenceEvent(
                siteId, siteDesc(siteId), recordedBits, actualBits, kind, cause);
        try {
            divergenceHandler.onDivergence(ev);
        } catch (Throwable t) {
            System.err.println("[ttd-nondet] divergence handler threw: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // fetchOrCall* helpers — one per intercepted method
    // -------------------------------------------------------------------------

    /**
     * Replaces {@code System.currentTimeMillis()}.
     * On record: logs the real value, returns it.
     * On replay: returns the recorded value, or emits a divergence event and
     *            returns the real value if not found.
     * Cold path: returns the real value with no allocation.
     */
    public static long fetchOrCallCurrentTimeMillis(int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            long v = System.currentTimeMillis();
            rec.add(new NondetEvent(siteId, v, NondetEvent.KIND_LONG));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            long actual = System.currentTimeMillis();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_LONG, actual);
            return ev != null ? ev.asLong() : actual;
        }
        return System.currentTimeMillis();
    }

    /**
     * Replaces {@code System.nanoTime()}.
     */
    public static long fetchOrCallNanoTime(int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            long v = System.nanoTime();
            rec.add(new NondetEvent(siteId, v, NondetEvent.KIND_LONG));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            long actual = System.nanoTime();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_LONG, actual);
            return ev != null ? ev.asLong() : actual;
        }
        return System.nanoTime();
    }

    /**
     * Replaces {@code System.identityHashCode(Object)}.
     * The {@code obj} argument is forwarded so the real JDK call is made
     * on the cold path and replay path (to avoid keeping a strong reference).
     */
    public static int fetchOrCallIdentityHashCode(Object obj, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            int v = System.identityHashCode(obj);
            rec.add(new NondetEvent(siteId, v, NondetEvent.KIND_INT));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            int actual = System.identityHashCode(obj);
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_INT, actual);
            return ev != null ? ev.asInt() : actual;
        }
        return System.identityHashCode(obj);
    }

    /**
     * Replaces {@code Object.hashCode()} when the static type is Object.
     * The object is required to call the real hashCode on the cold/replay path.
     */
    public static int fetchOrCallObjectHashCode(Object obj, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            int v = obj.hashCode();
            rec.add(new NondetEvent(siteId, v, NondetEvent.KIND_INT));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            int actual = obj.hashCode();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_INT, actual);
            return ev != null ? ev.asInt() : actual;
        }
        return obj.hashCode();
    }

    /**
     * Replaces {@code Random.next(int)} (protected method, called internally
     * by all nextXxx methods).
     */
    public static int fetchOrCallRandomNext(Random rng, int bits, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            // Use nextInt() as a proxy: call the actual next() via reflection
            // is fragile; intercept at the public API level instead.
            // Note: next(bits) is protected; we intercept the public nextInt() etc.
            // This helper is kept for completeness but the transformer primarily
            // intercepts the public APIs. next(int) is only rewritten when the
            // call site explicitly references Random.next.
            int v = callRandomNext(rng, bits);
            rec.add(new NondetEvent(siteId, v, NondetEvent.KIND_INT));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            int actual = callRandomNext(rng, bits);
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_INT, actual);
            return ev != null ? ev.asInt() : actual;
        }
        return callRandomNext(rng, bits);
    }

    private static int callRandomNext(Random rng, int bits) {
        // Random.next(int) is protected; use reflection to call it.
        // This is only invoked when a user class calls the protected method
        // directly (rare, only subclasses). On the common (cold) path,
        // the public nextInt/nextLong/etc. helpers below are used.
        try {
            java.lang.reflect.Method m = Random.class.getDeclaredMethod("next", int.class);
            m.setAccessible(true);
            return (int) m.invoke(rng, bits);
        } catch (Exception e) {
            throw new RuntimeException("NondetRecorder.callRandomNext failed", e);
        }
    }

    /** Replaces {@code Random.nextInt()}. */
    public static int fetchOrCallNextInt(Random rng, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            int v = rng.nextInt();
            rec.add(new NondetEvent(siteId, v, NondetEvent.KIND_INT));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            int actual = rng.nextInt();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_INT, actual);
            return ev != null ? ev.asInt() : actual;
        }
        return rng.nextInt();
    }

    /** Replaces {@code Random.nextInt(int bound)}. */
    public static int fetchOrCallNextIntBound(Random rng, int bound, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            int v = rng.nextInt(bound);
            rec.add(new NondetEvent(siteId, v, NondetEvent.KIND_INT));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            int actual = rng.nextInt(bound);
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_INT, actual);
            return ev != null ? ev.asInt() : actual;
        }
        return rng.nextInt(bound);
    }

    /** Replaces {@code Random.nextLong()}. */
    public static long fetchOrCallNextLong(Random rng, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            long v = rng.nextLong();
            rec.add(new NondetEvent(siteId, v, NondetEvent.KIND_LONG));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            long actual = rng.nextLong();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_LONG, actual);
            return ev != null ? ev.asLong() : actual;
        }
        return rng.nextLong();
    }

    /** Replaces {@code Random.nextDouble()}. */
    public static double fetchOrCallNextDouble(Random rng, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            double v = rng.nextDouble();
            rec.add(new NondetEvent(siteId, Double.doubleToRawLongBits(v), NondetEvent.KIND_DOUBLE));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            double actual = rng.nextDouble();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_DOUBLE,
                    Double.doubleToRawLongBits(actual));
            return ev != null ? ev.asDouble() : actual;
        }
        return rng.nextDouble();
    }

    /** Replaces {@code Random.nextFloat()}. */
    public static float fetchOrCallNextFloat(Random rng, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            float v = rng.nextFloat();
            rec.add(new NondetEvent(siteId, Float.floatToRawIntBits(v), NondetEvent.KIND_FLOAT));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            float actual = rng.nextFloat();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_FLOAT,
                    Float.floatToRawIntBits(actual));
            return ev != null ? ev.asFloat() : actual;
        }
        return rng.nextFloat();
    }

    /** Replaces {@code Random.nextBoolean()}. */
    public static boolean fetchOrCallNextBoolean(Random rng, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            boolean v = rng.nextBoolean();
            rec.add(new NondetEvent(siteId, v ? 1L : 0L, NondetEvent.KIND_INT));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            boolean actual = rng.nextBoolean();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_INT, actual ? 1L : 0L);
            return ev != null ? (ev.asInt() != 0) : actual;
        }
        return rng.nextBoolean();
    }

    /** Replaces {@code Random.nextGaussian()}. */
    public static double fetchOrCallNextGaussian(Random rng, int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            double v = rng.nextGaussian();
            rec.add(new NondetEvent(siteId, Double.doubleToRawLongBits(v), NondetEvent.KIND_DOUBLE));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            double actual = rng.nextGaussian();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_DOUBLE,
                    Double.doubleToRawLongBits(actual));
            return ev != null ? ev.asDouble() : actual;
        }
        return rng.nextGaussian();
    }

    /** Replaces {@code Math.random()}. */
    public static double fetchOrCallMathRandom(int siteId) {
        ArrayList<NondetEvent> rec = RECORDING_TL.get();
        if (rec != null) {
            double v = Math.random();
            rec.add(new NondetEvent(siteId, Double.doubleToRawLongBits(v), NondetEvent.KIND_DOUBLE));
            return v;
        }
        Map<Integer, ArrayDeque<NondetEvent>> rep = REPLAYING_TL.get();
        if (rep != null) {
            double actual = Math.random();
            NondetEvent ev = dequeue(siteId, NondetEvent.KIND_DOUBLE,
                    Double.doubleToRawLongBits(actual));
            return ev != null ? ev.asDouble() : actual;
        }
        return Math.random();
    }
}
