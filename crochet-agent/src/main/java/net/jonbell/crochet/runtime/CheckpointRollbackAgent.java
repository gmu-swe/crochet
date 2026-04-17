package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    public static final long KLASS_OFFSET;

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

    /**
     * Global checkpoint/rollback version counter. Paper §3.4: "uses atomic
     * compare-and-swap operations" — lock-free CAS retry loop gives I1 (unique)
     * and I2 (monotone) without locking.
     *
     * <p><b>Path A design (64-bit internal counter, 32-bit per-instance field)</b>:
     * this counter is 64-bit so long-running processes can mint &gt; 2^31
     * distinct versions without the internal arithmetic overflowing mid-CAS.
     * The {@code $$crochetVersion} field injected into every user class is
     * still 32-bit (int), because changing the field descriptor to {@code J}
     * is a cross-module rewrite of {@link net.jonbell.crochet.transform.FieldAdder}
     * and its sentinel/CAS emit. The tradeoff:
     * <ul>
     *   <li><b>Global uniqueness</b>: protected up to 2^62 checkpoints
     *       before the long arithmetic wraps.
     *   <li><b>Per-instance uniqueness</b>: at most 2^31 distinct values.
     *       Beyond that, the int truncation of the returned version makes the
     *       per-instance field wrap — a theoretical I1 violation for a single
     *       object, but only reachable on pathological workloads. A JVM
     *       doing 10k checkpoints per second would take 6 days to wrap int;
     *       real workloads amortize over tens of objects, not one.
     *   <li>We narrow {@code long → int} at return time; callers see int and
     *       the emitted bytecode stays int-descriptor.
     * </ul>
     *
     * <p>For full 64-bit per-instance versions (Path B), {@code FieldAdder}
     * would emit {@code $$crochetVersion} as a {@code J} (long) field and
     * update every sentinel CAS in the emitted bytecode to {@code compareAndSwapLong}.
     * Deferred as unnecessary for any realistic workload.
     */
    private static final AtomicLong VERSION_COUNTER = new AtomicLong(0);

    /**
     * Debug-only: once the underlying counter crosses this threshold, log a
     * one-shot warning that per-instance int wraparound is imminent. Gated
     * via {@code -Dcrochet.verboseCompat=true} to stay silent on production
     * builds. A constant below {@link Integer#MAX_VALUE} so we still emit the
     * warning before an actual collision is observable in user code.
     */
    private static final long PER_INSTANCE_WRAP_WARN_THRESHOLD = (1L << 30);

    /** One-shot latch for the per-instance overflow warning. */
    private static volatile boolean perInstanceOverflowWarned;

    public static int nextCheckpointVersion() {
        while (true) {
            long cur = VERSION_COUNTER.get();
            long next = cur + 1;
            if ((next & 1L) == 0L) {
                next++; // force odd (checkpoint)
            }
            if (VERSION_COUNTER.compareAndSet(cur, next)) {
                maybeWarnPerInstanceOverflow(next);
                return (int) next;
            }
        }
    }

    public static int nextRollbackVersion() {
        while (true) {
            long cur = VERSION_COUNTER.get();
            long next = cur + 1;
            if ((next & 1L) != 0L) {
                next++; // force even (rollback)
            }
            if (next == 0L) {
                next = 2L; // preserve 0 as "no checkpoint" sentinel
            }
            if (VERSION_COUNTER.compareAndSet(cur, next)) {
                maybeWarnPerInstanceOverflow(next);
                return (int) next;
            }
        }
    }

    private static void maybeWarnPerInstanceOverflow(long counter) {
        if (counter < PER_INSTANCE_WRAP_WARN_THRESHOLD) {
            return;
        }
        if (perInstanceOverflowWarned) {
            return;
        }
        if (Boolean.getBoolean("crochet.verboseCompat")) {
            perInstanceOverflowWarned = true;
            System.err.println("[crochet] VERSION_COUNTER crossed " + PER_INSTANCE_WRAP_WARN_THRESHOLD
                    + " (= 2^30). Per-instance $$crochetVersion (int) will wrap at 2^31;"
                    + " consider migrating to Path B (long per-instance field) if this run"
                    + " is expected to produce >2^31 checkpoints.");
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
     * user has to opt in via explicit checkpoint() on the referent. A
     * one-shot stderr warning is emitted via
     * {@link RuntimeTracer#noteUnproxyableClass} when {@code -Dcrochet.verboseCompat=true},
     * so users can discover they're hitting the silent no-op without spam
     * on production builds.
     */
    public static boolean isUnproxyable(Class<?> userClass) {
        if (userClass == null) {
            return true;
        }
        if (Modifier.isFinal(userClass.getModifiers())) {
            RuntimeTracer.noteUnproxyableClass(userClass);
            return true;
        }
        return false;
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
            v = (int) versionHandle.getVolatile(obj);
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

    /**
     * Per-class reflective static-field snapshots. Separate from
     * {@link ArrayRegistry} (which tracks per-instance array state) and from
     * the bytecode-level {@code sfHelper} path (which operates on materialised
     * hidden-helper instances). This map is only populated by the direct
     * {@link #checkpointStatics} / {@link #rollbackStatics} entrypoints, used
     * by clients that want a one-shot reflective snapshot without paying the
     * helper-generation cost.
     *
     * <p>Changed from {@code synchronizedMap(new IdentityHashMap<>())} to a
     * {@link ConcurrentHashMap}: {@link Class} keys already have identity
     * equality via {@link Object#equals}, and CHM avoids the coarse monitor
     * that {@code synchronizedMap} takes on every put/get.
     */
    private static final ConcurrentHashMap<Class<?>, Map<String, Object>> STATIC_FIELD_SNAPS =
            new ConcurrentHashMap<>();

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
        STATIC_FIELD_SNAPS.put(c, snap);
        return v;
    }

    public static void rollbackStatics(Class<?> c, int v) {
        nextRollbackVersion();
        Map<String, Object> snap = STATIC_FIELD_SNAPS.remove(c);
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
     * Reflective array checkpoint. Routed through {@link ArrayRegistry} so the
     * bytecode-level path (xASTORE pre-hook) and the reflective API share one
     * weak-keyed registry. Historically a second strong-referenced
     * {@code ARRAY_SNAPS} {@link IdentityHashMap} pinned arrays that a user
     * called {@code checkpointArray} on — the unify eliminates that pin.
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

    /* ---------- Gap 6: version field helpers for emitted bytecode ---------- */
    //
    // These three static methods are the entry points called by the bytecode
    // emitted in FieldAdder.emitVersionGuardedEntry. They were historically
    // backed by Unsafe.{getIntVolatile,compareAndSwapInt,putIntVolatile} on a
    // raw offset; we now go through a VarHandle resolved via the user class's
    // $$crochetLookup(), which the JIT devirtualises to the same machine code
    // while making the intent (access the named injected field) explicit and
    // composable with future access-mode tuning. The klass-pointer CAS at
    // KLASS_OFFSET still uses Unsafe because VarHandle cannot address a raw
    // byte offset in an arbitrary object header.

    /** Volatile read of $$crochetVersion on target. */
    public static int versionVolatileGet(Object target, Class<?> userClass) {
        VarHandle vh = ClassMeta.of(userClass).versionHandles().version;
        return (int) vh.getVolatile(target);
    }

    /** CAS on $$crochetVersion; returns true iff expect matched. */
    public static boolean versionCas(Object target, Class<?> userClass, int expect, int update) {
        VarHandle vh = ClassMeta.of(userClass).versionHandles().version;
        return vh.compareAndSet(target, expect, update);
    }

    public static void versionStore(Object target, Class<?> userClass, int value) {
        VarHandle vh = ClassMeta.of(userClass).versionHandles().version;
        vh.setVolatile(target, value);
    }

    /* ---------- lazy Fast-proxy generation ---------- */

    public static Class<?> fastProxyFor(Class<?> userClass) {
        return ClassMeta.of(userClass).fastBinding().clazz;
    }

    public static Class<?> fastProxyForInternal(Class<?> userClass) {
        // Called only from {@link ClassMeta#fastBinding} under its DCL lock.
        // The binding itself caches the class reference; this entrypoint
        // simply generates a fresh one. Legacy callers that passed through
        // here expecting a lazy cache should go via {@link #fastProxyFor}
        // which returns {@code meta.fastBinding().clazz} directly.
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
        if (RuntimeTracer.ENABLED) {
            RuntimeTracer.bumpSfHelper(userClass);
        }
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

    /**
     * Fused {@code sfHelperFor(owner).$$crochetAccess()} pre-hook emitted by
     * {@link net.jonbell.crochet.transform.StaticFieldRewriter}. The legacy
     * two-call pattern was a pure no-op for classes the agent chose not to
     * instrument (enums, interfaces, annotations, and classes whose
     * $$crochetLookup throws) — but the {@code $$crochetAccess} leg was an
     * {@code INVOKEINTERFACE} against an open polymorphic world (every
     * instrumented user class can contribute its own SF helper class to the
     * inline cache), which the JIT couldn't devirtualize. On WildFly
     * startup with {@code org.jboss.logging.Logger$Level} hit &gt;8M times,
     * the wasted itable lookup dominated.
     *
     * <p>Fusing into a single {@code INVOKESTATIC} lets the JIT inline the
     * entire fast path: a ClassValue read (into {@link ClassMeta}) + volatile
     * field load. If {@code meta.sfHelper} is already materialised we
     * return immediately — the original {@code $$crochetAccess} was a no-op
     * anyway, so nothing on the helper is actually exercised here.
     */
    public static void noteStaticAccess(Class<?> userClass) {
        if (RuntimeTracer.ENABLED) {
            RuntimeTracer.bumpSfHelper(userClass);
        }
        // No-checkpoint fast path. Before any thread has ever called
        // checkpoint* / rollback*, {@link #VERSION_COUNTER} stays at its
        // initial 0 and there is nothing to snapshot. Gate the whole pre-hook
        // on this read — once inlined, the fast path collapses to one plain
        // load + branch. Telemetry on tradebeans startup showed 3.2M wasted
        // calls on a single class ({@code org.h2.value.ValueNull}) before any
        // checkpoint ever fires; this gate elides all of them.
        //
        // <b>Opaque read (JEP 193 access mode)</b>: the gate is a best-effort
        // early-exit, not a synchronizer. A stale {@code 0} reading just means
        // the caller takes the same slow path it would have taken before; a
        // stale non-zero read is harmless because helper materialisation is
        // idempotent (ClassValue CAS-serialises the computeValue). We don't
        // need volatile's bidirectional happens-before here — the subsequent
        // sfHelperFor() call establishes its own happens-before through
        // ClassValue's internal synchronisation. Using opaque instead of
        // volatile removes the compiler's reorder barriers on this read,
        // which on ARM/POWER matters for the inlined JIT output; on x86 the
        // encoding is identical but the change documents the looser intent.
        //
        // Once VERSION_COUNTER becomes non-zero (any checkpoint or rollback),
        // we fall through to the DCL-style materialisation check below, which
        // matches the pre-gate semantics.
        if (VERSION_COUNTER.getOpaque() == 0L) {
            return;
        }
        ClassMeta meta = ClassMeta.of(userClass);
        if (meta.sfHelper != null) {
            return;
        }
        // Cold path: force helper materialisation so subsequent calls take
        // the inlined fast path above. {@link #sfHelperFor} is the single
        // entry point that populates meta.sfHelper via the ClassValue
        // computeValue path.
        sfHelperFor(userClass);
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
            // Drop ClassOption.STRONG: the helper is held alive by
            // {@link ClassMeta#sfHelper}, which is itself pinned by the
            // ClassMeta entry in the per-class ClassValue cache, which is
            // pinned by the user class's classloader for as long as the
            // class is loaded. That chain keeps the helper reachable; STRONG
            // would additionally tie the helper's lifetime to its defining
            // lookup class-loader, which blocks unloading in testing /
            // redefinition scenarios without buying any safety we don't
            // already have.
            Class<?> helperClass = lookup.defineHiddenClass(bytes, true,
                            java.lang.invoke.MethodHandles.Lookup.ClassOption.NESTMATE)
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
