package net.jonbell.crochet.tests;

/**
 * Fixture used to opt in via {@code -Dcrochet.eagerClasses=...} (no
 * annotation). Mirrors {@link EagerBean}'s shape.
 */
public class PlainBean {
    public int x;
    public String label;

    public PlainBean(int x, String label) {
        this.x = x;
        this.label = label;
    }
}
