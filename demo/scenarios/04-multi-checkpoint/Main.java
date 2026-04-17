import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * Flat-nested: per the paper, a second checkpoint discards the first.
 * Rollback then restores to the most recent checkpoint only.
 */
public class Main {
    public static void main(String[] args) {
        Widget w = new Widget(1);
        System.out.println("initial: w.counter=" + w.counter);

        int v1 = CheckpointRollbackAgent.checkpoint(w);
        System.out.println("v1=" + v1);
        w.counter = 10;

        int v2 = CheckpointRollbackAgent.checkpoint(w);
        System.out.println("v2=" + v2);
        w.counter = 100;

        CheckpointRollbackAgent.rollback(w, v2);
        System.out.println("after rollback: w.counter=" + w.counter);

        // Rollback should restore to v2's state (counter=10), not v1's (counter=1).
        if (w.counter == 10) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: expected 10, got " + w.counter);
            System.exit(1);
        }
    }
}
