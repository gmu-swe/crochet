package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.jonbell.crochet.transform.ProxyTemplate;
import net.jonbell.crochet.transform.Specializer;

import sun.misc.Unsafe;

/**
 * Klass-swap machinery plus lazy Fast-proxy generation. Owns the Unsafe
 * handle, the HotSpot klass-pointer offset detection, the klass-CAS
 * primitives, the fastAccess race-winner pattern, and the VarHandle-based
 * version-field helpers emitted from {@link net.jonbell.crochet.transform.FieldAdder}.
 *
 * <p>Gap 6 / V2: klass-swap + lazy snapshot with race-winner concurrency.
 * {@link #swapToFastProxy(Object, Class)} swaps an object to a per-user-class
 * hidden Fast proxy. The proxy's {@code $$crochetAccess} override delegates
 * to {@link #fastAccess(CRIJInstrumented)}, which uses a CAS on the klass
 * header to ensure exactly one thread does the snapshot/restore work while
 * peers return cheaply.
 *
 * <p>Gap 8 (exception safety): the winner's snapshot/restore runs inside a
 * try/catch that zeroes the version + nulls the snap on throw and raises a
 * {@link RollbackException} with {@link RollbackException#POISON_VERSION}.
 * Because the winner swapped klass <em>at the top</em>, no finally clause is
 * needed — a thrown path leaves the object in the user-class state with a
 * consistent (zeroed) view, preserving the paper's I3 continuity invariant.
 */
final class FastProxySupport {

    private FastProxySupport() {}

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

    /**
     * Byte offset of the (compressed) klass pointer in a HotSpot object header.
     *
     * <p>Defaults to 8 with HotSpot's compressed-class-pointer layout
     * (mark word at 0..7, 4-byte compressed klass at 8..11). Overridable via
     * {@code -Dcrochet.klassOffset=<bytes>} for exotic layouts — but note
     * that our klass-CAS uses {@link Unsafe#compareAndSwapInt} (4-byte width),
     * which is only correct when the klass field is exactly 4 bytes wide.
     *
     * <p>The guard in {@link #detectCompressedClassPointers} throws from the
     * static initializer if the klass pointer is 8 bytes (because the flag
     * {@code -XX:-UseCompressedClassPointers} was passed) rather than silently
     * corrupting half of it via the 4-byte CAS.
     */
    static final long KLASS_OFFSET;

    /**
     * True iff HotSpot's compressed-class-pointer mode is enabled at startup
     * (the default on 64-bit JVMs). Distinct from {@code UseCompressedOops},
     * which controls compression of reference fields; {@code UseCompressedClassPointers}
     * specifically controls the width of the klass field in the object header.
     */
    private static final boolean COMPRESSED_KLASS;

    /**
     * A probe class with a single declared {@code int} field. The offset of
     * {@link Probe#marker} relative to the start of a {@code Probe} instance
     * tells us the exact size of the object header + padding — which under
     * HotSpot is 12 bytes for compressed-oops and 16 bytes for uncompressed.
     */
    private static final class Probe {
        int marker;
    }

    static {
        String override = System.getProperty("crochet.klassOffset");
        if (override != null) {
            try {
                KLASS_OFFSET = Long.parseLong(override.trim());
                COMPRESSED_KLASS = KLASS_OFFSET == 8L; // best-effort inference
            } catch (NumberFormatException e) {
                throw new ExceptionInInitializerError(
                        "Invalid -Dcrochet.klassOffset: " + override);
            }
        } else {
            COMPRESSED_KLASS = detectCompressedClassPointers();
            KLASS_OFFSET = 8L;
            if (!COMPRESSED_KLASS) {
                throw new ExceptionInInitializerError(
                        "CROCHET requires HotSpot compressed-class-pointers (the default on "
                                + "64-bit JVMs with heaps < 32 GiB). The klass-pointer CAS at "
                                + "offset 8 uses Unsafe.compareAndSwapInt (4-byte width); when "
                                + "-XX:-UseCompressedClassPointers is passed the klass pointer "
                                + "is 8 bytes wide and that CAS would corrupt half of it. "
                                + "Re-run with +UseCompressedClassPointers (the default), or "
                                + "override via -Dcrochet.klassOffset=<bytes> if you know your "
                                + "platform has a 4-byte klass pointer at a non-standard offset. "
                                + "Detected object-header + padding size: "
                                + probeHeaderSize() + " bytes "
                                + "(expected 12 for compressed-class-pointers; 16 means not "
                                + "compressed).");
            }
        }
    }

