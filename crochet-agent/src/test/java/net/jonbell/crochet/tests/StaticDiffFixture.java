package net.jonbell.crochet.tests;

/**
 * Fixture for {@code Crochet.diffStatic()} tests. The transformer emits a
 * static-field helper for this class that mirrors {@code sValue} and
 * {@code sLabel}.
 */
public class StaticDiffFixture {

    public static int    sValue;
    public static String sLabel;

    // final static fields are intentionally excluded (not checkpointed)
    public static final String CONSTANT = "immutable";

    private StaticDiffFixture() {}
}
