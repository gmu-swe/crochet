package net.jonbell.crochet.agent;

import java.lang.instrument.Instrumentation;

import net.jonbell.crochet.runtime.ArrayRegistry;
import net.jonbell.crochet.runtime.CheckpointRollbackAgent;
import net.jonbell.crochet.runtime.ClassMeta;
import net.jonbell.crochet.runtime.RuntimeReady;

public final class CrochetAgent {

    private CrochetAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    private static void install(String agentArgs, Instrumentation inst) {
        // Publish the Instrumentation handle to the runtime so
        // {@link CheckpointRollbackAgent#checkpointAll()} can use
        // {@link Instrumentation#getAllLoadedClasses()} as a fallback
        // root-discovery mechanism. Wrapped in try/catch so failure in the
        // runtime's static init on very early load paths (when packed into
        // java.base) doesn't abort agent installation.
        try {
            CheckpointRollbackAgent.setInstrumentation(inst);
        } catch (Throwable ignored) {
            // Best-effort publish; if the runtime facade isn't loaded yet,
            // the -javaagent path still falls back to TOUCHED_CLASSES +
            // INITIALIZED_CLASSES as root sources.
        }
        // Preload the lazy-reached runtime classes that the gated pre-hook
        // paths in {@link RuntimeReady} (noteStaticAccess / beforeStore /
        // fieldAccess / interceptedArraycopy) would otherwise load on the
        // first {@code VERSION_GATE != 0} call — which, if triggered from
        // inside an active {@link TransformerWrapper#transform} frame,
        // recursively re-enters the transformer for the runtime class
        // itself and fires {@link ClassCircularityError}. Loading them here
        // (while we're still inside premain on a well-controlled stack)
        // is always safe: {@code READY} is still false and
        // {@code VERSION_GATE} is still 0, so any instrumented code their
        // class initializers indirectly invoke early-exits in RuntimeReady.
        try {
            ArrayRegistry.warmup();
        } catch (Throwable ignored) {
        }
        // Eagerly initialize ClassMeta so that its static CACHE ClassValue is
        // non-null before any PUTFIELD hook can fire noteDirty → ClassMeta.of().
        // If ClassMeta is not initialized by the time the first checkpoint fires
        // (setting VERSION_GATE non-zero), noteDirty's ClassMeta.of() call sees
        // a null CACHE and throws NPE, cascading to NoClassDefFoundError for
        // ClassMeta on every subsequent reference. See ClassMeta.warmup() javadoc.
        try {
            ClassMeta.warmup();
        } catch (Throwable ignored) {
        }

        inst.addTransformer(new TransformerWrapper(), true);
        // Surface verifier: registered after TransformerWrapper so it sees
        // the final class bytes (post all transformers). Enabled only when
        // -Dcrochet.verifyInstrumented=true is set. Gate inside the verifier
        // keeps this registration itself zero-cost when disabled — the JVM
        // still calls the transformer but it exits at the ENABLED check.
        inst.addTransformer(new InstrumentedSurfaceVerifier(), false);
        // Gap 7 closure: flip the RuntimeReady flag now that the agent
        // runtime's dependency closure is installed and reachable. Before
        // this point, pre-hooks emitted in JDK bytecode (HashMap.put,
        // TreeMap.remove, etc. on the instrumented java.base) took the
        // RuntimeReady.READY==false branch and returned immediately,
        // avoiding re-entry into CheckpointRollbackAgent /
        // ArrayRegistry during JVM bootstrap. After this flip, the
        // hooks become live and full lazy-snapshot behaviour is in
        // effect. Wrapped in try/catch for the same reason as the
        // setInstrumentation call above — early-load ordering.
        try {
            RuntimeReady.markReady();
        } catch (Throwable ignored) {
        }
    }
}
