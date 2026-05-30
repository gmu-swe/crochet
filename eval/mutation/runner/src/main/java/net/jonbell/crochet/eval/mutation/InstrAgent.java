package net.jonbell.crochet.eval.mutation;

import java.lang.instrument.Instrumentation;

/**
 * Tiny -javaagent that captures the {@link Instrumentation} handle for the
 * mutation harness so we can call {@code redefineClasses} to swap mutant
 * bytecode into an already-loaded target class.
 *
 * <p>Loaded ALONGSIDE the Crochet agent. Crochet owns the heap-snapshot
 * surface; this agent owns the class-redefinition surface. They do not
 * interact because Crochet's transform pipeline is invoked at class load,
 * not at redefinition (the JVM does not re-trigger transformers on
 * {@code redefineClasses} for already-instrumented classes — we keep
 * the target class on the JDK skip-list of user-package transforms by
 * pre-defining it through the system classloader before instrumentation
 * kicks in).</p>
 */
public final class InstrAgent {
    private static volatile Instrumentation INSTR;

    public static void premain(String args, Instrumentation inst) {
        INSTR = inst;
    }

    public static void agentmain(String args, Instrumentation inst) {
        INSTR = inst;
    }

    public static Instrumentation get() {
        if (INSTR == null) {
            throw new IllegalStateException(
                "InstrAgent not loaded: pass -javaagent:mutation-runner.jar in addition to crochet-agent.jar");
        }
        return INSTR;
    }
}
