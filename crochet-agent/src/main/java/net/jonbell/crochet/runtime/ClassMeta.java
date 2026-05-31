package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandle;
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
        ClassValue<ClassMeta> cache = CACHE;
        if (cache == null) {
            // ClassMeta.<clinit> still in flight — see {@link #warmup()} for
            // why this should not normally happen, and callers (noteDirty,
            // fastAccess) for the null-tolerant fallback.
            return null;
        }
        return cache.get(userClass);
    }

    /**
     * Force {@link #CACHE}'s assignment to complete by triggering this class's
     * {@code <clinit>} now, while {@code RuntimeReady.VERSION_GATE == 0}. This
     * is invoked from {@link net.jonbell.crochet.agent.CrochetAgent#premain}
     * to prevent the following cycle observed under the instrumented JDK
     * after the first {@code Crochet.checkpoint()} call lifts VERSION_GATE:
     *
     * <pre>
     *   instrumented-JDK PUTFIELD
     *     → noteDirty(obj)
     *       → ClassMeta.of(obj.getClass())     // first reference: triggers <clinit>
     *         → ClassMeta.<clinit> runs
     *           → new ClassValue&lt;&gt;() { ... }   // constructs anonymous subclass
     *             → ClassValue.&lt;init&gt; PUTFIELDs (instrumented under Gap 7)
     *               → noteDirty(thisClassValue)
     *                 → ClassMeta.of(...)        // CACHE still null → NPE
     * </pre>
     *
     * <p>{@link FastProxySupport#NOTE_DIRTY_GUARD} stops re-entry through
     * {@code noteDirty}, but the prehook also calls {@code $$crochetAccess}
     * which can reach {@link #of} via {@link FastProxySupport#fastAccess}
     * without going through that guard. The cheapest, broadest fix is to
     * pre-resolve {@code CACHE} during {@code premain} (when {@code
     * VERSION_GATE == 0}, so the inner PUTFIELDs short-circuit before
     * reaching {@code noteDirty} at all) — same idiom as
     * {@link ArrayRegistry#warmup()} for the same class of bug.
     */
    public static void warmup() {
        // Touching CACHE forces ClassMeta's <clinit> to complete. The
        // get() call exercises the full path so the ClassValue's own
        // <clinit> + the first computeValue's <clinit>-of-its-impl-class
        // also resolve here. After return, CACHE is non-null and any
        // subsequent ClassMeta.of() lookup from a noteDirty / fastAccess
        // path on a hot stack will hit the cache directly.
        CACHE.get(Object.class);
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
     * and {@code $$crochetDirty} fields. Resolved via the user class's own
     * {@code $$crochetLookup()} so that the handles carry private-member access —
     * both fields are emitted {@code ACC_PRIVATE | ACC_SYNTHETIC | ACC_TRANSIENT}
     * and are otherwise unreachable from outside the class. Published via
     * {@code final} fields on this immutable holder, so any non-null observation of
     * {@link ClassMeta#versionHandles} guarantees all slots are fully initialised.
     *
     * <p>The {@code dirty} VarHandle backs the F.1 dirty-bit optimization.
     * {@code $$crochetDirty} is set to 1 by the PUTFIELD pre-hook (in
     * {@code FieldAccessWrapper}) and read/cleared by {@code FastProxySupport.fastAccess}
     * under the stripe lock. Volatile access semantics on the read side (via
     * {@link VarHandle#getVolatile}) pair with the stripe-lock release-acquire to
     * establish happens-before between the dirty-bit clear at one checkpoint and the
     * dirty-bit read at the next checkpoint.
     */
    public static final class VersionHandles {
        public final VarHandle version;
        /** VarHandle for {@code $$crochetDirty} (F.1 dirty-bit). May be null if the
         *  user class predates F.1 instrumentation (fallback: treat as always dirty). */
        public final VarHandle dirty;

        VersionHandles(VarHandle version, VarHandle dirty) {
            this.version = version;
            this.dirty = dirty;
        }
    }

    public final Class<?> userClass;

    /* ---- Gap 6: immutable bindings (race-safe publication) ---- */

    private volatile KlassBinding userBinding;
    private volatile KlassBinding fastBinding;
    private volatile FieldOffsets fieldOffsets;
    private volatile VersionHandles versionHandles;

    /** Cached bytecode-emitting {@link MethodHandles.Lookup} for this class.
     *
     *  <p>Populated by {@link #publishLookup(MethodHandles.Lookup)} from the
     *  user class's {@code <clinit>} (via {@code
     *  CheckpointRollbackAgent.registerInitializedClass(Class, Lookup)}), so
     *  the Lookup is captured inside the user class's own frame and has
     *  {@code lookupClass() == userClass}. The reflective fallback in
     *  {@link #resolveLookup()} would otherwise return a Lookup whose
     *  {@code lookupClass()} is {@code DirectMethodHandleAccessor} due to
     *  {@code @CallerSensitive} resolution through {@code Method.invoke}. */
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

    /**
     * Cache a Lookup captured inside the user class's own {@code <clinit>}
     * frame. First write wins (the ClinitRegistrar emit is exactly once per
     * class; subsequent reflective lookups would have to match anyway).
     */
    public void publishLookup(MethodHandles.Lookup l) {
        if (l != null && lookup == null) {
            lookup = l;
        }
    }

    public MethodHandles.Lookup resolveLookup() {
        MethodHandles.Lookup l = lookup;
        if (l != null) {
            return l;
        }
        // Side-table publication (from user-class clinit) is the preferred
        // source — its Lookup was captured inside the user class's own frame
        // and has the correct lookupClass(). The reflective fallback below
        // is only reached on classes whose clinit didn't run our emit (JDK
        // internals reached during very early boot before agent install).
        l = CheckpointRollbackAgent.publishedLookup(userClass);
        if (l != null) {
            lookup = l;
            return l;
        }
        try {
            // Resolve via a MethodHandle rather than {@link
            // java.lang.reflect.Method#invoke}. {@code MethodHandles.lookup()}
            // inside the user class's {@code $$crochetLookup} body is
            // {@code @CallerSensitive}: when reached through
            // {@code Method.invoke}, the JVM's caller-class resolution
            // identifies {@code jdk.internal.reflect.DirectMethodHandleAccessor}
            // (the reflection accessor introduced in JDK 18) as the caller,
            // not the user class — so the returned Lookup has
            // {@code lookupClass() == DirectMethodHandleAccessor}. Any
            // subsequent {@code findVarHandle} then fails with
            // "symbolic reference class is not accessible: class
            // DirectMethodHandleAccessor, from class
            // net.jonbell.crochet.runtime.ClassMeta (module java.base)"
            // because that accessor is qualified-exported only to a
            // hardcoded set of modules. Invoking through
            // {@link MethodHandle} preserves the user-class frame, so
            // {@code lookupClass() == userClass} as intended. The agent
            // jar's {@link MethodHandles#lookup} call below is fine: it
            // gives ClassMeta the right to {@link MethodHandles.Lookup#unreflect}
            // any setAccessible-cleared {@link Method}.
            Method m = userClass.getDeclaredMethod("$$crochetLookup");
            m.setAccessible(true);
            MethodHandle handle = MethodHandles.lookup().unreflect(m);
            Object result = handle.invoke();
            l = (MethodHandles.Lookup) result;
            lookup = l;
            return l;
        } catch (Throwable e) {
            if (e instanceof Error err) {
                throw err;
            }
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
                // F.1: also resolve $$crochetDirty if present. Classes instrumented
                // before F.1 (or classes that failed dirty-field injection) will not have
                // this field; we tolerate that by storing null and treating dirty as
                // "always dirty" at checkpoint time (safe fallback — just no optimization).
                VarHandle dirtyVh = null;
                try {
                    dirtyVh = lookup.findVarHandle(userClass, "$$crochetDirty", int.class);
                } catch (NoSuchFieldException ignored) {
                    // Pre-F.1 class or special class that didn't get the dirty field.
                    // dirtyVh stays null; fastAccess will treat dirty as 1 (always shadow).
                }
                h = new VersionHandles(vh, dirtyVh);
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
