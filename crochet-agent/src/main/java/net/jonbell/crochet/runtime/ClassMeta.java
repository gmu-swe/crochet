package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import sun.misc.Unsafe;

import net.jonbell.crochet.annotation.Internal;

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
@Internal
public final class ClassMeta {

    private static final ClassValue<ClassMeta> CACHE = new ClassValue<>() {
        @Override
        protected ClassMeta computeValue(Class<?> userClass) {
            ClassMeta m = new ClassMeta(userClass);
            // Exactly-once registration in TOUCHED_CLASSES: ClassValue
            // serializes computeValue per key internally, so any further
            // {@link #of} call for the same class returns the cached
            // ClassMeta without re-invoking this method. That avoids a CHM
            // put on every hot-path lookup.
            CheckpointRollbackAgent.TOUCHED_CLASSES.add(userClass);
            return m;
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

    /**
     * Cached {@link VarHandle} accessors for the injected {@code $$crochetVersion}
     * field. Resolved via the user class's own {@code $$crochetLookup()} so
     * that the handle carries private-member access — the field is emitted
     * {@code ACC_PRIVATE | ACC_SYNTHETIC | ACC_TRANSIENT} and is otherwise
     * unreachable from outside the class. Published via {@code final} fields
     * on this immutable holder, so any non-null observation of
     * {@link ClassMeta#versionHandles} guarantees all slots are fully initialised.
     */
    public static final class VersionHandles {
        public final VarHandle version;

        VersionHandles(VarHandle version) {
            this.version = version;
        }
    }

    public final Class<?> userClass;

    /* ---- Gap 6: immutable bindings (race-safe publication) ---- */

    private volatile KlassBinding userBinding;
    private volatile KlassBinding fastBinding;
    private volatile FieldOffsets fieldOffsets;
    private volatile VersionHandles versionHandles;

    /** Cached bytecode-emitting {@link MethodHandles.Lookup} for this class. */
    volatile MethodHandles.Lookup lookup;

    /* ---- Gap 3 (bytecode): static-field helper fields ---- */

    /** Hidden helper class mirroring the user class's non-final statics. */
    public volatile Class<?> sfHelperClass;

    /** Singleton helper instance used as snapshot storage. */
    public volatile CRIJInstrumented sfHelper;

    /**
     * Tri-state cache for {@code @CrochetEager} membership (annotation OR
     * the {@code -Dcrochet.eagerClasses} opt-in list).
     *
     * <p>Computed once on first query per class. {@code null} means "not yet
     * resolved", {@link Boolean#TRUE} / {@link Boolean#FALSE} are the cached
     * answers. The boxed {@link Boolean} is used as a sentinel because the
     * two materialised values are interned singletons — reads are a single
     * volatile load.
     */
    volatile Boolean eagerMode;

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
            // Package-private classes (e.g. org.apache.commons.cli.Util) still
            // reject reflective invocation of their public members from outside
            // the package without setAccessible. The injected $$crochetLookup is
            // ACC_PUBLIC ACC_STATIC but the enclosing class access controls
            // whether callers can actually reach it.
            m.setAccessible(true);
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

    /**
     * Lazily resolve and cache a {@link VarHandle} for the injected
     * {@code $$crochetVersion} int field. The handle is obtained via a
     * {@link MethodHandles.Lookup} returned by the user class's
     * {@code $$crochetLookup()}; that lookup carries private-member access,
     * so it can reach the field even though it is emitted {@code ACC_PRIVATE}.
     *
     * <p>The handle hides the offset arithmetic behind a name-based API and
     * lets the JIT specialise on a stable call site rather than an
     * {@code Unsafe.*} intrinsic. It does not replace the klass-pointer CAS
     * at {@link CheckpointRollbackAgent#KLASS_OFFSET}, which still uses
     * {@link Unsafe#compareAndSwapInt} because VarHandles cannot target a
     * byte-offset in a foreign header.
     */
    public VersionHandles versionHandles() {
        VersionHandles h = versionHandles;
        if (h != null) {
            return h;
        }
        synchronized (this) {
            h = versionHandles;
            if (h != null) {
                return h;
            }
            try {
                MethodHandles.Lookup lookup = resolveLookup();
                VarHandle vh = lookup.findVarHandle(userClass, "$$crochetVersion", int.class);
                h = new VersionHandles(vh);
                versionHandles = h;
                return h;
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException(
                        "Failed to resolve VarHandle for $$crochetVersion on "
                                + userClass.getName() + "; was the Java agent attached?", e);
            }
        }
    }
}
