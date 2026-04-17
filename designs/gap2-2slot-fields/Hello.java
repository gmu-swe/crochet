import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * PoC driver that exercises 2-slot fields (long, double) alongside 1-slot
 * primitives and references. Requires the Gap-2 fix to be in place in
 * crochet-agent (copy {@code FieldAccessWrapper.java} from this design dir
 * into {@code crochet-agent/src/main/java/net/jonbell/crochet/transform/}).
 *
 * Expected output (order is stable because the driver is single-threaded):
 * <pre>
 * before checkpoint : Scalar{i=1, l=100, d=3.14, o=origin-obj, s=origin}
 * checkpoint version: 1
 * after mutation    : Scalar{i=42, l=9999999999, d=2.71828, o=mutated-obj, s=mutated}
 * after rollback    : Scalar{i=1, l=100, d=3.14, o=origin-obj, s=origin}
 * ROLLBACK OK
 * </pre>
 */
public class Hello {
    public static void main(String[] args) {
        Scalar s = new Scalar(1, 100L, 3.14, "origin-obj", "origin");
        System.out.println("before checkpoint : " + s);

        int v = CheckpointRollbackAgent.checkpoint(s);
        System.out.println("checkpoint version: " + v);

        // Mutate every field — each PUTFIELD goes through the wrapper.
        s.iField = 42;
        s.lField = 9_999_999_999L;   // 2-slot PUTFIELD: exercises the J path
        s.dField = 2.71828;          // 2-slot PUTFIELD: exercises the D path
        s.oField = "mutated-obj";
        s.sField = "mutated";
        System.out.println("after mutation    : " + s);

        CheckpointRollbackAgent.rollback(s, v);
        System.out.println("after rollback    : " + s);

        boolean ok = s.iField == 1
                && s.lField == 100L
                && s.dField == 3.14
                && "origin-obj".equals(s.oField)
                && "origin".equals(s.sField);
        if (ok) {
            System.out.println("ROLLBACK OK");
        } else {
            System.out.println("ROLLBACK FAIL");
            System.exit(1);
        }
    }
}
