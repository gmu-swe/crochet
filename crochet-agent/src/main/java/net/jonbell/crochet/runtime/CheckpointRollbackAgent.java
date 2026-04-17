package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

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

    private static int VERSION_COUNTER;

    public static synchronized int nextCheckpointVersion() {
        VERSION_COUNTER++;
        if (VERSION_COUNTER % 2 == 0) {
            VERSION_COUNTER++;
        }
        return VERSION_COUNTER;
    }

    public static synchronized int nextRollbackVersion() {
        VERSION_COUNTER++;
        if (VERSION_COUNTER % 2 != 0) {
            VERSION_COUNTER++;
        }
        return VERSION_COUNTER;
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
    public static void swapToFastProxy(Object target, Class<?> userClass) {
        Class<?> fastProxy = fastProxyFor(userClass);
        changeClass(target, userClass, fastProxy);
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
        int v = obj.$$crochetGetVersion();
        if (v == 0) {
            // No checkpoint in flight — shouldn't happen on a proxy, but be defensive.
            changeClass(obj, proxyClass, userClass);
            return;
        }
        if ((v & 1) == 1) {
            // Odd — checkpoint state. Take a fresh snapshot (flat-nested:
            // a newer checkpoint discards any older snap).
            Object shadow = allocateShadow(userClass);
            obj.$$crochetCopyFieldsTo(shadow);
            obj.$$crochetSetSnap(shadow);
            // Propagate: transform all directly-referenced CRIJInstrumented
            // objects into their Fast proxies. Cycle-safe via version guard.
            obj.$$crochetPropagateCheckpoint(v);
        } else {
            // Even — rollback state. Restore from the snap if we have one.
            Object snap = obj.$$crochetGetSnap();
            if (snap != null) {
                obj.$$crochetCopyFieldsFrom(snap);
                obj.$$crochetSetSnap(null);
            }
            obj.$$crochetPropagateRollback(v);
        }
        changeClass(obj, proxyClass, userClass);
    }

    public static Object allocateShadow(Class<?> c) {
        try {
            return U.allocateInstance(c);
        } catch (InstantiationException e) {
            throw new IllegalStateException("allocateInstance failed for " + c, e);
        }
    }

    /* ---------- klass-swap machinery ---------- */

    @SuppressWarnings("deprecation")
    public static void changeClass(Object target, Class<?> from, Class<?> to) {
        int fromKlass = klassOf(from);
        int toKlass = klassOf(to);
        // CAS — if another thread already flipped the klass, we just proceed;
        // that other thread got there first and either swapped the same way
        // or moved it to a state we'll re-observe next time around.
        U.compareAndSwapInt(target, KLASS_OFFSET, fromKlass, toKlass);
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
