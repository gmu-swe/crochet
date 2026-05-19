package net.jonbell.crochet.tests;

import net.jonbell.crochet.annotation.CrochetSkip;

/**
 * Fixture for the @CrochetSkip opt-out path. Annotated directly; the
 * transformer should return null (no instrumentation) when it encounters this
 * class, verified by checking that no {@code $$crochet*} methods appear in the
 * transformed bytes.
 */
@CrochetSkip
public class SkipBean {
    public int value;

    public SkipBean(int value) {
        this.value = value;
    }
}
