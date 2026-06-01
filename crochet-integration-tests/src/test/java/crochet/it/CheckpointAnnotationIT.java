package crochet.it;

import net.jonbell.crochet.runtime.CRIJInstrumented;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for {@code @CrochetCheckpoint} annotation
 * processing by the Crochet transformer.
 *
 * <p>These tests run under the instrumented JDK (via maven-failsafe-plugin)
 * with {@code -javaagent:crochet-agent.jar}.  The fixture class
 * {@link CheckpointAnnotationFixture} is in the {@code crochet.it} package so
 * that field-access wrapping fires correctly.
 */
class CheckpointAnnotationIT {

    /**
     * A void annotated method mutates root; after the method returns the
     * mutation must be rolled back to the state at method entry.
     */
    @Test
    void voidMethodRollsBackOnExit() {
        CheckpointAnnotationFixture f = new CheckpointAnnotationFixture();
        f.value = 5;
        assumeInstrumented(f);

        f.mutate(f);

        assertEquals(5, f.value,
                "value should be restored to 5 after rollback; was: " + f.value);
    }

    /**
     * A non-void annotated method must return the value it computed (not the
     * restored snapshot value), even though rollback fires before the method
     * exits.
     */
    @Test
    void returnValuePreservedThroughRollback() {
        CheckpointAnnotationFixture f = new CheckpointAnnotationFixture();
        f.value = 5;
        assumeInstrumented(f);

        int result = f.addAndReturn(f);

        // The returned value was computed BEFORE rollback, so it must be 15.
        assertEquals(15, result, "return value should be the post-mutation value (15)");
        // But the field must be rolled back to 5.
        assertEquals(5, f.value,
                "value field should be restored to 5 after rollback; was: " + f.value);
    }

    /**
     * When the body throws, the exception handler must roll back the mutation
     * and re-throw.
     */
    @Test
    void exceptionPropagatesAfterRollback() {
        CheckpointAnnotationFixture f = new CheckpointAnnotationFixture();
        f.value = 5;
        assumeInstrumented(f);

        assertThrows(RuntimeException.class, () -> f.throwOnPurpose(f));

        assertEquals(5, f.value,
                "value should be restored after exception path rollback; was: " + f.value);
    }

    /**
     * A method with an inner try/catch must still have the outer wrapper fire,
     * rolling back all mutations including those inside the inner catch block.
     */
    @Test
    void innerTryCatchPreservedByWrap() {
        CheckpointAnnotationFixture f = new CheckpointAnnotationFixture();
        f.value = 5;
        assumeInstrumented(f);

        f.catchInner(f);

        assertEquals(5, f.value,
                "value should be restored even when inner catch mutated it; was: " + f.value);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Skips the test with a clear message if the fixture is not instrumented.
     * This protects against running integration tests against a plain JDK where
     * rollback would be a no-op.
     */
    private static void assumeInstrumented(Object obj) {
        assertTrue(obj instanceof CRIJInstrumented,
                "Fixture is not instrumented — run under the instrumented JDK "
                        + "with -javaagent:crochet-agent.jar");
    }
}
