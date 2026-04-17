import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

public class Main {
    public static void main(String[] args) {
        Config.counter = 5;
        final String originalLabel = "before";
        Config.label = originalLabel;
        Config.magic = 1_000_000_000_000L;

        int v = CheckpointRollbackAgent.checkpointClass(Config.class);

        Config.counter = 42;
        Config.label = "mutated";
        Config.magic = -7L;

        CheckpointRollbackAgent.rollbackClass(Config.class, v);

        boolean ok = Config.counter == 5
                && Config.label == originalLabel
                && Config.magic == 1_000_000_000_000L;
        if (ok) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: counter=" + Config.counter
                    + " label=" + Config.label + " magic=" + Config.magic);
            System.exit(1);
        }
    }
}
