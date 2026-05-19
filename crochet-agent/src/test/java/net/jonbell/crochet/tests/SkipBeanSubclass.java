package net.jonbell.crochet.tests;

/**
 * Direct subclass of {@link SkipBean}. Carries no annotation itself; the
 * transformer must still skip it because the superclass is annotated with
 * {@code @CrochetSkip} (inheritance depth 1).
 */
public class SkipBeanSubclass extends SkipBean {
    public String label;

    public SkipBeanSubclass(int value, String label) {
        super(value);
        this.label = label;
    }
}
