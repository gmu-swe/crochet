package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import sun.misc.Unsafe;

/**
 * Per-user-class metadata that the legacy CROCHET attached by monkey-patching
 * {@code java.lang.Class} (see legacy/src/main/java/java/lang/Class.java). We
 * can't inject fields into {@code java.lang.Class} on modern JVMs, so this
 * state lives in a {@link ClassValue} keyed by the user class.
 *
 * <p>Gap 6 (thread safety): the paper's per-class klass pointer and
 * field-offset resolution are published via {@link KlassBinding}, an
 * immutable holder whose {@code final} fields inherit JMM final-field
 * guarantees — any thread observing a non-null binding sees both fields fully
 * constructed even without a volatile read.
 */
public final class ClassMeta {

    private static final ClassValue<ClassMeta> CACHE = new ClassValue<>() {
        @Override
        protected ClassMeta computeValue(Class<?> userClass) {
            return new ClassMeta(userClass);
        }
    };

    public static ClassMeta of(Class<?> userClass) {
        return CACHE.get(userClass);
    }

    /**
     * Immutable binding of a preallocated shadow instance and the klass-pointer
     * int extracted from its header. Publication by writing the reference to a
     * volatile field is sufficient because {@code final} fields on the
     * {@code KlassBinding} enforce safe initialization order per JMM §17.5.
     */
    public static final class KlassBinding {
        public final Class<?> clazz;
        public final Object prealloc;
        public final int klass;

        KlassBinding(Class<?> clazz, Object prealloc, int klass) {
            this.clazz = clazz;
            this.prealloc = prealloc;
            this.klass = klass;
        }
    }

    /**
     * Cached offsets of the injected per-object int/Object fields. Published
     * via the {@code final} initialization guarantee: once {@link #fieldOffsets}
     * is set on the enclosing ClassMeta, the corresponding {@code FieldOffsets}
     * instance is fully constructed.
     */
    public static final class FieldOffsets {
        public final long versionOffset;
        public final long snapOffset;

        FieldOffsets(long v, long s) {
            this.versionOffset = v;
            this.snapOffset = s;
        }
    }

    public final Class<?> userClass;

    /* ---- Gap 6: immutable bindings (race-safe publication) ---- */

    private volatile KlassBinding userBinding;
    private volatile KlassBinding fastBinding;
    private volatile FieldOffsets fieldOffsets;

    /* ---- Gap 0/V1 legacy backing fields (still read by callers) ---- */

    public volatile Object preallocInst;
    public volatile int userKlass;
    public volatile Class<?> fastProxyClass;
    public volatile Object fastProxyPreallocInst;
    public volatile int fastProxyKlass;
    public volatile long versionOffset;
    public volatile long snapOffset;
    public volatile MethodHandles.Lookup lookup;

    /* ---- Gap 3 (bytecode): static-field helper fields ---- */

    /** Hidden helper class mirroring the user class's non-final statics. */
    public volatile Class<?> sfHelperClass;

    /** Singleton helper instance used as snapshot storage. */
    public volatile CRIJInstrumented sfHelper;

    private ClassMeta(Class<?> userClass) {
        this.userClass = userClass;
    }

    public MethodHandles.Lookup resolveLookup() {
        MethodHandles.Lookup l = lookup;
        if (l != null) {
            return l;
        }
        try {
            Method m = userClass.getDeclaredMethod("$$crochetLookup");
            Object result = m.invoke(null);
            l = (MethodHandles.Lookup) result;
            lookup = l;
            return l;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "User class " + userClass.getName()
                            + " was not instrumented with $$crochetLookup; was the Java agent attached?",
                    e);
        }
    }

    /**
     * Lazily compute and publish the user-klass binding. The {@code final}
     * fields of {@link KlassBinding} mean any thread that observes a non-null
     * {@code userBinding} reference is guaranteed to see {@code prealloc} and
     * {@code klass} already initialized.
     */
    public KlassBinding userBinding() {
        KlassBinding b = userBinding;
        if (b != null) {
            return b;
        }
        synchronized (this) {
            b = userBinding;
            if (b != null) {
                return b;
            }
            Object p = CheckpointRollbackAgent.allocateShadow(userClass);
            int k = CheckpointRollbackAgent.U.getInt(p, CheckpointRollbackAgent.KLASS_OFFSET);
            b = new KlassBinding(userClass, p, k);
            userBinding = b;
            preallocInst = p;
            userKlass = k;
            return b;
        }
    }

    /**
     * Lazily generate and publish the fast-proxy binding. The proxy class is
     * created only once per user class; concurrent callers after the first
     * serialize on {@code synchronized(this)} and observe the already-published
     * binding.
     */
    public KlassBinding fastBinding() {
        KlassBinding b = fastBinding;
        if (b != null) {
            return b;
        }
        synchronized (this) {
            b = fastBinding;
            if (b != null) {
                return b;
            }
            Class<?> proxy = CheckpointRollbackAgent.fastProxyForInternal(userClass);
            Object p = CheckpointRollbackAgent.allocateShadow(proxy);
            int k = CheckpointRollbackAgent.U.getInt(p, CheckpointRollbackAgent.KLASS_OFFSET);
            b = new KlassBinding(proxy, p, k);
            fastBinding = b;
            fastProxyClass = proxy;
            fastProxyPreallocInst = p;
            fastProxyKlass = k;
            return b;
        }
    }

    /**
     * Lazily resolve offsets of {@code $$crochetVersion} and {@code $$crochetSnap}
     * on the user class. Offsets are constants per klass so one publish suffices.
     */
    public FieldOffsets fieldOffsets() {
        FieldOffsets fo = fieldOffsets;
        if (fo != null) {
            return fo;
        }
        synchronized (this) {
            fo = fieldOffsets;
            if (fo != null) {
                return fo;
            }
            try {
                Unsafe u = CheckpointRollbackAgent.U;
                Field vf = findField(userClass, "$$crochetVersion");
                Field sf = findField(userClass, "$$crochetSnap");
                long vo = u.objectFieldOffset(vf);
                long so = u.objectFieldOffset(sf);
                fo = new FieldOffsets(vo, so);
                fieldOffsets = fo;
                versionOffset = vo;
                snapOffset = so;
                return fo;
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("User class " + userClass.getName()
                        + " is missing injected field — was the Java agent attached?", e);
            }
        }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
