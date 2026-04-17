import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * checkpoint → mutate → rollback → mutate → checkpoint → mutate → rollback.
 * Each checkpoint/rollback pair must independently preserve state.
 */
public class Main {
    public static void main(String[] args) {
        Cell c = new Cell();
        c.value = 1;

        int v1 = CheckpointRollbackAgent.checkpoint(c);
        c.value = 2;
        CheckpointRollbackAgent.rollback(c, v1);
        if (c.value != 1) {
            System.out.println("SCENARIO FAIL: after first rollback expected 1, got " + c.value);
            System.exit(1);
        }

        c.value = 3;
        int v2 = CheckpointRollbackAgent.checkpoint(c);
        c.value = 4;
        CheckpointRollbackAgent.rollback(c, v2);
        if (c.value != 3) {
            System.out.println("SCENARIO FAIL: after second rollback expected 3, got " + c.value);
            System.exit(1);
        }

        System.out.println("SCENARIO OK");
    }
}
