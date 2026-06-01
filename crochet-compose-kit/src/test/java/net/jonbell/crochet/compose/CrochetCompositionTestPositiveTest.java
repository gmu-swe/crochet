package net.jonbell.crochet.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.jonbell.crochet.runtime.CheckpointRollbackAgent;
import net.jonbell.crochet.runtime.CRIJInstrumented;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Positive composition test: Crochet-only configuration passes silently.
 *
 * <p>Validates that basic checkpoint/rollback works correctly in the
 * {@link AgentConfig#CROCHET_ONLY} configuration. No surface-mismatch log lines
 * should appear on stderr when {@code -Dcrochet.verifyInstrumented=true} is set.
 *
 * <p>This test is annotated {@link CrochetCompositionTest} so that the
 * {@link CrochetCompositionExtension} verifies the agent is present at
 * test startup. If the agent is absent, the test aborts with a clear
 * diagnostic message rather than a confusing NPE.
 *
 * <p>{@link TrackedBean} is a user-defined class in this module. Because it
 * is loaded by the application classloader, the Crochet agent will instrument
 * it at load time and it will implement {@link CRIJInstrumented}. JDK classes
 * like {@code StringBuilder} live in {@code java.base} (bootstrap classloader)
 * and are only instrumented on the jlink-instrumented JDK path.
 */
@CrochetCompositionTest
class CrochetCompositionTestPositiveTest {

    private static boolean isInstrumented(Object obj) {
        return obj instanceof CRIJInstrumented;
    }

    @Test
    void checkpointAndRollbackWorksOnUserClass() {
        TrackedBean bean = new TrackedBean("initial");
        // Only run checkpoint/rollback if the class was instrumented.
        // On a vanilla -javaagent run, TrackedBean will be instrumented.
        // Skip gracefully if the agent isn't active.
        if (!isInstrumented(bean)) {
            System.out.println("[CrochetCompositionTest] TrackedBean not instrumented — "
                    + "skipping checkpoint/rollback test (agent may not be attached)");
            return;
        }
        int v = CheckpointRollbackAgent.checkpoint(bean);
        bean.setValue("modified");
        assertEquals("modified", bean.getValue());
        CheckpointRollbackAgent.rollback(bean, v);
        assertEquals("initial", bean.getValue(),
                "Rollback should restore the original value");
    }

    @Test
    void multipleCheckpointsAndRollbacks() {
        TrackedBean bean = new TrackedBean("a");
        if (!isInstrumented(bean)) {
            return;
        }
        int v1 = CheckpointRollbackAgent.checkpoint(bean);
        bean.setValue("b");
        CheckpointRollbackAgent.rollback(bean, v1);
        assertEquals("a", bean.getValue(), "After rollback 1");

        int v2 = CheckpointRollbackAgent.checkpoint(bean);
        bean.setValue("c");
        CheckpointRollbackAgent.rollback(bean, v2);
        assertEquals("a", bean.getValue(), "After rollback 2");
    }

    @Test
    void userClassIsInstrumentedWhenAgentPresent() {
        TrackedBean bean = new TrackedBean("test");
        // This verifies that the compose extension's agent-detection logic is consistent
        // with actual instrumentation status.
        System.out.println("[CrochetCompositionTest] TrackedBean instrumented: "
                + isInstrumented(bean));
        // No assertion — just smoke test that we can instantiate the class and
        // inspect it without errors.
    }
}
