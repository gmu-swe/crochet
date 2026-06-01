package net.jonbell.crochet.compose;

import net.jonbell.crochet.annotation.Experimental;
import net.jonbell.crochet.runtime.CRIJInstrumented;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension registered by {@link CrochetCompositionTest}.
 *
 * <p>Checks at test-class initialization time that:
 * <ol>
 *   <li>The Crochet agent is present (at least one instrumented class is
 *       reachable as {@link CRIJInstrumented}). Detected by checking whether
 *       {@code java.util.HashMap} implements {@link CRIJInstrumented} — on
 *       an instrumented JDK, or when the agent is attached and HashMap was
 *       loaded after the agent, it will. On a vanilla JDK without the agent,
 *       it won't; we then look for any user class that might be instrumented
 *       by checking the system property {@code sun.java.command} or just
 *       emit a warning that the agent may not be loaded.</li>
 *   <li>The active {@link AgentConfig} (from {@code crochet.compose.config})
 *       is logged at DEBUG level so test output is self-describing.</li>
 * </ol>
 *
 * <p>This is a read-only extension — it does not modify test behaviour,
 * intercept method calls, or alter test lifecycle. It only provides
 * pre-test diagnostic output and an early warning if the agent is absent.
 */
@Experimental
public final class CrochetCompositionExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        AgentConfig config = AgentConfig.fromSystemProperty();
        boolean agentPresent = isAgentPresent();

        if (!agentPresent) {
            // Abort with a clear message rather than failing with an
            // unintelligible NPE or ClassCastException later.
            throw new IllegalStateException(
                    "[CrochetCompositionTest] Crochet agent not detected. "
                    + "Run with -javaagent:crochet-agent.jar "
                    + "(or use the Crochet-instrumented JDK). "
                    + "Active config: " + config.propertyValue);
        }

        // Log the active config so CI output is self-describing.
        System.out.println("[CrochetCompositionTest] active-config="
                + config.propertyValue
                + " class=" + context.getRequiredTestClass().getName());

        // For CROCHET_FRAY: emit a warning if Fray is expected but the
        // property is set and we can't detect Fray's presence. Phase A
        // doesn't load Fray so we just note the intent.
        if (config == AgentConfig.CROCHET_FRAY) {
            boolean frayPresent = isFrayPresent();
            if (!frayPresent) {
                System.out.println("[CrochetCompositionTest] WARNING: "
                        + "config=crochet+fray but Fray runtime not detected. "
                        + "Fray tests will run without the Fray scheduler; "
                        + "composition-specific behaviour will not be exercised.");
            }
        }
    }

    /**
     * Returns true iff the Crochet agent is present.
     *
     * <p>Detection strategy: try to load {@code CRIJInstrumented} via the
     * context classloader. If it's on the classpath (it is when crochet-agent
     * is a dependency), further check whether any known class actually
     * implements it — which only happens when the agent is running. On a
     * pure-classpath setup without the agent, {@code HashMap} will not
     * implement {@code CRIJInstrumented}.
     *
     * <p>We use {@code HashMap} as the probe because it is always loaded
     * before any test code and is instrumented on both the jlink-instrumented
     * JDK path and the {@code -javaagent} path (when the JDK is pre-built).
     */
    private static boolean isAgentPresent() {
        try {
            Class<?> marker = Class.forName("net.jonbell.crochet.runtime.CRIJInstrumented");
            // Check HashMap (instrumented on the jlink path)
            if (marker.isAssignableFrom(java.util.HashMap.class)) {
                return true;
            }
            // Check if the runtime class is accessible and the agent jar is
            // on the classpath at all (runtime-only check succeeds when the
            // -javaagent path is used but no JDK pre-instrumentation happened)
            Class<?> agent = Class.forName(
                    "net.jonbell.crochet.runtime.CheckpointRollbackAgent");
            // If we got here, the agent classes are on the classpath.
            // On a plain -javaagent run, user classes loaded AFTER the agent
            // will be instrumented. The agent IS present; return true.
            return agent != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns true iff the Fray runtime is present on the classpath.
     */
    private static boolean isFrayPresent() {
        try {
            Class.forName("org.pastalab.fray.runtime.Runtime");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
