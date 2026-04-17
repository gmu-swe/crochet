package net.jonbell.crochet.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sun.misc.Unsafe;

/**
 * Runtime support invoked from instrumented user classes and the user-facing
 * checkpoint/rollback API. This class is a thin facade over a set of
 * single-purpose helpers in the same package:
 *
 * <ul>
 *   <li>{@link VersionCounter} — global checkpoint/rollback version counter.
 *   <li>{@link FastProxySupport} — klass-swap machinery, Fast-proxy generation,
 *       {@link #fastAccess} race-winner, VarHandle-based version-field helpers.
 *   <li>{@link SfHelperFactory} — per-class static-field helper materialisation
 *       and cache.
 *   <li>{@link StaticSnapshots} — reflective static-field snapshot/rollback API.
 *   <li>{@link ArrayRegistry} — per-array snapshot state.
 * </ul>
 *
 * <p>Bytecode emitted across millions of classes references
 * {@code net/jonbell/crochet/runtime/CheckpointRollbackAgent} by internal
 * name. This facade preserves every method signature that emitters
 * (FieldAdder, StaticFieldRewriter, FieldAccessWrapper, ArrayAccessWrapper,
 * ArrayCopyInterceptor, Specializer) compile into {@code INVOKESTATIC}
 * sites. Delegate bodies are one-liners so the JIT inlines the facade away
 * entirely.
 *
 * <p>Gap 6 / V2: klass-swap + lazy snapshot with race-winner concurrency.
 * {@link #checkpoint(Object)} / {@link #rollback(Object, int)} swap the object
 * to a per-user-class hidden Fast proxy. The proxy's {@code $$crochetAccess}
 * override delegates to {@link #fastAccess(CRIJInstrumented)}, which uses a
 * CAS on the klass header to ensure exactly one thread does the
 * snapshot/restore work while peers return cheaply.
 *
 * <p>Gap 8 (exception safety): the winner's snapshot/restore runs inside a
 * try/catch that zeroes the version + nulls the snap on throw and raises a
 * {@link RollbackException} with {@link RollbackException#POISON_VERSION}.
 * Because the winner swapped klass <em>at the top</em>, no finally clause is
 * needed — a thrown path leaves the object in the user-class state with a
 * consistent (zeroed) view, preserving the paper's I3 continuity invariant.
 */
public final class CheckpointRollbackAgent {

    private CheckpointRollbackAgent() {}

    /**
     * Re-exported handle to the shared Unsafe. Kept package-private for
     * {@link ClassMeta}, which reads {@code CheckpointRollbackAgent.U} to
     * read the raw klass-pointer int out of preallocated shadow instances.
     * The single source of truth lives in {@link FastProxySupport}.
     */
    static final Unsafe U = FastProxySupport.U;

    /**
     * Re-exported klass-pointer offset. Publicly visible because
     * {@link ClassMeta} and diagnostics reference it as
     * {@code CheckpointRollbackAgent.KLASS_OFFSET}. The canonical definition
     * (with HotSpot probe-based detection and the uncompressed-klass guard)
     * lives in {@link FastProxySupport}.
     */
    public static final long KLASS_OFFSET = FastProxySupport.KLASS_OFFSET;

    /* ---------- Version counter (delegates to VersionCounter) ---------- */

    public static int nextCheckpointVersion() {
        return VersionCounter.nextCheckpointVersion();
    }

    public static int nextRollbackVersion() {
        return VersionCounter.nextRollbackVersion();
    }

    /* ---------- Paper §3 user-facing API ---------- */

    /** User-facing: checkpoint {@code target}. Returns the version id. */
    public static int checkpoint(Object target) {
        Class<?> userClass = realUserClassOf(target);
        int v = nextCheckpointVersion();
        ((CRIJInstrumented) target).$$crochetCheckpoint(v);
        ArrayRegistry.propagateCheckpoint(target, v);
        checkpointClassAtVersion(userClass, v);
        return v;
    }

