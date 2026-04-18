package net.jonbell.crochet.tests;

import net.jonbell.crochet.annotation.CrochetEager;

/**
 * Fixture for the eager checkpoint path. Mutable public fields so tests can
 * read and write without reflection. The {@code @CrochetEager} annotation
 * makes {@link net.jonbell.crochet.transform.FieldAdder} emit the
 * shallow-copy body instead of the Fast-proxy + sentinel body.
 */
@CrochetEager
public class EagerBean {
    public int x;
    public String label;

    public EagerBean(int x, String label) {
        this.x = x;
        this.label = label;
    }
}
