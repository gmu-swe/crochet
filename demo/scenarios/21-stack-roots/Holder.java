/**
 * Plain mutable holder. Used in 21-stack-roots to hold a reference that
 * lives ONLY in a stack-frame local variable — not stored in any field
 * or static so the regular {@code checkpointAll} heap-walk won't see it.
 */
public class Holder {
    public int value;
    public String label;

    public Holder(int v, String l) {
        this.value = v;
        this.label = l;
    }
}
