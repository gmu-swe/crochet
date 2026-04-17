package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.jonbell.crochet.transform.ProxyTemplate;
import net.jonbell.crochet.transform.Specializer;

import sun.misc.Unsafe;

/**
 * Runtime support invoked from instrumented user classes and the user-facing
 * checkpoint/rollback API.
 *
 * <p>V1: klass-swap + lazy snapshot. {@link #checkpoint(Object)} / {@link #rollback(Object, int)}
 * swap the object to a per-user-class hidden Fast proxy. The proxy's
 * {@code $$crochetAccess} override delegates to {@link #fastAccess(CRIJInstrumented)},
 * which takes the snapshot (on checkpoint) or restores from it (on rollback)
 * on the FIRST field access and then swaps the klass back to the user class,
 * so the object is "normal" for all subsequent accesses.
 */
public final class CheckpointRollbackAgent {

    private CheckpointRollbackAgent() {}

    static final Unsafe U;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Byte offset of the (compressed) klass pointer in a HotSpot object header. */
    public static final long KLASS_OFFSET = 8L;

    /**
     * Template bytes for the Fast-state proxy. Emitted once and rewritten per
     * user class by {@link Specializer}.
     */
    private static volatile byte[] FAST_TEMPLATE;

    private static byte[] fastTemplate() {
        byte[] t = FAST_TEMPLATE;
        if (t == null) {
            synchronized (CheckpointRollbackAgent.class) {
                t = FAST_TEMPLATE;
                if (t == null) {
                    t = ProxyTemplate.emit();
                    FAST_TEMPLATE = t;
                }
            }
        }
        return t;
    }

    // Paper §3.4: "uses atomic compare-and-swap operations". The counter is
    // lock-free under contention via a CAS retry loop.
    private static final AtomicInteger VERSION_COUNTER = new AtomicInteger(0);

    public static int nextCheckpointVersion() {
        while (true) {
            int cur = VERSION_COUNTER.get();
            int next = cur + 1;
            if ((next & 1) == 0) {
                next++; // force odd (checkpoint)
            }
            if (VERSION_COUNTER.compareAndSet(cur, next)) {
                return next;
            }
        }
    }

    public static int nextRollbackVersion() {
        while (true) {
            int cur = VERSION_COUNTER.get();
            int next = cur + 1;
            if ((next & 1) != 0) {
                next++; // force even (rollback)
            }
            if (next == 0) {
                next = 2; // preserve 0 as "no checkpoint" sentinel
            }
            if (VERSION_COUNTER.compareAndSet(cur, next)) {
                return next;
            }
        }
    }

    /** User-facing: checkpoint {@code target}. Returns the version id. */
    public static int checkpoint(Object target) {
        int v = nextCheckpointVersion();
        ((CRIJInstrumented) target).$$crochetCheckpoint(v);
        return v;
    }

    /** User-facing: roll {@code target} back to the state captured at version {@code v}. */
    public static void rollback(Object target, int v) {
        int rv = nextRollbackVersion();
        ((CRIJInstrumented) target).$$crochetRollback(rv);
    }

    /* ---------- called from instrumented code ---------- */

    /**
     * Bound into every user class's {@code $$crochetCheckpoint} body by
     * {@link net.jonbell.crochet.transform.FieldAdder}. Lazily generates the
     * Fast proxy for {@code userClass}, then klass-swaps {@code target}.
     */
    /**
     * Called from the emitted {@code $$crochetCheckpoint} / {@code $$crochetRollback}
     * bodies AFTER the version field has been bumped. If proxy generation or
     * klass swap fails, the caller's bump must be undone — we do that here.
     *
     * @param priorVersion the value of {@code $$crochetVersion} BEFORE the caller
     *                     bumped it; restored on failure.
     */
    public static void swapToFastProxy(Object target, Class<?> userClass, int priorVersion) {
        Class<?> fastProxy;
        try {
            fastProxy = fastProxyFor(userClass);
        } catch (RuntimeException | Error e) {
            ((CRIJInstrumented) target).$$crochetSetVersion(priorVersion);
            throw new RollbackException(RollbackException.POISON_VERSION, e);
        }
        // CAS failure is benign: if another thread already swapped us, we're
        // already in (or past) the target state. The version we just wrote
        // will be observed by the next fastAccess either way.
        changeClass(target, userClass, fastProxy);
    }

