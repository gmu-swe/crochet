package net.jonbell.crochet.tests;

/**
 * Grandchild of {@link SkipBean} (depth 2 in the inheritance chain). Neither
 * this class nor its direct parent ({@link SkipBeanSubclass}) carries
 * {@code @CrochetSkip}; only the grandparent does. The transformer must still
 * skip this class.
 */
public class SkipBeanGrandchild extends SkipBeanSubclass {
    public boolean flag;

    public SkipBeanGrandchild(int value, String label, boolean flag) {
        super(value, label);
        this.flag = flag;
    }
}
