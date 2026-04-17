import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

public class Main {
    public static void main(String[] args) {
        Store.counter = 5;
        Store.label = "before";
        Store.big = 1_000_000_000_000L;

        int v = CheckpointRollbackAgent.checkpointStatics(Store.class);

        Store.counter = 42;
        Store.label = "mutated";
        Store.big = -7L;

        CheckpointRollbackAgent.rollbackStatics(Store.class, v);

        if (Store.counter == 5 && "before".equals(Store.label) && Store.big == 1_000_000_000_000L) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: counter=" + Store.counter
                    + " label=" + Store.label + " big=" + Store.big);
            System.exit(1);
        }
    }
}