    /** Backward-compat shim for any bytecode emitted before the 3-arg form. */
    @Deprecated
    public static void swapToFastProxy(Object target, Class<?> userClass) {
        swapToFastProxy(target, userClass, 0);
    }

    /**
     * Invoked by the Fast proxy's overridden {@code $$crochetAccess} (via the
     * template in {@link ProxyTemplate}). Decides checkpoint vs rollback based
     * on the version parity of {@code obj} and performs the snapshot or restore
     * work, then swaps the klass back to the user class so subsequent accesses
     * run without hook overhead.
     */
    public static void fastAccess(CRIJInstrumented obj) {
        Class<?> proxyClass = obj.getClass();
        Class<?> userClass = proxyClass.getSuperclass();
        if (userClass == null) {
            throw new RollbackException(RollbackException.POISON_VERSION,
                    new IllegalStateException("fastAccess: proxy has no superclass"));
        }
        int v = obj.$$crochetGetVersion();
        if (v == 0) {
            changeClass(obj, proxyClass, userClass);
            return;
        }
        Throwable thrown = null;
        boolean rollbackBranch = (v & 1) == 0;
        try {
            if (!rollbackBranch) {
                // Checkpoint state. Shadow allocated first; only published
                // via setSnap on success. Throws leave snap at its prior
                // value and obj untouched.
                Object shadow = allocateShadow(userClass);
                obj.$$crochetCopyFieldsTo(shadow);
                obj.$$crochetSetSnap(shadow);
                obj.$$crochetPropagateCheckpoint(v);
            } else {
                // Rollback state. Snap cleared only AFTER successful
                // copyFieldsFrom — a mid-copy throw preserves the snap so
                // the caller could retry (if they know the state is recoverable).
                Object snap = obj.$$crochetGetSnap();
                if (snap != null) {
                    obj.$$crochetCopyFieldsFrom(snap);
                    obj.$$crochetSetSnap(null);
                }
                obj.$$crochetPropagateRollback(v);
            }
        } catch (Throwable t) {
            thrown = t;
        } finally {
            // Always swap klass back to the user class. CAS-race-loss is benign.
            changeClass(obj, proxyClass, userClass);
        }
        if (thrown != null) {
            if (thrown instanceof RollbackException re) {
                throw re;
            }
            throw new RollbackException(RollbackException.POISON_VERSION, thrown);
        }
    }

    /* ---------- Gap 3: reflective static-field checkpoint ---------- */

