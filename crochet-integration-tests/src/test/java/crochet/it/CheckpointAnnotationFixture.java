package crochet.it;

import net.jonbell.crochet.annotation.CrochetCheckpoint;
import net.jonbell.crochet.annotation.CrochetRoot;

/**
 * Fixture class for {@link CheckpointAnnotationIT}.
 *
 * <p>This class is deliberately placed in the {@code crochet.it} package —
 * NOT under {@code net.jonbell.crochet.*} — so that
 * {@link net.jonbell.crochet.transform.FieldAccessWrapper#shouldWrap} does
 * not exclude it. If it were placed under the Crochet namespace, field-access
 * wrapping would be suppressed and the lazy snapshot could never materialise,
 * making rollback a no-op.
 */
public class CheckpointAnnotationFixture {

    public int value;

    /**
     * Mutates {@code root.value} — after rollback the mutation must be undone.
     */
    @CrochetCheckpoint
    public void mutate(@CrochetRoot CheckpointAnnotationFixture root) {
        root.value += 10;
    }

    /**
     * Mutates and returns a value — the return value must survive the rollback.
     */
    @CrochetCheckpoint
    public int addAndReturn(@CrochetRoot CheckpointAnnotationFixture root) {
        root.value += 10;
        return root.value;
    }

    /**
     * Throws deliberately — rollback must fire even when an exception propagates.
     */
    @CrochetCheckpoint
    public void throwOnPurpose(@CrochetRoot CheckpointAnnotationFixture root) {
        root.value += 10;
        throw new RuntimeException("deliberate");
    }

    /**
     * Has an inner try/catch — the wrapping must not break it.
     */
    @CrochetCheckpoint
    public void catchInner(@CrochetRoot CheckpointAnnotationFixture root) {
        try {
            root.value += 10;
            int x = 1 / 0; // provoke ArithmeticException
        } catch (ArithmeticException e) {
            root.value += 5; // extra mutation inside catch
        }
    }
}
