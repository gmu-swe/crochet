package net.jonbell.crochet.agent;

import java.lang.instrument.Instrumentation;

public final class CrochetAgent {

    private CrochetAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    private static void install(String agentArgs, Instrumentation inst) {
        inst.addTransformer(new TransformerWrapper(), true);
    }
}
