package edu.neu.ccs.prl.crochet.ttd;

import java.lang.instrument.Instrumentation;

/**
 * Java agent for crochet-ttd Phase 1. Registers a
 * {@link LineMarkerTransformer} that auto-instruments every line of
 * any method bearing {@link TimeTravelBody}.
 *
 * <p>Run with: {@code java -javaagent:crochet-ttd.jar -javaagent:crochet-agent.jar ...}
 *
 * <p>Order matters when paired with the Crochet agent — TTD should
 * load FIRST so its line markers are inserted before Crochet's
 * field-access wrappers see the bytecode. Crochet's transforms don't
 * touch ordinary INVOKESTATIC instructions, so this composition is
 * safe (verified by smoke tests).
 */
public final class TtdAgent {

    private TtdAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    private static void install(Instrumentation inst) {
        if (Boolean.getBoolean("crochet.ttd.debug")) {
            System.err.println("[ttd-agent] installed");
        }
        // NondetTransformer must be installed BEFORE LineMarkerTransformer so
        // that nondet call-site rewrites are visible to the line-marker pass.
        // Both transformers are independent (nondet rewrites INVOKESTATIC/VIRTUAL;
        // line markers emit new INVOKESTATIC Ttd.lineHit calls at line boundaries).
        inst.addTransformer(new NondetTransformer(), true);
        inst.addTransformer(new LineMarkerTransformer(), true);
    }
}
