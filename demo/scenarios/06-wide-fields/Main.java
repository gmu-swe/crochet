import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * Exercises 2-slot field types (long, double) alongside 1-slot primitives and
 * references. Gap 2 fix required in crochet-agent.
 */
public class Main {
    public static void main(String[] args) {
        Scalar s = new Scalar(1, 100L, 3.14, "origin-obj", "origin");
        System.out.println("before checkpoint : " + s);

        int v = CheckpointRollbackAgent.checkpoint(s);
        System.out.println("checkpoint version: " + v);

        s.iField = 42;
        s.lField = 9_999_999_999L;
        s.dField = 2.71828;
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
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL");
            System.exit(1);
        }
    }
}