    /**
     * Probe-based compressed-class-pointers detector. Under HotSpot's default
     * {@code +UseCompressedClassPointers}, a {@link Probe} instance lays out
     * as {@code [mark:8][klass:4][marker:4]} so the marker field sits at
     * offset 12. When {@code -UseCompressedClassPointers} is passed, the
     * klass pointer is 8 bytes and the layout becomes
     * {@code [mark:8][klass:8][marker:4]}, shifting the marker to offset 16.
     * The offset test is the load-bearing signal and is robust across JDK
     * versions (the mark word has been 8 bytes since Java 7).
     *
     * <p>Independent of {@code UseCompressedOops}, which compresses
     * <em>reference</em> fields (not the klass pointer). On modern Temurin
     * the two flags default to {@code +} on 64-bit platforms with heaps
     * under 32 GiB.
     */
    private static boolean detectCompressedClassPointers() {
        try {
            Field marker = Probe.class.getDeclaredField("marker");
            long markerOffset = U.objectFieldOffset(marker);
            // Compressed klass: [mark:8][klass:4][marker:4] → offset 12.
            // Uncompressed klass: [mark:8][klass:8][marker:4] → offset 16.
            return markerOffset == 12L;
        } catch (Throwable t) {
            // If the probe itself fails we fall back to assuming compressed
            // (the HotSpot default) rather than crash — users can opt in to
            // strict detection via -Dcrochet.klassOffset= to force a specific
            // layout.
            return true;
        }
    }

    /** Diagnostic helper used in the guard's error message. Returns -1 on failure. */
    private static long probeHeaderSize() {
        try {
            return U.objectFieldOffset(Probe.class.getDeclaredField("marker"));
        } catch (Throwable t) {
            return -1L;
        }
    }

    /* ---------- fast proxy template ---------- */

    private static volatile byte[] FAST_TEMPLATE;

    private static byte[] fastTemplate() {
        byte[] t = FAST_TEMPLATE;
        if (t == null) {
            synchronized (FastProxySupport.class) {
                t = FAST_TEMPLATE;
                if (t == null) {
                    t = ProxyTemplate.emit();
                    FAST_TEMPLATE = t;
                }
            }
        }
        return t;
    }

    /* ---------- klass-swap machinery ---------- */