    /** User-facing: roll {@code target} back to the state captured at version {@code v}. */
    public static void rollback(Object target, int v) {
        Class<?> userClass = realUserClassOf(target);
        int rv = nextRollbackVersion();
        ((CRIJInstrumented) target).$$crochetRollback(rv);
        ArrayRegistry.propagateRollback(target, v);
        rollbackClassAtVersion(userClass, rv);
    }

    /* ---------- Paper §3 top-level API: checkpointAll / rollbackAll ---------- */

    /**
     * Set of user classes whose {@link ClassMeta} has been materialised — i.e.
     * classes the runtime has already noticed via {@code ClassMeta.of}. This
     * is a superset of "classes that have been touched" which is a superset
     * of "classes that might hold mutable static state we need to snap".
     *
     * <p>Populated at the bottom of {@link ClassMeta#of}; never cleared. On a
     * long-running server this can grow into the thousands — it's keyed by
     * {@link Class} so classloader-unloaded classes retain liveness via this
     * set (acceptable in practice; users targeting classloader churn should
     * hold their own per-workload roots instead of relying on
     * {@code checkpointAll}).
     */
    static final Set<Class<?>> TOUCHED_CLASSES = ConcurrentHashMap.newKeySet();

    /**
     * Opt-out for users whose test frameworks or hosting containers assume
     * the system classloader / thread list are stable. When {@code true},
     * {@link #checkpointAll} / {@link #rollbackAll} skip those two roots and
     * only walk user classes.
     */
    private static final boolean SKIP_SYSTEM =
            Boolean.getBoolean("crochet.checkpointAll.skipSystem");

