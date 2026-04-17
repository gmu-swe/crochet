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
    // lock-free under contention via a CAS retry loop. I1 (unique) and I2
    // (monotone) follow from CAS-on-successor-value.
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

    private static Class<?> realUserClassOf(Object target) {
        Class<?> c = target.getClass();
        if (CRIJFast.class.isAssignableFrom(c)) {
            return c.getSuperclass();
        }
        return c;
    }

    /* ---------- called from instrumented code ---------- */

    /**
     * Called from the emitted {@code $$crochetCheckpoint} / {@code $$crochetRollback}
     * bodies once the caller has installed the sentinel version {@code -v}. Looks
     * up (and generates if needed) the fast proxy, then CASes the klass from
     * user to proxy. A failed CAS is benign — a peer may have already swapped;
     * the sentinel version is what drives fastAccess regardless.
     */
    public static void swapToFastProxy(Object target, Class<?> userClass) {
        Class<?> fastProxy = fastProxyFor(userClass);
        changeClass(target, userClass, fastProxy);
    }

    /**
     * Backwards-compat shim used by bytecode emitted by an earlier FieldAdder
     * that passed a {@code priorVersion} argument. The new sentinel-aware
     * emit does not call this form; kept non-deprecated so older cached
     * instrumentation still links.
     */
    public static void swapToFastProxy(Object target, Class<?> userClass, int priorVersion) {
        try {
            Class<?> fastProxy = fastProxyFor(userClass);
            changeClass(target, userClass, fastProxy);
        } catch (RuntimeException | Error e) {
            ((CRIJInstrumented) target).$$crochetSetVersion(priorVersion);
            throw new RollbackException(RollbackException.POISON_VERSION, e);
        }
    }

    /**
     * Invoked by the Fast proxy's overridden {@code $$crochetAccess}. Gap 6
     * race-winner pattern: we CAS the klass from fast-proxy back to user at the
     * <em>top</em> of the method, so exactly one thread enters the
     * snapshot/restore body. Peers whose CAS fails return immediately.
     *
     * <p>The winner's work is wrapped in try/catch for gap 8: an exception
     * mid-snapshot leaves the klass at user (already swapped), and we zero
     * version+snap so the object is in a consistent "no active checkpoint"
     * state before raising {@link RollbackException}.
     */
    public static void fastAccess(CRIJInstrumented obj) {
        Class<?> observedClass = obj.getClass();
        // Quick exit: klass may have already been CAS'd back to the user class
        // by a peer that completed the work. If so, we can proceed to the
        // caller's field access without any hook overhead.
        if (!CRIJFast.class.isAssignableFrom(observedClass)) {
            return;
        }
        Class<?> userClass = observedClass.getSuperclass();
        if (userClass == null) {
            throw new RollbackException(RollbackException.POISON_VERSION,
                    new IllegalStateException("fastAccess: proxy has no superclass"));
        }

        int v = obj.$$crochetGetVersion();
        // Sentinel decode (paper Listing 3): mid-update callers may observe -v
        // between sentinel install and finalize; treat realV = |v|.
        int realV = (v < 0) ? -v : v;

        // Gap 6/8 resolution: we serialize ALL fastAccess entrants for this
        // object via a lock derived from the object's user class, so there is
        // no "winner publishes user-class but work still in flight" window.
        // Work runs to completion while klass is still proxy; klass CAS happens
        // at the END of the critical section. Non-winners that wait on the
        // same lock observe klass == user on re-check and return cheaply.
        //
        // We intentionally do NOT synchronize on {@code obj} itself to avoid
        // contending with user code that might lock on the object. Instead we
        // use the object's $$crochetSnap slot when present (rollback path) or
        // the user-class {@link Class} object as a class-wide lock (checkpoint
        // path when no snap exists yet).
        Object lock = obj.$$crochetGetSnap();
        if (lock == null) {
            // Checkpoint-before-first-access: no snap exists yet, sync on class.
            lock = userClass;
        }
        synchronized (lock) {
            // Re-check under the lock: a peer that we were blocked behind may
            // already have finished the work and CAS'd klass to user.
            if (!CRIJFast.class.isAssignableFrom(obj.getClass())) {
                return;
            }
            // Re-read the version in case a peer completed while we blocked.
            v = obj.$$crochetGetVersion();
            realV = (v < 0) ? -v : v;
            if (realV == 0) {
                swapKlassProxyToUser(obj, userClass);
                return;
            }
            boolean rollbackBranch = (realV & 1) == 0;
            try {
                if (!rollbackBranch) {
                    // Checkpoint: paper §3.1 flat-nested semantics — the latest
                    // checkpoint overwrites any previous snap. A racing entrant
                    // that sees the same version and was blocked behind us will
                    // re-check klass under the lock and find klass=user; it
                    // returns cheaply so it doesn't double-install.
                    Object shadow = allocateShadow(userClass);
                    obj.$$crochetCopyFieldsTo(shadow);
                    obj.$$crochetSetSnap(shadow);
                    obj.$$crochetPropagateCheckpoint(realV);
                } else {
                    Object snap = obj.$$crochetGetSnap();
                    if (snap != null) {
                        obj.$$crochetCopyFieldsFrom(snap);
                        obj.$$crochetSetSnap(null);
                    }
                    obj.$$crochetPropagateRollback(realV);
                }
            } catch (Throwable t) {
                // Gap 8: zero the version and snap to leave a consistent
                // no-active-checkpoint state before rethrowing as poison.
                try {
                    obj.$$crochetSetVersion(0);
                    obj.$$crochetSetSnap(null);
                } catch (Throwable ignore) {
                }
                swapKlassProxyToUser(obj, userClass);
                if (t instanceof RollbackException re) {
                    throw re;
                }
                throw new RollbackException(RollbackException.POISON_VERSION, t);
            }
            // Work is done; swap klass from proxy to user. Any peer that
            // blocked on our lock will re-check CRIJFast and return cheaply.
            swapKlassProxyToUser(obj, userClass);
        }
    }

    private static void swapKlassProxyToUser(CRIJInstrumented obj, Class<?> userClass) {
        ClassMeta meta = ClassMeta.of(userClass);
        int userK  = meta.userBinding().klass;
        int proxyK = meta.fastBinding().klass;
        U.compareAndSwapInt(obj, KLASS_OFFSET, proxyK, userK);
    }

    /* ---------- Gap 3: reflective static-field checkpoint ---------- */

    private static final Map<Class<?>, Map<String, Object>> STATIC_SNAPS =
            Collections.synchronizedMap(new IdentityHashMap<>());

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

    public static boolean changeClass(Object target, Class<?> from, Class<?> to) {
        int fromKlass = klassOf(from);
        int toKlass = klassOf(to);
        return U.compareAndSwapInt(target, KLASS_OFFSET, fromKlass, toKlass);
    }

    public static int klassOf(Class<?> c) {
        return ClassMeta.of(c).userBinding().klass;
    }

    /* ---------- Gap 6: version field-offset helpers for emitted bytecode ---------- */

    /** Volatile read of $$crochetVersion on target. */
    public static int versionVolatileGet(Object target, Class<?> userClass) {
        long off = ClassMeta.of(userClass).fieldOffsets().versionOffset;
        return U.getIntVolatile(target, off);
    }

    /** CAS on $$crochetVersion; returns true iff expect matched. */
    public static boolean versionCas(Object target, Class<?> userClass, int expect, int update) {
        long off = ClassMeta.of(userClass).fieldOffsets().versionOffset;
        return U.compareAndSwapInt(target, off, expect, update);
    }

    public static void versionStore(Object target, Class<?> userClass, int value) {
        long off = ClassMeta.of(userClass).fieldOffsets().versionOffset;
        U.putIntVolatile(target, off, value);
    }

    /* ---------- lazy Fast-proxy generation ---------- */

    public static Class<?> fastProxyFor(Class<?> userClass) {
        return ClassMeta.of(userClass).fastBinding().clazz;
    }

    public static Class<?> fastProxyForInternal(Class<?> userClass) {
        ClassMeta meta = ClassMeta.of(userClass);
        Class<?> proxy = meta.fastProxyClass;
        if (proxy == null) {
            proxy = generateFastProxy(userClass);
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

    /* ---------- Gap 3 (bytecode): static-field helper lookup ---------- */

    public static CRIJInstrumented sfHelperFor(Class<?> userClass) {
        ClassMeta meta = ClassMeta.of(userClass);
        CRIJInstrumented h = meta.sfHelper;
        if (h != null) {
            return h;
        }
        synchronized (meta) {
            h = meta.sfHelper;
            if (h != null) {
                return h;
            }
            h = generateSFHelper(userClass, meta);
            meta.sfHelper = h;
            return h;
        }
    }

    private static CRIJInstrumented generateSFHelper(Class<?> userClass, ClassMeta meta) {
        try {
            String userInternal = userClass.getName().replace('.', '/');
            String helperInternal = userInternal + "$$crochetSFHelper";
            java.util.List<net.jonbell.crochet.transform.StaticFieldHelperTemplate.FieldRecord> statics =
                    staticFieldsOf(userClass);
            byte[] bytes = net.jonbell.crochet.transform.StaticFieldHelperTemplate.emit(
                    userInternal, helperInternal, statics);
            MethodHandles.Lookup lookup = meta.resolveLookup();
            Class<?> helperClass = lookup.defineHiddenClass(bytes, true,
                            java.lang.invoke.MethodHandles.Lookup.ClassOption.NESTMATE,
                            java.lang.invoke.MethodHandles.Lookup.ClassOption.STRONG)
                    .lookupClass();
            meta.sfHelperClass = helperClass;
            Object instance = allocateShadow(helperClass);
            return (CRIJInstrumented) instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to generate SF helper for " + userClass, t);
        }
    }

    private static java.util.List<net.jonbell.crochet.transform.StaticFieldHelperTemplate.FieldRecord>
    staticFieldsOf(Class<?> c) {
        java.util.List<net.jonbell.crochet.transform.StaticFieldHelperTemplate.FieldRecord> out = new java.util.ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            if (f.getName().startsWith("$$crochet")) continue;
            String desc = org.objectweb.asm.Type.getDescriptor(f.getType());
            out.add(new net.jonbell.crochet.transform.StaticFieldHelperTemplate.FieldRecord(
                    f.getName(), desc));
        }
        return out;
    }

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
