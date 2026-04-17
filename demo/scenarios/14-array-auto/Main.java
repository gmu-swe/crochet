import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

public class Main {
    public static void main(String[] args) {
        int[] originalData = {1, 2, 3, 4, 5};
        String[] originalNames = {"alpha", "beta", "gamma"};
        Container container = new Container(originalData, originalNames);
        int[] dataRef = container.data;

        int v = CheckpointRollbackAgent.checkpoint(container);

        container.data[0] = 99;
        container.data[4] = 100;
        container.data[2] = -42;
        container.names[1] = "MUT";

        CheckpointRollbackAgent.rollback(container, v);

        boolean identityOk = container.data == dataRef;
        boolean intsOk = container.data[0] == 1
                && container.data[1] == 2
                && container.data[2] == 3
                && container.data[3] == 4
                && container.data[4] == 5;
        boolean stringsOk = "alpha".equals(container.names[0])
                && "beta".equals(container.names[1])
                && "gamma".equals(container.names[2]);

        if (identityOk && intsOk && stringsOk) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: identityOk=" + identityOk
                    + " intsOk=" + intsOk + " stringsOk=" + stringsOk);
            System.exit(1);
        }
    }
}