    /**
     * Paper §3 "checkpoint the live world". Bumps the version counter once,
     * then walks:
     * <ul>
     *   <li>Every user class materialised via {@link ClassMeta#of} (their
     *       reflective statics via {@link #checkpointClassAtVersion}).
     *   <li>Every live {@link Thread} from
     *       {@link Thread#getAllStackTraces} — threads are instrumented
     *       objects, so each gets {@link #checkpoint(Object)}.
     *   <li>The system {@link ClassLoader} — also instrumented.
     * </ul>
     *
     * <p>Returns the version id; pass it to {@link #rollbackAll(int)} to
     * restore.
     *
     * <p><b>Scope caveats</b>: {@code checkpointAll} is a best-effort root
     * set. It does NOT discover arbitrary user-held objects — only the
     * paper-documented "live world" roots (threads + system CL) and the
     * user-classes-touched-so-far. Callers with their own static root
     * collections should checkpoint those explicitly before or after.
     *
     * <p><b>Escape hatch</b>: {@code -Dcrochet.checkpointAll.skipSystem=true}
     * elides the thread-list and classloader walks.
     */
    public static int checkpointAll() {
        int v = nextCheckpointVersion();
        // Snapshot TOUCHED_CLASSES before iterating — a new $$crochetAccess
        // from a peer thread can populate the set mid-iteration otherwise and
        // we'd capture a class at the wrong version.
        Class<?>[] classes = TOUCHED_CLASSES.toArray(new Class<?>[0]);
        for (Class<?> c : classes) {
            try {
                checkpointClassAtVersion(c, v);
            } catch (Throwable t) {
                // Gap 8 exception safety: one class's static-helper
                // generation failing (e.g. a classloader that can no longer
                // resolve $$crochetLookup) must not abort checkpointAll.
                if (Boolean.getBoolean("crochet.verboseCompat")) {
                    System.err.println("checkpointAll: skipping " + c.getName()
                            + " (" + t.getClass().getSimpleName() + ")");
                }
            }
        }
        if (!SKIP_SYSTEM) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t instanceof CRIJInstrumented) {
                    try {
                        checkpoint(t);
                    } catch (Throwable x) {
                        if (Boolean.getBoolean("crochet.verboseCompat")) {
                            System.err.println("checkpointAll: thread "
                                    + t.getName() + " skipped: " + x);
                        }
                    }
                }
            }
            ClassLoader scl = ClassLoader.getSystemClassLoader();
            if (scl instanceof CRIJInstrumented) {
                try {
                    checkpoint(scl);
                } catch (Throwable x) {
                    if (Boolean.getBoolean("crochet.verboseCompat")) {
                        System.err.println("checkpointAll: system CL skipped: " + x);
                    }
                }
            }
        }
        return v;
    }

    /**
     * Symmetric rollback for {@link #checkpointAll()}. Walks the same roots
     * in the same order. Each per-root failure is isolated so a partial
     * rollback still restores the majority; callers who want strict
     * all-or-nothing semantics should pair {@code checkpointAll} with a
     * custom orchestrator.
     */
    public static void rollbackAll(int v) {
        int rv = nextRollbackVersion();
        Class<?>[] classes = TOUCHED_CLASSES.toArray(new Class<?>[0]);
        for (Class<?> c : classes) {
            try {
                rollbackClassAtVersion(c, rv);
            } catch (Throwable t) {
                if (Boolean.getBoolean("crochet.verboseCompat")) {
                    System.err.println("rollbackAll: skipping " + c.getName()
                            + " (" + t.getClass().getSimpleName() + ")");
                }
            }
        }
        if (!SKIP_SYSTEM) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t instanceof CRIJInstrumented) {
                    try {
                        rollback(t, v);
                    } catch (Throwable x) {
                        if (Boolean.getBoolean("crochet.verboseCompat")) {
                            System.err.println("rollbackAll: thread "
                                    + t.getName() + " skipped: " + x);
                        }
                    }
                }
            }
            ClassLoader scl = ClassLoader.getSystemClassLoader();
            if (scl instanceof CRIJInstrumented) {
                try {
                    rollback(scl, v);
                } catch (Throwable x) {
                    if (Boolean.getBoolean("crochet.verboseCompat")) {
                        System.err.println("rollbackAll: system CL skipped: " + x);
                    }
                }
            }
        }
    }

    /**
     * Walks the supertype chain past any stacked Fast-proxy layers. Common
     * third-party runtimes (ByteBuddy, Weld, Hibernate) synthesize proxies
     * that subclass our Fast proxy — {@code UserClass$$crochetFast} then
     * {@code UserClass$$crochetFast$$ByteBuddy$123} — so the first
     * {@code getSuperclass()} step lands on our proxy rather than the real
     * user class. Walk while the predicate holds so the returned class is
     * always the original user class (first type in the chain that does not
     * implement {@link CRIJFast}).
     */
    private static Class<?> realUserClassOf(Object target) {
        Class<?> c = target.getClass();
        while (c != null && CRIJFast.class.isAssignableFrom(c)) {
            c = c.getSuperclass();
        }
        return c;
    }

    /* ---------- called from instrumented code (facade delegations) ---------- */

    /** See {@link FastProxySupport#swapToFastProxy(Object, Class)}. */
    public static void swapToFastProxy(Object target, Class<?> userClass) {
        FastProxySupport.swapToFastProxy(target, userClass);
    }

    /** Back-compat overload; see {@link FastProxySupport#swapToFastProxy(Object, Class, int)}. */
    public static void swapToFastProxy(Object target, Class<?> userClass, int priorVersion) {
        FastProxySupport.swapToFastProxy(target, userClass, priorVersion);
    }

    /** See {@link FastProxySupport#isUnproxyable(Class)}. */
    public static boolean isUnproxyable(Class<?> userClass) {
        return FastProxySupport.isUnproxyable(userClass);
    }

    /**
     * Replacement for {@code System.arraycopy} emitted by
     * {@link net.jonbell.crochet.transform.ArrayCopyInterceptor}. Ensures the
     * destination array's snapshot is captured before the native bulk copy
     * overwrites its contents. This closes the "System.arraycopy bypass"
     * gap where a registered array could be modified in bulk without
     * triggering the per-slot xASTORE pre-hook.
     *
     * <p>Reads of {@code src} don't need a hook — we only care about writes
     * to a possibly-registered destination.
     */
    public static void interceptedArraycopy(Object src, int srcPos,
                                            Object dst, int dstPos, int length) {
        if (dst != null) {
            ArrayRegistry.beforeStore(dst);
        }
        System.arraycopy(src, srcPos, dst, dstPos, length);
    }

    /** See {@link FastProxySupport#fastAccess(CRIJInstrumented)}. */
    public static void fastAccess(CRIJInstrumented obj) {
        FastProxySupport.fastAccess(obj);
    }

    /* ---------- Gap 3: reflective static-field checkpoint ---------- */

    public static int checkpointStatics(Class<?> c) {
        return StaticSnapshots.checkpointStatics(c);
    }

    public static void rollbackStatics(Class<?> c, int v) {
        StaticSnapshots.rollbackStatics(c, v);
    }

    /* ---------- Gap 4: reflective array checkpoint ---------- */

    /**
     * Reflective array checkpoint. Routed through {@link ArrayRegistry} so the
     * bytecode-level path (xASTORE pre-hook) and the reflective API share one
     * weak-keyed registry. Historically a second strong-referenced
     * {@code ARRAY_SNAPS} {@link java.util.IdentityHashMap} pinned arrays that
     * a user called {@code checkpointArray} on — the unify eliminates that pin.
     */
    public static int checkpointArray(Object array) {
        if (array == null || !array.getClass().isArray()) {
            throw new IllegalArgumentException("checkpointArray requires a non-null array, got "
                    + (array == null ? "null" : array.getClass()));
        }
        int v = nextCheckpointVersion();
        ArrayRegistry.snapNow(array, v);
        return v;
    }

    public static void rollbackArray(Object array, int v) {
        nextRollbackVersion();
        ArrayRegistry.rollback(array, v);
    }

    /** See {@link FastProxySupport#allocateShadow(Class)}. */
    public static Object allocateShadow(Class<?> c) {
        return FastProxySupport.allocateShadow(c);
    }

    /* ---------- klass-swap machinery (facade) ---------- */

    public static boolean changeClass(Object target, Class<?> from, Class<?> to) {
        return FastProxySupport.changeClass(target, from, to);
    }

    public static int klassOf(Class<?> c) {
        return FastProxySupport.klassOf(c);
    }

    /* ---------- Gap 6: version field helpers for emitted bytecode ---------- */

    /** Volatile read of $$crochetVersion on target. */
    public static int versionVolatileGet(Object target, Class<?> userClass) {
        return FastProxySupport.versionVolatileGet(target, userClass);
    }

    /** CAS on $$crochetVersion; returns true iff expect matched. */
    public static boolean versionCas(Object target, Class<?> userClass, int expect, int update) {
        return FastProxySupport.versionCas(target, userClass, expect, update);
    }

    public static void versionStore(Object target, Class<?> userClass, int value) {
        FastProxySupport.versionStore(target, userClass, value);
    }

    /* ---------- lazy Fast-proxy generation (facade) ---------- */

    public static Class<?> fastProxyFor(Class<?> userClass) {
        return FastProxySupport.fastProxyFor(userClass);
    }

    /** Called only from {@link ClassMeta#fastBinding} under its DCL lock. */
    public static Class<?> fastProxyForInternal(Class<?> userClass) {
        return FastProxySupport.fastProxyForInternal(userClass);
    }

    /* ---------- Gap 3 (bytecode): static-field helper lookup ---------- */

    public static CRIJInstrumented sfHelperFor(Class<?> userClass) {
        return SfHelperFactory.sfHelperFor(userClass);
    }

    public static void noteStaticAccess(Class<?> userClass) {
        SfHelperFactory.noteStaticAccess(userClass);
    }

    /* ---------- class-level static checkpoint / rollback ---------- */

    public static int checkpointClass(Class<?> c) {
        int v = nextCheckpointVersion();
        checkpointClassAtVersion(c, v);
        return v;
    }

    public static void checkpointClassAtVersion(Class<?> c, int v) {
        CRIJInstrumented h = sfHelperFor(c);
        h.$$crochetCheckpoint(v);
    }

    public static void rollbackClass(Class<?> c, int v) {
        int rv = nextRollbackVersion();
        rollbackClassAtVersion(c, rv);
    }

    public static void rollbackClassAtVersion(Class<?> c, int rv) {
        ClassMeta meta = ClassMeta.of(c);
        CRIJInstrumented h = meta.sfHelper;
        if (h == null) {
            return;
        }
        h.$$crochetRollback(rv);
    }
}
