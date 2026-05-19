package net.jonbell.crochet.runtime;

import net.jonbell.crochet.annotation.Internal;

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
@Internal
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
     * rollback has fired (written from
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
     *
     * <p><b>Int, not long</b>: the gate is used only as a boolean-ish
     * "is any checkpoint ever active" signal — the actual value doesn't
     * matter to consumers, only {@code !=0}. Making it int lets us emit
     * the site-level gate as {@code GETSTATIC + IFEQ} (6 bytes) rather
     * than {@code GETSTATIC + LCONST_0 + LCMP + IFEQ} (8 bytes), and
     * avoids the {@code LCMP} instruction entirely on the hot path.
     * Theoretical wraparound after {@code Integer.MAX_VALUE}
     * checkpoints would momentarily flip the gate back to 0 between
     * counter wrap and the next bump, opening a one-bump window where
     * a pre-hook could miss its snap. That is not reachable in any
     * realistic workload (2^31 checkpoints in a single JVM lifetime).
     */
    public static volatile int VERSION_GATE;

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
        // {@code VERSION_GATE} subsumes the former {@code READY} check.
        // {@code VERSION_GATE} can only become non-zero when a checkpoint
        // fires, which can only happen after user code runs, which only
        // runs after premain (where {@code READY} is set). So
        // {@code VERSION_GATE != 0L} implies {@code READY == true} by
        // causal order. One fewer volatile load per pre-hook call.
        if (VERSION_GATE == 0) return;
        CheckpointRollbackAgent.noteStaticAccess(userClass);
    }

    /**
     * Pre-hook emitted by
     * {@link net.jonbell.crochet.transform.FieldAccessWrapper} before
     * every GETFIELD/PUTFIELD of an instrumented owner's field. Routes
     * through here so the {@code VERSION_GATE == 0} fast-path can
     * eliminate the pre-hook entirely for the "agent attached, no
     * checkpoint ever fired" case that dominates DaCapo and other
     * production steady-state workloads.
     *
     * <p>Under the prior emit shape (direct {@code INVOKEVIRTUAL
     * owner.$$crochetAccess()}), every GETFIELD/PUTFIELD in
     * {@code java.base} paid a vtable dispatch to a single-RETURN NOOP
     * body on user-class instances and (post-JIT) inlined to zero — but
     * the interpreter + C1 phase before full JIT'ing, plus the bytecode
     * inflation on methods not hot enough to reach C2, accumulated into
     * a substantial overhead once Gap 7's field-wrap expansion brought
     * ~10k new sites into scope. Measured: biojava 1.32x → 5.60x,
     * tradebeans 1.34x → 3.23x, tradesoap 0.67x → 2.24x between
     * pre-Gap-7 and post-correctness baselines.
     *
     * <p>Routing through this helper collapses the steady-state site to
     * a single volatile-long read + branch, which the JIT inlines into
     * the caller. When {@code VERSION_GATE == 0}, the call becomes a
     * dead branch and C2 eliminates it entirely. When a checkpoint is
     * active, we fall through to {@code $$crochetAccess} on the
     * instance — {@link CRIJInstrumented} INVOKEINTERFACE here is
     * megamorphic (many owner types call through the same helper), but
     * is only reached once a checkpoint has fired, which is rare.
     *
     * <p>The prior rejected variant
     * ({@code CheckpointRollbackAgent.fastAccessIfProxy(Object)} with
     * {@code if (obj instanceof CRIJFast) fastAccess(...)}) was measured
     * 20-40% slower than unguarded {@code INVOKEVIRTUAL} on h2, because
     * it paid a secondary-super-cache check for the interface
     * {@code instanceof} on every call. This variant avoids that: the
     * {@code VERSION_GATE} check is a plain volatile-long compare, not
     * a type-system query, so steady-state cost is lower than both.
     */
    public static void fieldAccess(Object target) {
        if (VERSION_GATE == 0) return;
        ((CRIJInstrumented) target).$$crochetAccess();
    }

    /**
     * Pre-hook emitted by
     * {@link net.jonbell.crochet.transform.ArrayAccessWrapper} before
     * every typed xASTORE. Forwards to
     * {@link ArrayRegistry#beforeStore} once ready and at least one
     * checkpoint has fired.
     */
    public static void beforeStore(Object array) {
        // {@code READY} check dropped; see {@link #noteStaticAccess}.
        if (VERSION_GATE == 0) return;
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
        // Fast path: when no checkpoint has ever fired (the dominant case
        // for DaCapo and other agent-attached-but-unused workloads), skip
        // the ArrayRegistry lookup and fall through to the native bulk-copy.
        // Saves a CHM identity-lookup per intercepted arraycopy.
        //
        // {@link ArrayRegistry} must be force-loaded from
        // {@link net.jonbell.crochet.agent.CrochetAgent#install} for this
        // gate to be safe — the original (pre-gate) code path here always
        // called {@code CheckpointRollbackAgent.interceptedArraycopy},
        // which eagerly loaded {@code ArrayRegistry} via its body's
        // {@code ArrayRegistry.beforeStore} reference during JVM bootstrap.
        // With the gate, that eager load no longer happens; the first
        // non-gate-zero call would otherwise land in the middle of
        // {@code CrochetTransformer.transform} (ASM parsing), and the
        // recursive transform for {@code ArrayRegistry} itself fires
        // {@link ClassCircularityError}.
        if (VERSION_GATE == 0) {
            System.arraycopy(src, sPos, dst, dPos, len);
            return;
        }
        CheckpointRollbackAgent.interceptedArraycopy(src, sPos, dst, dPos, len);
    }
}