    /**
     * Per-class snapshot of non-final, non-synthetic static fields, keyed on
     * {@link Class}. V1: eager reflective snapshot; {@link #checkpointStatics}
     * reads every static into the cache, {@link #rollbackStatics} writes them
     * back. No bytecode rewriting — mutations to the statics happen on the
     * real field slots, no redirection.
     */
    private static final Map<Class<?>, Map<String, Object>> STATIC_SNAPS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * Captures a snapshot of all non-final static fields of {@code c} (and
     * nothing inherited). Returns a checkpoint version id.
     */
    public static int checkpointStatics(Class<?> c) {
        int v = nextCheckpointVersion();
        Map<String, Object> snap = new LinkedHashMap<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    || Modifier.isFinal(f.getModifiers())
                    || f.isSynthetic()
                    || f.getName().startsWith("$$crochet")) {
                continue;
            }
            try {
                f.setAccessible(true);
                snap.put(f.getName(), f.get(null));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("checkpointStatics read failed: " + c.getName() + "." + f.getName(), e);
            }
        }
        STATIC_SNAPS.put(c, snap);
        return v;
    }

    /** Restores the statics captured by the matching {@link #checkpointStatics}. */
    public static void rollbackStatics(Class<?> c, int v) {
        nextRollbackVersion();
        Map<String, Object> snap = STATIC_SNAPS.remove(c);
        if (snap == null) {
            return;
        }
        for (Map.Entry<String, Object> e : snap.entrySet()) {
            try {
                Field f = c.getDeclaredField(e.getKey());
                f.setAccessible(true);
                f.set(null, e.getValue());
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("rollbackStatics write failed: " + c.getName() + "." + e.getKey(), ex);
            }
        }
    }

    /* ---------- Gap 4: reflective array checkpoint ---------- */

    /**
     * Per-array snapshot keyed by identity. {@code checkpointArray} records a
     * deep copy; {@code rollbackArray} writes it back into the same array
     * instance so all references remain valid.
     *
     * <p>V1 is single-level only — nested arrays or CRIJInstrumented element
     * propagation is driven explicitly by the caller. Moving to a version
     * that propagates through element refs is straightforward once we have
     * the array-visitor pass working.
     */
    private static final Map<Object, Object> ARRAY_SNAPS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public static int checkpointArray(Object array) {
        if (array == null || !array.getClass().isArray()) {
            throw new IllegalArgumentException("checkpointArray requires a non-null array, got "
                    + (array == null ? "null" : array.getClass()));
        }
        int v = nextCheckpointVersion();
        int len = java.lang.reflect.Array.getLength(array);
        Class<?> componentType = array.getClass().getComponentType();
        Object copy = java.lang.reflect.Array.newInstance(componentType, len);
        System.arraycopy(array, 0, copy, 0, len);
        ARRAY_SNAPS.put(array, copy);
        return v;
    }

    public static void rollbackArray(Object array, int v) {
        nextRollbackVersion();
        Object snap = ARRAY_SNAPS.remove(array);
        if (snap == null) {
            return;
        }
        int len = java.lang.reflect.Array.getLength(snap);
        System.arraycopy(snap, 0, array, 0, len);
    }

    public static Object allocateShadow(Class<?> c) {
        try {
            return U.allocateInstance(c);
        } catch (InstantiationException e) {
            throw new IllegalStateException("allocateInstance failed for " + c, e);
        }
    }

    /* ---------- klass-swap machinery ---------- */

    /**
     * Atomically flip the (compressed) klass pointer of {@code target} from the
     * klass of {@code from} to the klass of {@code to}.
     *
     * <p>Returns {@code true} if the CAS succeeded, {@code false} if another
     * thread got there first. Callers that require the swap must check; callers
     * that just want the target state (e.g. fastAccess's swap-back) can ignore.
     */
    @SuppressWarnings("deprecation")
    public static boolean changeClass(Object target, Class<?> from, Class<?> to) {
        int fromKlass = klassOf(from);
        int toKlass = klassOf(to);
        return U.compareAndSwapInt(target, KLASS_OFFSET, fromKlass, toKlass);
    }

    /** Returns the compressed klass-pointer int for {@code c}, caching it in {@link ClassMeta}. */
    public static int klassOf(Class<?> c) {
        ClassMeta meta = ClassMeta.of(c);
        Object prealloc = meta.preallocInst;
        if (prealloc == null) {
            synchronized (meta) {
                prealloc = meta.preallocInst;
                if (prealloc == null) {
                    prealloc = allocateShadow(c);
                    meta.preallocInst = prealloc;
                    meta.userKlass = U.getInt(prealloc, KLASS_OFFSET);
                }
            }
        }
        return meta.userKlass;
    }

    /* ---------- lazy Fast-proxy generation ---------- */

    public static Class<?> fastProxyFor(Class<?> userClass) {
        ClassMeta meta = ClassMeta.of(userClass);
        Class<?> proxy = meta.fastProxyClass;
        if (proxy == null) {
            synchronized (meta) {
                proxy = meta.fastProxyClass;
                if (proxy == null) {
                    proxy = generateFastProxy(userClass);
                    meta.fastProxyClass = proxy;
                }
            }
        }
        return proxy;
    }

    private static Class<?> generateFastProxy(Class<?> userClass) {
        try {
            MethodHandles.Lookup lookup = Specializer.lookupFromUserClass(userClass);
            return Specializer.specializeFast(fastTemplate(), userClass, lookup);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to generate Fast proxy for " + userClass, t);
        }
    }
}
