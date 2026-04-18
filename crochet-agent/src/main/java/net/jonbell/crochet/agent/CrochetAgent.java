package net.jonbell.crochet.agent;

import java.lang.instrument.Instrumentation;

import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

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
        inst.addTransformer(new TransformerWrapper(), true);
    }
}
