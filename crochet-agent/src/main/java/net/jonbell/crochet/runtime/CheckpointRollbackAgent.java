package net.jonbell.crochet.runtime;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.jonbell.crochet.annotation.CrochetEager;
import net.jonbell.crochet.annotation.Stable;

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
@Stable
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
        CRIJInstrumented t = (CRIJInstrumented) target;
        t.$$crochetRollback(rv);
        // Force the lazy-mode fastAccess-driven field restore to complete
        // BEFORE we walk {@code target}'s array-typed fields reflectively.
        // Without this, {@link ArrayRegistry#propagateRollback} reads the
        // post-workload (possibly resized) array reference rather than the
        // one registered for snapshot — in the CHM resize case, the current
        // {@code chm.table} points to {@code tab_v2}, which was never
        // registered; {@code tab_v1} (the registered original) stays
        // reachable only via {@code chm.$$crochetSnap} until fastAccess
        // copies it back. For eager-mode classes (final user classes) this
        // call is a no-op because their $$crochetRollback already finished
        // the field restore inline.
        t.$$crochetAccess();
        ArrayRegistry.propagateRollback(target, v, rv);
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
     * Set of user classes whose {@code <clinit>} has fired on the instrumented
     * JDK. Populated from a registration call emitted by
     * {@link net.jonbell.crochet.transform.FieldAdder} into the top of every
     * user class's class initializer. Unlike {@link #TOUCHED_CLASSES} (which
     * only captures classes the runtime has noticed via {@code ClassMeta.of}
     * — typically on first GETSTATIC/PUTSTATIC or first object allocation),
     * this set captures classes whose static state was initialized by any
     * code path including those that bypassed our bytecode hooks: native
     * init, reflection, framework hidden-class defines, etc.
     *
     * <p>This closes the legacy CROCHET {@code ClassCoverageProbe} /
     * {@code RootCollector} gap, where classes initialized via non-hooked
     * paths would never make it into the {@code checkpointAll} root set.
     *
     * <p>The registration call is guarded on the agent-side by a try/catch
     * in {@link #registerInitializedClass} so VERY early
     * {@code java.base} class initialization (which can fire before this
     * class itself is fully initialized) tolerates a missing helper state.
     */
    static final Set<Class<?>> INITIALIZED_CLASSES = ConcurrentHashMap.newKeySet();

    /**
     * Handle to the {@link Instrumentation} instance captured by
     * {@link net.jonbell.crochet.agent.CrochetAgent#premain}. Only populated
     * when the runtime is loaded via the {@code -javaagent} path; remains
     * {@code null} when the runtime is packed into {@code java.base} without
     * an external agent attached.
     *
     * <p>Used by {@link #checkpointAll()} as a fallback discovery mechanism:
     * when present, we iterate {@link Instrumentation#getAllLoadedClasses()}
     * and include any class that implements {@link CRIJInstrumented} but
     * has not yet been registered by {@code <clinit>} emission or
     * {@link ClassMeta#of}. This catches classes whose {@code <clinit>} fired
     * before our registration helper emit was in place (e.g. if the agent
     * attaches after some user classes have already been loaded).
     */
    private static volatile Instrumentation INSTRUMENTATION_HANDLE;

    /**
     * Called once from {@link net.jonbell.crochet.agent.CrochetAgent#premain}
     * (and {@code agentmain}) to publish the {@link Instrumentation} handle
     * so {@link #checkpointAll()} can discover classes loaded before our
     * transformer was installed. Calling this a second time is benign — the
     * handle is idempotent — but is an orderly no-op since the JVM provides
     * the same handle to each {@code premain}/{@code agentmain} invocation.
     */
    public static void setInstrumentation(Instrumentation inst) {
        INSTRUMENTATION_HANDLE = inst;
    }

    /**
     * Registration call emitted by {@link net.jonbell.crochet.transform.FieldAdder}
     * at the top of every user class's {@code <clinit>} (synthesised if
     * absent). Captures every class whose {@code <clinit>} runs on the
     * instrumented JDK, regardless of whether our runtime has seen it via
     * {@code ClassMeta.of}.
     *
     * <p>The {@code <clinit>} of some {@code java.base} classes fires VERY
     * early during JVM bootstrap, before this class itself is fully
     * initialized on the {@code -javaagent} path. To tolerate that, the
     * emitted bytecode wraps the {@code INVOKESTATIC} in its own try/catch
     * that silently swallows any {@link Throwable} — and this helper runs a
     * second try/catch of its own so that even if the static initialization
     * of {@link #INITIALIZED_CLASSES} hasn't yet run, the caller doesn't
     * see a {@link NoClassDefFoundError} or {@link ExceptionInInitializerError}.
     */
    public static void registerInitializedClass(Class<?> c) {
        if (c == null) {
            return;
        }
        try {
            INITIALIZED_CLASSES.add(c);
        } catch (Throwable ignored) {
            // Very-early boot: INITIALIZED_CLASSES may not yet be initialized
            // (the containing CheckpointRollbackAgent class initializer could
            // still be running). Silently skip — next call will succeed.
        }
    }

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
     *   <li>Every user class in the union of:
     *     <ul>
     *       <li>{@link #TOUCHED_CLASSES} — classes the runtime has materialised
     *           via {@link ClassMeta#of} (typically on first GETSTATIC/PUTSTATIC);
     *       <li>{@link #INITIALIZED_CLASSES} — classes whose {@code <clinit>}
     *           fired and invoked {@link #registerInitializedClass} (closes
     *           the legacy {@code ClassCoverageProbe} / {@code RootCollector}
     *           gap: captures classes initialised via native init, reflective
     *           force-init, or framework hidden-class defines);
     *       <li>{@link java.lang.instrument.Instrumentation#getAllLoadedClasses()} —
     *           available only on the {@code -javaagent} path, covers classes
     *           whose {@code <clinit>} fired before our transformer attached.
     *     </ul>
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
     * <p><b>What gets snapped per class</b>: the <em>reference values</em>
     * of each user class's mutable non-final static fields (see
     * {@link net.jonbell.crochet.transform.StaticFieldHelperTemplate}).
     * {@code checkpointAll} does <em>not</em> transitively snapshot the
     * state of objects those static references point at — the per-object
     * snapshot in {@link CRIJInstrumented} fires only when the object's
     * klass has been swapped to a Fast proxy, which
     * {@link #checkpoint(Object)} does explicitly. If your program mutates
     * fields of an object reachable through a static reference and expects
     * {@code rollbackAll} to reverse those mutations, you must
     * <em>also</em> call {@link #checkpoint(Object)} on that referent (or
     * annotate/opt-in via {@link CrochetEager} so its static initialiser
     * captures one proactively). {@code rollbackAll} only restores the
     * static field's reference slot; the referent's internal fields stay
     * at their post-mutation values.
     *
     * <p><b>One-shot per checkpoint phase</b>: like all per-target
     * {@link #checkpoint(Object)} calls, each {@code checkpointAll()} /
     * {@code rollbackAll(v)} pair corresponds to one version bump — after
     * rolling back to version {@code v} the registered snapshots are
     * consumed. Code that wants to roll back to the same logical state
     * multiple times must take a fresh checkpoint after each rollback:
     * <pre>
     *   int v = checkpointAll();
     *   for (int i = 0; i &lt; N; i++) {
     *     mutate();
     *     rollbackAll(v);
     *     v = checkpointAll(); // re-checkpoint between iterations
     *   }
     * </pre>
     * This matches the paper's flat-nested semantics (§3.1): a second
     * checkpoint discards the first, and rollback restores to the most
     * recent checkpoint only. The same rule applies to the per-object API
     * (see demo scenario 05-rollback-then-checkpoint).
     *
     * <p><b>Escape hatch</b>: {@code -Dcrochet.checkpointAll.skipSystem=true}
     * elides the thread-list and classloader walks.
     */
    public static int checkpointAll() {
        int v = nextCheckpointVersion();
        // Fire external-state snapshots BEFORE the root walk so hooks see
        // the pre-checkpoint heap. If any hook throws, the exception
        // propagates immediately and the root walk is skipped.
        ExternalStateRegistry.fireSnapshots();
        // Snapshot all root sets before iterating — a new $$crochetAccess
        // from a peer thread can populate TOUCHED_CLASSES mid-iteration
        // otherwise and we'd capture a class at the wrong version. The
        // union de-dupes classes present in more than one set via HashSet.
        Set<Class<?>> classes = collectRootClasses();
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
        // Stack-frame roots: no-op when the optional native JVMTI agent
        // (libcrochet-jvmti) isn't loaded. Closes the parity gap with
        // legacy Tagger.checkpointStackRoots — without this, references
        // held only by an active stack frame's locals would be silently
        // dropped from the snapshot graph.
        try {
            StackRoots.checkpointStackRoots(v, true);
        } catch (Throwable t) {
            if (Boolean.getBoolean("crochet.verboseCompat")) {
                System.err.println("checkpointAll: stack roots skipped: " + t);
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
        Set<Class<?>> classes = collectRootClasses();
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
        // Symmetric stack-frame rollback. {@code rv} (the rollback version)
        // is what the per-element {@code $$crochetRollback} expects so its
        // version-guard admits the call.
        try {
            StackRoots.rollbackStackRoots(rv, true);
        } catch (Throwable t) {
            if (Boolean.getBoolean("crochet.verboseCompat")) {
                System.err.println("rollbackAll: stack roots skipped: " + t);
            }
        }
        // Fire external-state restore hooks AFTER the heap has been restored
        // so hooks see the post-rollback heap. Throws a
        // RollbackException.HookFailure (with all hook exceptions suppressed)
        // if any hook's restore threw; the heap is already restored at that
        // point.
        ExternalStateRegistry.fireRestores();
    }

    /**
     * Unions {@link #TOUCHED_CLASSES}, {@link #INITIALIZED_CLASSES}, and (when
     * available) the instrumented subset of
     * {@link Instrumentation#getAllLoadedClasses()} into a single HashSet for
     * stable iteration. Called at the top of {@link #checkpointAll()} and
     * {@link #rollbackAll(int)} to ensure both APIs see identical roots.
     *
     * <p>The {@link Instrumentation} fallback catches classes whose
     * {@code <clinit>} fired before the agent's transformer was installed
     * and thus never got the {@link #registerInitializedClass} emit. These
     * classes are discoverable only after the fact via
     * {@code getAllLoadedClasses}; we filter on
     * {@code CRIJInstrumented.class.isAssignableFrom(c)} to get exactly the
     * classes the transformer did eventually process.
     *
     * <p>The fallback gate is the {@link #INSTRUMENTATION_HANDLE} — null on
     * the jlink/packed-runtime path (no external agent), so the fallback is
     * a no-op there. On the jlink path the JDK is pre-instrumented and every
     * class's {@code <clinit>} carries the registration call, so the primary
     * path ({@link #INITIALIZED_CLASSES}) already covers the root set.
     */
    private static Set<Class<?>> collectRootClasses() {
        Set<Class<?>> roots = new HashSet<>();
        roots.addAll(TOUCHED_CLASSES);
        roots.addAll(INITIALIZED_CLASSES);
        Instrumentation inst = INSTRUMENTATION_HANDLE;
        if (inst != null) {
            try {
                Class<?>[] loaded = inst.getAllLoadedClasses();
                for (Class<?> c : loaded) {
                    if (c == null) {
                        continue;
                    }
                    if (c.isArray() || c.isInterface() || c.isAnnotation()) {
                        continue;
                    }
                    if (!CRIJInstrumented.class.isAssignableFrom(c)) {
                        continue;
                    }
                    // Fast-proxy subclasses inherit CRIJInstrumented; the
                    // real user class (first non-CRIJFast type) is the
                    // root we care about.
                    if (CRIJFast.class.isAssignableFrom(c)) {
                        continue;
                    }
                    roots.add(c);
                }
            } catch (Throwable ignored) {
                // Instrumentation API is optional; never let a scan failure
                // abort checkpointAll.
            }
        }
        return roots;
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
     * Iterative drain entry for eager $$crochetCheckpoint / $$crochetRollback
     * bodies. Replaces a direct {@code this.$$crochetPropagate*(v)} call so
     * deep reference chains (linked lists, tree spines) don't recurse the
     * JVM stack into a {@link StackOverflowError}. See
     * {@link PropagateWorklist}.
     */
    public static void propagate(Object target, int version, boolean checkpoint) {
        PropagateWorklist.enqueueOrRun(target, version, checkpoint);
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

    /* ---------- Eager checkpoint opt-in ---------- */

    /**
     * Cached parse of {@code -Dcrochet.eagerClasses}. Re-parsed lazily when
     * the property string changes (typically never — set once at JVM startup
     * — but the tests vary it per scenario and the transformer-side
     * {@code FieldAdder} also re-resolves). The fully-qualified string form
     * (dots) is preserved here; the bytecode-transform path uses slashes.
     */
    private static volatile String eagerPropCached;
    private static volatile Set<String> eagerClassNamesCached = Collections.emptySet();

    private static Set<String> eagerClassNames() {
        String prop = System.getProperty("crochet.eagerClasses");
        if (prop == null) {
            if (eagerPropCached != null) {
                eagerPropCached = null;
                eagerClassNamesCached = Collections.emptySet();
            }
            return Collections.emptySet();
        }
        String cached = eagerPropCached;
        if (prop.equals(cached)) {
            return eagerClassNamesCached;
        }
        Set<String> parsed = parseEagerClassNames(prop);
        eagerClassNamesCached = parsed;
        eagerPropCached = prop;
        return parsed;
    }

    private static Set<String> parseEagerClassNames(String prop) {
        if (prop == null || prop.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (String part : prop.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * True iff instances of {@code c} should use the eager shallow-copy
     * checkpoint strategy instead of the default Fast-proxy + lazy snapshot.
     * Answers cache on {@link ClassMeta#eagerMode} so repeated queries are a
     * single volatile read.
     *
     * <p>Two sources, either sufficient:
     * <ul>
     *   <li>{@link CrochetEager} present on the class declaration.
     *   <li>Fully-qualified class name listed in
     *       {@code -Dcrochet.eagerClasses}.
     * </ul>
     */
    public static boolean isEagerClass(Class<?> c) {
        if (c == null) {
            return false;
        }
        // Consult the system property first with a live read — tests (and
        // rare runtime reconfigurers) can flip eligibility without the
        // cached ClassMeta.eagerMode going stale. Annotation membership
        // never changes post-class-load, so that half is safe to cache.
        Set<String> opted = eagerClassNames();
        if (!opted.isEmpty() && opted.contains(c.getName())) {
            return true;
        }
        ClassMeta meta = ClassMeta.of(c);
        Boolean cached = meta.eagerMode;
        if (cached != null) {
            return cached;
        }
        boolean eager;
        try {
            eager = c.isAnnotationPresent(CrochetEager.class);
        } catch (Throwable t) {
            // Reflection on annotations can fail under broken classloaders
            // (e.g. during early VM boot when annotation types haven't
            // finished loading). Default to "not eager" and keep going —
            // the worst outcome is Fast-proxy instead of eager, which is
            // the existing behaviour anyway.
            eager = false;
        }
        meta.eagerMode = eager;
        return eager;
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
