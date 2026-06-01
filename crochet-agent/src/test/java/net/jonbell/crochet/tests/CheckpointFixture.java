package net.jonbell.crochet.tests;

import net.jonbell.crochet.annotation.CrochetCheckpoint;
import net.jonbell.crochet.annotation.CrochetRoot;

/**
 * Fixture class for {@link net.jonbell.crochet.transform.CheckpointWrapperTest}.
 *
 * <p>Each method exercises a different return-type shape so the test can
 * verify that {@code CheckpointWrapper} generates correct bytecode for all
 * flavours.
 */
public class CheckpointFixture {

    public int value;

    // -----------------------------------------------------------------
    // Positive cases — all should be wrapped
    // -----------------------------------------------------------------

    @CrochetCheckpoint
    public void doVoid(@CrochetRoot Object root) {
        // nothing
    }

    @CrochetCheckpoint
    public int doInt(@CrochetRoot Object root) {
        return 42;
    }

    @CrochetCheckpoint
    public long doLong(@CrochetRoot Object root) {
        return 42L;
    }

    @CrochetCheckpoint
    public double doDouble(@CrochetRoot Object root) {
        return 3.14;
    }

    @CrochetCheckpoint
    public float doFloat(@CrochetRoot Object root) {
        return 1.0f;
    }

    @CrochetCheckpoint
    public Object doRef(@CrochetRoot Object root) {
        return root;
    }

    @CrochetCheckpoint
    public boolean doBoolean(@CrochetRoot Object root) {
        return true;
    }

    @CrochetCheckpoint
    public void doThrow(@CrochetRoot Object root) {
        throw new RuntimeException("test");
    }

    /** Has an inner try/catch — the wrapper should still surround everything. */
    @CrochetCheckpoint
    public void doWithInnerTryCatch(@CrochetRoot Object root) {
        try {
            int x = 1 / 0;
        } catch (ArithmeticException e) {
            // ignored
        }
    }

    /** @CrochetRoot is the second parameter. */
    @CrochetCheckpoint
    public void doMultiParam(int extra, @CrochetRoot Object root) {
        // nothing
    }

    // -----------------------------------------------------------------
    // Negative cases — should NOT be wrapped
    // -----------------------------------------------------------------

    /** No @CrochetRoot — must NOT be wrapped. */
    @CrochetCheckpoint
    public void noRoot(Object notRoot) {
        // nothing
    }

    /** Static — must NOT be wrapped (even with @CrochetCheckpoint + @CrochetRoot). */
    @CrochetCheckpoint
    public static void staticMethod(@CrochetRoot Object root) {
        // nothing
    }
}
