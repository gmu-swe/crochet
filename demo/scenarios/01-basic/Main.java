import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

public class Main {
    public static void main(String[] args) {
        Counter c = new Counter(1, "original");
        System.out.println("before checkpoint : value=" + c.value + " label=" + c.label);

        int v = CheckpointRollbackAgent.checkpoint(c);
        System.out.println("checkpoint version: " + v);

        c.value = 42;
        c.label = "mutated";
        System.out.println("after mutation    : value=" + c.value + " label=" + c.label);

        CheckpointRollbackAgent.rollback(c, v);
        System.out.println("after rollback    : value=" + c.value + " label=" + c.label);

        if (c.value == 1 && "original".equals(c.label)) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL");
            System.exit(1);
        }
    }
}
