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
        if (isUnproxyable(userClass)) {
            return;
        }
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
        if (isUnproxyable(userClass)) {
            return;
        }
        try {
            Class<?> fastProxy = fastProxyFor(userClass);
            changeClass(target, userClass, fastProxy);
        } catch (RuntimeException | Error e) {
            ((CRIJInstrumented) target).$$crochetSetVersion(priorVersion);
            throw new RollbackException(RollbackException.POISON_VERSION, e);
        }
    }

    /**
     * True if {@code userClass} cannot host a hidden Fast proxy. Final classes
     * (String, Integer, Long, ...) can't be subclassed; record classes are
     * implicitly final; enums are ACC_ENUM which we skip at instrumentation
     * time anyway.
     *
     * <p>For these classes $$crochetCheckpoint records the version bump but
     * no klass swap happens. fastAccess will never fire because the klass
     * never transitions to the proxy. User-visible effect: checkpoint and
     * rollback are no-ops on instances of final classes.
     *
     * <p>This is semantically correct for immutable finals (String etc.) —
     * there's nothing to roll back. For the rare mutable final class, the
     * user has to opt in via explicit checkpoint() on the referent.
     */
    public static boolean isUnproxyable(Class<?> userClass) {
        return userClass == null || Modifier.isFinal(userClass.getModifiers());
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

    /**
     * Invoked by the Fast proxy's overridden {@code $$crochetAccess}. Gap 6
     * race-winner pattern driven by three CASes:
     * <ol>
     *   <li>The klass CAS in {@link #swapKlassProxyToUser} (proxy &rarr; user)
     *       is the race winner — exactly one thread flips the header and
     *       therefore exactly one thread ran the snap/restore body under the
     *       stripe lock.
     *   <li>Version install (sentinel {@code -v}) is a CAS done by the emitted
     *       {@code $$crochetCheckpoint} / {@code $$crochetRollback} bodies
     *       (see {@code FieldAdder.emitVersionGuardedEntry}).
     *   <li>Version finalize ({@code -v} &rarr; {@code v}) is a second CAS in
     *       the same emitted bodies, also independent of this method.
     * </ol>
     *
     * <p><b>Fast paths taken without locking</b>:
     * <ul>
     *   <li>If the observed klass is already the user class, a peer finished
     *       the work — return immediately. Zero locks, zero atomics.
     *   <li>If the object's {@code $$crochetVersion} is {@code 0} (no active
     *       checkpoint), straight CAS the klass back to user and return.
     *   <li>A contended path that falls through the stripe lock and finds
     *       klass already user also returns without doing any snap work.
     * </ul>
     *
     * <p><b>Contended (cold) path</b>: when the version is non-zero the
     * winner still needs to install a snap (checkpoint) or restore from snap
     * (rollback), which is a multi-word write that requires mutual exclusion
     * against concurrent winners for the <em>same</em> object. We use a
     * stripe-lock bank ({@link FastAccessCoordinator}) keyed by
     * {@code identityHashCode(obj) & 0xff} — with 256 stripes, distinct
     * objects almost never collide, so the effective critical section is
     * per-object rather than the old per-class. See that class for the
     * invariant-preservation argument.
     *
     * <p><b>Gap 8 (exception safety)</b>: the winner's work is wrapped in
     * try/catch. On throw we zero the version + snap and swap klass to user
     * before raising {@link RollbackException}, leaving the object in a
     * consistent "no active checkpoint" state even under partial failure.
     *
     * <p><b>Sentinel {@code -v}</b>: the sentinel closes the "version bumped
     * but klass not yet swapped" window for <em>readers of the version</em>.
     * fastAccess itself decodes {@code realV = |v|} so it sees the same
     * parity/branch regardless of whether the caller observed mid-update or
     * finalized state. I1 (unique v) and I2 (monotone) are preserved because
     * the only bumping paths are still {@link #nextCheckpointVersion} /
     * {@link #nextRollbackVersion} CAS loops.
     */
    public static void fastAccess(CRIJInstrumented obj) {
        Class<?> observedClass = obj.getClass();
        // Uncontended fast path: klass is already user (a peer finished the
        // work, or we're here via a reentrant call that already did it).
        // Zero atomics, zero locks.
        if (!CRIJFast.class.isAssignableFrom(observedClass)) {
            return;
        }
        Class<?> userClass = observedClass.getSuperclass();
        if (userClass == null) {
            throw new RollbackException(RollbackException.POISON_VERSION,
                    new IllegalStateException("fastAccess: proxy has no superclass"));
        }

        // Read version with volatile semantics so we observe the latest
        // sentinel or finalized value published by $$crochetCheckpoint /
        // $$crochetRollback's CAS.
        long versionOff = ClassMeta.of(userClass).fieldOffsets().versionOffset;
        int v = U.getIntVolatile(obj, versionOff);
        // Sentinel decode (paper Listing 3): mid-update callers may observe -v
        // between sentinel install and finalize; treat realV = |v|.
        int realV = (v < 0) ? -v : v;

        // Fast path (no snap work needed): version is zero — no active
        // checkpoint — so just flip klass back to user and return. Multiple
        // threads racing here all succeed or observe the post-CAS state;
        // CAS failure just means a peer beat us, also benign.
        if (realV == 0) {
            swapKlassProxyToUser(obj, userClass);
            return;
        }

        // Cold path: snap install / restore is a multi-word operation that
        // requires mutual exclusion per object against concurrent winners.
        // Use a stripe-lock keyed by identityHashCode — finer-grained than
        // the previous synchronized(userClass) which serialized across all
        // instances of the same class. See FastAccessCoordinator javadoc for
        // the invariant-preservation argument (I1, I2, sentinel handling).
        Object stripe = FastAccessCoordinator.lockFor(obj);
        synchronized (stripe) {
            // Re-check under the lock: a peer may have won the klass CAS
            // while we were blocked acquiring the stripe, in which case the
            // work is done and we just return. Happens-before is established
            // by the stripe monitor's release-acquire.
            if (!CRIJFast.class.isAssignableFrom(obj.getClass())) {
                return;
            }
            // Re-read the version with volatile semantics in case a peer
            // finalized while we blocked.
            v = U.getIntVolatile(obj, versionOff);
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
            // Work done: CAS klass proxy→user. This is the race-winner
            // transition — any peer still blocked on the stripe monitor will
            // re-check CRIJFast after release and return cheaply.
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

    /**
     * ClassValue-backed cache of per-user-class static-field helpers. The
     * legacy path used {@code synchronized(meta) + DCL} on a
     * {@link ClassMeta#sfHelper} field, which serialized ALL first-access
     * callers per class. {@link ClassValue#get} does one-shot lock-free
     * materialisation via a CAS-based internal table, so subsequent lookups
     * are a fast hash-table read without any monitor acquisition.
     *
     * <p>Classes our transformer skips (enums, annotations, classes without
     * {@code $$crochetLookup}) fall through to {@link NoopSFHelper#INSTANCE}
     * so the user's GETSTATIC/PUTSTATIC still hits the real static field —
     * checkpoint/rollback silently skip these statics, matching the final-
     * class proxy policy.
     *
     * <p>The ClassValue instance is stored on {@link ClassMeta} (a cached
     * per-class object) so the hot-path lookup is a direct field load of
     * {@code ClassMeta.sfHelper} and hits the DCL-style fast path without
     * traversing the ClassValue cache on every call. The ClassValue only
     * participates in first-access materialisation; after that the helper
     * is pinned on the ClassMeta.
     */
    public static CRIJInstrumented sfHelperFor(Class<?> userClass) {
        ClassMeta meta = ClassMeta.of(userClass);
        CRIJInstrumented h = meta.sfHelper;
        if (h != null) {
            return h;
        }
        // Cold path: delegate to ClassValue for lock-free one-shot
        // materialisation. concurrent callers observe the same helper
        // without any synchronized() block. Once assigned, meta.sfHelper
        // is stable for the lifetime of the ClassMeta and the fast path
        // above covers all subsequent calls.
        return SF_HELPERS.get(userClass);
    }

    private static final ClassValue<CRIJInstrumented> SF_HELPERS = new ClassValue<>() {
        @Override
        protected CRIJInstrumented computeValue(Class<?> userClass) {
            // ClassValue serializes computeValue per key internally using a
            // CAS-based one-shot, so concurrent callers observe the same
            // helper without us doing any additional locking.
            CRIJInstrumented helper;
            if (!hasLookup(userClass)) {
                helper = NoopSFHelper.INSTANCE;
            } else {
                ClassMeta meta = ClassMeta.of(userClass);
                helper = generateSFHelper(userClass, meta);
            }
            // Back-compat + fast-path pin: populate the ClassMeta.sfHelper
            // volatile so subsequent sfHelperFor calls hit a single-load
            // DCL-style fast path (see sfHelperFor). Also readable by
            // rollbackClassAtVersion directly.
            ClassMeta.of(userClass).sfHelper = helper;
            return helper;
        }
    };

    private static boolean hasLookup(Class<?> userClass) {
        try {
            userClass.getDeclaredMethod("$$crochetLookup");
            return true;
        } catch (Throwable t) {
            // NoClassDefFoundError can fire here if the class was instrumented
            // but its classloader can't resolve CRIJInstrumented (happens in
            // plugin-style classloader hierarchies DaCapo uses). Treat any
            // failure as "not instrumented" so we fall back to the no-op
            // SF helper rather than breaking the user's program.
            return false;
        }
    }

    /** Placeholder helper for classes the agent chose not to instrument. */
    private static final class NoopSFHelper implements CRIJInstrumented {
        static final NoopSFHelper INSTANCE = new NoopSFHelper();
        @Override public void $$crochetCopyFieldsTo(Object to) {}
        @Override public void $$crochetCopyFieldsFrom(Object old) {}
        @Override public void $$crochetCheckpoint(int version) {}
        @Override public void $$crochetRollback(int version) {}
        @Override public void $$crochetPropagateCheckpoint(int version) {}
        @Override public void $$crochetPropagateRollback(int version) {}
        @Override public int $$crochetGetVersion() { return 0; }
        @Override public void $$crochetSetVersion(int version) {}
        @Override public Object $$crochetGetSnap() { return null; }
        @Override public void $$crochetSetSnap(Object snap) {}
        @Override public void $$crochetAccess() {}
        @Override public boolean $$crochetIsRollbackState() { return false; }
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
            if (Boolean.getBoolean("crochet.verboseCompat")) {
                System.err.println("Crochet SF helper gen FAILED for " + userClass.getName()
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
            }
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
