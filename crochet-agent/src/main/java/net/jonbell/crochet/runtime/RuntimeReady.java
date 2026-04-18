package net.jonbell.crochet.runtime;

/**
 * Bootstrap-safety gate. Every pre-hook that instrumented JDK classes
 * may invoke during JVM bootstrap is routed through here; the
 * forwarders return early while {@link #READY} is {@code false},
 * avoiding re-entry into {@link CheckpointRollbackAgent} /
 * {@link ArrayRegistry} before their full class-initialisation chain
 * has completed.
 *
 * <p><b>Why this is safe to reference during bootstrap</b>: this class
 * has no static-init dependencies other than the default-initialised
 * {@code volatile boolean READY} field (default {@code false}). Its
 * {@code <clinit>} does essentially nothing — the JVM's first
 * invocation of any forwarder here triggers a trivial class
 * initialisation that cannot recursively re-enter any user code or
 * deeper agent-runtime class. Reads of {@code READY} after init are
 * plain volatile loads (on x86-TSO a straight load; on AArch64 a
 * dependent load) — per-call cost is a branch predictor hit once the
 * flag stabilises.
 *
 * <p><b>Flip point</b>: {@link #markReady()} is called from
 * {@link net.jonbell.crochet.agent.CrochetAgent} once the runtime
 * dependency closure is known to be loaded. Before that, every
 * forwarder is a no-op. {@code interceptedArraycopy} is the one
 * exception: its pre-ready branch performs the real
 * {@link System#arraycopy} so bulk-copy semantics are preserved
 * during bootstrap; the lazy-snapshot tracking is what we skip, not
 * the copy itself.
 *
 * <p><b>Why {@link CheckpointRollbackAgent#fastAccess} is not gated
 * here</b>: {@code fastAccess} is only called from Fast-proxy
 * {@code $$crochetAccess} overrides, which live in hidden classes
 * created by {@link net.jonbell.crochet.transform.Specializer} on the
 * first checkpoint of a given user class. Hidden Fast proxies cannot
 * exist during JVM bootstrap because no checkpoint can have fired yet
 * — the JVM is still initialising. So there is no bootstrap-order
 * hazard on the fastAccess path.
 */
public final class RuntimeReady {

    private RuntimeReady() {}

    /**
     * Bootstrap gate. Volatile so the flip in {@link #markReady} is
     * visible across all threads without requiring an explicit fence.
     * Default value is {@code false} — callers during JVM startup see
     * false and return early via the forwarders below.
     */
    public static volatile boolean READY;

    /**
     * Pre-checkpoint gate. Non-zero iff at least one checkpoint or
     * rollback has fired (written opaquely by
     * {@link VersionCounter#nextCheckpointVersion} and
     * {@link VersionCounter#nextRollbackVersion}). Before any checkpoint
     * there is nothing to track — the pre-hooks can early-return from
     * {@link RuntimeReady} without entering {@link CheckpointRollbackAgent}
     * or {@link ArrayRegistry}.
     *
     * <p>Keeping this gate here, rather than reading
     * {@code VersionCounter.VERSION_COUNTER.getOpaque()} from
     * {@link CheckpointRollbackAgent#noteStaticAccess}, is
     * load-bearing for JDK-class bootstrap correctness. The JVM
     * invokes {@link net.jonbell.crochet.agent.TransformerWrapper#transform}
     * on every class it loads, including our own runtime support
     * classes. If that transform triggers
     * {@code TransformTracer.<clinit>} (via the GETSTATIC at BCI 26),
     * the init calls {@code Boolean.getBoolean} → {@code System.getProperty}
     * → {@code System.allowSecurityManager}, whose instrumented
     * GETSTATIC ultimately calls {@link #noteStaticAccess} here.
     * If that call then referenced {@link VersionCounter} or
     * {@link SfHelperFactory}, the JVM would be asked to load those
     * classes <em>while it is already loading them</em> (because the
     * in-flight {@code transform()} call was FOR that same class),
     * which the JVM rejects with {@link ClassCircularityError}. Keeping
     * the gate purely local to {@link RuntimeReady} means the
     * fast-path branch reaches no other package-local class.
     */
    public static volatile long VERSION_GATE;

    /**
     * Called once from {@link net.jonbell.crochet.agent.CrochetAgent}
     * after the {@link java.lang.instrument.Instrumentation} handle is
     * captured and all agent-runtime classes the forwarders might
     * touch are guaranteed loaded.
     */
    public static void markReady() {
        READY = true;
    }

    /**
     * Pre-hook emitted by
     * {@link net.jonbell.crochet.transform.StaticFieldRewriter} at
     * every GETSTATIC/PUTSTATIC of an instrumented user class.
     * Forwards to {@link CheckpointRollbackAgent#noteStaticAccess}
     * once the runtime is ready and at least one checkpoint has fired.
     */
    public static void noteStaticAccess(Class<?> userClass) {
        if (!READY) return;
        if (VERSION_GATE == 0L) return;
        CheckpointRollbackAgent.noteStaticAccess(userClass);
    }

    /**
     * Pre-hook emitted by
     * {@link net.jonbell.crochet.transform.ArrayAccessWrapper} before
     * every typed xASTORE. Forwards to
     * {@link ArrayRegistry#beforeStore} once ready and at least one
     * checkpoint has fired.
     */
    public static void beforeStore(Object array) {
        if (!READY) return;
        if (VERSION_GATE == 0L) return;
        ArrayRegistry.beforeStore(array);
    }

    /**
     * Replacement for {@code System.arraycopy} emitted by
     * {@link net.jonbell.crochet.transform.ArrayCopyInterceptor}.
     * Before the runtime is ready we still need to perform the copy —
     * the JVM bootstrap depends on correct bulk-copy semantics — we
     * just skip the registry tracking.
     */
    public static void interceptedArraycopy(Object src, int sPos, Object dst, int dPos, int len) {
        if (!READY) {
            System.arraycopy(src, sPos, dst, dPos, len);
            return;
        }
        CheckpointRollbackAgent.interceptedArraycopy(src, sPos, dst, dPos, len);
    }
}