    /**
     * Called from the emitted {@code $$crochetCheckpoint} / {@code $$crochetRollback}
     * bodies once the caller has installed the sentinel version {@code -v}. Looks
     * up (and generates if needed) the fast proxy, then CASes the klass from
     * user to proxy. A failed CAS is benign — a peer may have already swapped;
     * the sentinel version is what drives fastAccess regardless.
     */
    static void swapToFastProxy(Object target, Class<?> userClass) {
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
    static void swapToFastProxy(Object target, Class<?> userClass, int priorVersion) {
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
     * user has to opt in via explicit checkpoint() on the referent. A
     * one-shot stderr warning is emitted via
     * {@link RuntimeTracer#noteUnproxyableClass} when {@code -Dcrochet.verboseCompat=true},
     * so users can discover they're hitting the silent no-op without spam
     * on production builds.
     */
    static boolean isUnproxyable(Class<?> userClass) {
        if (userClass == null) {
            return true;
        }
        if (Modifier.isFinal(userClass.getModifiers())) {
            RuntimeTracer.noteUnproxyableClass(userClass);
            return true;
        }
        return false;
    }

    static boolean changeClass(Object target, Class<?> from, Class<?> to) {
        int fromKlass = klassOf(from);
        int toKlass = klassOf(to);
        return U.compareAndSwapInt(target, KLASS_OFFSET, fromKlass, toKlass);
    }

    static int klassOf(Class<?> c) {
        return ClassMeta.of(c).userBinding().klass;
    }

    static Object allocateShadow(Class<?> c) {
        try {
            return U.allocateInstance(c);
        } catch (InstantiationException e) {
            throw new IllegalStateException("allocateInstance failed for " + c, e);
        }
    }

    /* ---------- F.1 dirty-bit: noteDirty ---------- */

    /**
     * F.1: set {@code $$crochetDirty = 1} on {@code inst}, using the
     * VarHandle resolved for the user class. Tolerates null {@code inst}
     * (no-op) and classes whose dirty VarHandle was not resolved (pre-F.1
     * instrumentation or failed field lookup — treated as always-dirty which
     * is the safe fallback).
     *
     * <p>Write uses plain (non-release) {@link VarHandle#set} semantics.
     * The stripe-lock in {@link #fastAccess} provides the ordering guarantee
     * for the checkpoint path; the PUTFIELD pre-hook fires {@code $$crochetAccess}
     * after this set, which either enters the stripe-lock (klass=proxy) or is a
     * no-op (klass=user). Either way, a subsequent stripe-lock holder's volatile
     * read of dirty observes dirty==1.
     */
    static void noteDirty(Object inst) {
        if (inst == null) {
            return;
        }
        Class<?> c = inst.getClass();
        // Walk past any Fast proxy layer to the real user class.
        while (c != null && CRIJFast.class.isAssignableFrom(c)) {
            c = c.getSuperclass();
        }
        if (c == null) {
            return;
        }
        ClassMeta.VersionHandles handles = ClassMeta.of(c).versionHandles();
        if (handles.dirty == null) {
            // Pre-F.1 class or failed VarHandle resolution: no dirty field.
            // Safe to skip — fastAccess treats missing dirty handle as always-dirty.
            return;
        }
        handles.dirty.set(inst, 1);
    }

    /* ---------- fastAccess race-winner ---------- */

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
     * the only bumping paths are still {@link VersionCounter#nextCheckpointVersion} /
     * {@link VersionCounter#nextRollbackVersion} CAS loops.
     */
    static void fastAccess(CRIJInstrumented obj) {
        Class<?> observedClass = obj.getClass();
        if (RuntimeTracer.ENABLED) {
            RuntimeTracer.bumpFastAccess(observedClass);
        }
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
        // $$crochetRollback's CAS. VarHandle.getVolatile is semantically
        // equivalent to Unsafe.getIntVolatile and gives the JIT a stable
        // call site for inlining.
        VarHandle versionHandle = ClassMeta.of(userClass).versionHandles().version;
        int v = (int) versionHandle.getVolatile(obj);
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
        //
        // Tapestry stripefix: the stripe acquire is a ReentrantLock, NOT a
        // JVM-level synchronized block. The synchronized form deadlocked when
        // Crochet was layered under Fray's scheduler — Crochet's premain
        // forces FastProxySupport / FastAccessCoordinator to load before
        // Fray's transformer registers, so the bytecode's monitorenter never
        // got wrapped with Fray's scheduling hook. ReentrantLock routes the
        // contended path through LockSupport.park (in java.base, transformed
        // by Fray's JlinkPlugin at the time the instrumented JDK is built),
        // which Fray's scheduler does see. See FastAccessCoordinator javadoc
        // for the full mechanism + correctness argument; from the lock
        // semantics side this swap is happens-before equivalent (JLS §17.4.5).
        FastAccessCoordinator.Stripe stripe = FastAccessCoordinator.lockFor(obj);
        stripe.lock.lock();
        try {
            // Re-check under the lock: a peer may have won the klass CAS
            // while we were blocked acquiring the stripe, in which case the
            // work is done and we just return. Happens-before is established
            // by the stripe lock's release-acquire pair (ReentrantLock
            // unlock/lock has the same memory semantics as monitorexit/enter).
            if (!CRIJFast.class.isAssignableFrom(obj.getClass())) {
                return;
            }
            // Re-read the version with volatile semantics in case a peer
            // finalized while we blocked.
            v = (int) versionHandle.getVolatile(obj);
            realV = (v < 0) ? -v : v;
            if (realV == 0) {
                swapKlassProxyToUser(obj, userClass);
                return;
            }
            boolean rollbackBranch = (realV & 1) == 0;
            try {
                if (!rollbackBranch) {
                    // F.1 dirty-bit optimization: skip shadow allocation if dirty==0
                    // AND a prior snap already exists (snap != null). When snap is null
                    // (first checkpoint ever for this instance) we always allocate to
                    // avoid the first-checkpoint race described in SOUNDNESS.md §7b.
                    //
                    // Invariant (SOUNDNESS.md §5): dirty==0 here means no PUTFIELD has
                    // fired on this instance since the prior checkpoint cleared dirty
                    // (which happened under this same stripe lock). The stripe-lock
                    // release-acquire provides the happens-before from prior-clear to
                    // this-read, so dirty==0 is a reliable signal.
                    ClassMeta.VersionHandles handles = ClassMeta.of(userClass).versionHandles();
                    VarHandle dirtyVh = handles.dirty;
                    int dirty = (dirtyVh != null) ? (int) dirtyVh.getVolatile(obj) : 1;
                    Object existingSnap = obj.$$crochetGetSnap();
                    if (dirty != 0 || existingSnap == null) {
                        // Checkpoint: paper §3.1 flat-nested semantics — the latest
                        // checkpoint overwrites any previous snap. A racing entrant
                        // that sees the same version and was blocked behind us will
                        // re-check klass under the lock and find klass=user; it
                        // returns cheaply so it doesn't double-install.
                        Object shadow = allocateShadow(userClass);
                        obj.$$crochetCopyFieldsTo(shadow);
                        obj.$$crochetSetSnap(shadow);
                        // Clear dirty under the stripe lock — this write is visible
                        // to the next checkpoint's dirty-read via the release-acquire
                        // of the stripe lock (ReentrantLock unlock/lock).
                        if (dirtyVh != null) {
                            dirtyVh.setVolatile(obj, 0);
                        }
                    }
                    // else: snap != null && dirty == 0 → prior snap is still valid.
                    // The prior snap holds field values identical to current values
                    // (no PUTFIELD fired since the prior checkpoint cleared dirty).
                    // No shadow allocation needed; the rollback path will use the
                    // existing snap.
                    PropagateWorklist.enqueueOrRun(obj, realV, true);
                } else {
                    Object snap = obj.$$crochetGetSnap();
                    if (snap != null) {
                        obj.$$crochetCopyFieldsFrom(snap);
                        obj.$$crochetSetSnap(null);
                    }
                    // F.1: clear dirty on rollback. After rollback the instance is
                    // in its pre-checkpoint state, equivalent to "never mutated since
                    // the last snap." Setting dirty=0 ensures the next checkpoint can
                    // skip the shadow if no PUTFIELD fires before it.
                    ClassMeta.VersionHandles handles = ClassMeta.of(userClass).versionHandles();
                    VarHandle dirtyVh = handles.dirty;
                    if (dirtyVh != null) {
                        dirtyVh.setVolatile(obj, 0);
                    }
                    PropagateWorklist.enqueueOrRun(obj, realV, false);
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
            // transition — any peer still blocked on the stripe lock will
            // re-check CRIJFast after release and return cheaply.
            swapKlassProxyToUser(obj, userClass);
        } finally {
            stripe.lock.unlock();
        }
    }

    private static void swapKlassProxyToUser(CRIJInstrumented obj, Class<?> userClass) {
        ClassMeta meta = ClassMeta.of(userClass);
        int userK  = meta.userBinding().klass;
        int proxyK = meta.fastBinding().klass;
        U.compareAndSwapInt(obj, KLASS_OFFSET, proxyK, userK);
    }

    /* ---------- Gap 6: version field helpers for emitted bytecode ----------
     *
     * These three static methods back the entry points called by the bytecode
     * emitted in FieldAdder.emitVersionGuardedEntry. They were historically
     * backed by Unsafe.{getIntVolatile,compareAndSwapInt,putIntVolatile} on a
     * raw offset; we now go through a VarHandle resolved via the user class's
     * $$crochetLookup(), which the JIT devirtualises to the same machine code
     * while making the intent (access the named injected field) explicit and
     * composable with future access-mode tuning. The klass-pointer CAS at
     * KLASS_OFFSET still uses Unsafe because VarHandle cannot address a raw
     * byte offset in an arbitrary object header.
     */

    /** Volatile read of $$crochetVersion on target. */
    static int versionVolatileGet(Object target, Class<?> userClass) {
        VarHandle vh = ClassMeta.of(userClass).versionHandles().version;
        return (int) vh.getVolatile(target);
    }

    /** CAS on $$crochetVersion; returns true iff expect matched. */
    static boolean versionCas(Object target, Class<?> userClass, int expect, int update) {
        VarHandle vh = ClassMeta.of(userClass).versionHandles().version;
        return vh.compareAndSet(target, expect, update);
    }

    static void versionStore(Object target, Class<?> userClass, int value) {
        VarHandle vh = ClassMeta.of(userClass).versionHandles().version;
        vh.setVolatile(target, value);
    }

    /* ---------- lazy Fast-proxy generation ---------- */

    static Class<?> fastProxyFor(Class<?> userClass) {
        return ClassMeta.of(userClass).fastBinding().clazz;
    }

    /**
     * Called only from {@link ClassMeta#fastBinding} under its DCL lock.
     * The binding itself caches the class reference; this entrypoint
     * simply generates a fresh one. Legacy callers that passed through
     * here expecting a lazy cache should go via {@link #fastProxyFor}
     * which returns {@code meta.fastBinding().clazz} directly.
     */
    static Class<?> fastProxyForInternal(Class<?> userClass) {
        return generateFastProxy(userClass);
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
