import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

public class Main {
    public static void main(String[] args) {
        int[] ints = {1, 2, 3, 4, 5};
        String[] strs = {"a", "b", "c"};
        long[] longs = {10L, 20L, 30L};

        int vi = CheckpointRollbackAgent.checkpointArray(ints);
        int vs = CheckpointRollbackAgent.checkpointArray(strs);
        int vl = CheckpointRollbackAgent.checkpointArray(longs);

        ints[0] = 99;
        ints[4] = 100;
        strs[1] = "MUT";
        longs[2] = -1L;

        CheckpointRollbackAgent.rollbackArray(ints, vi);
        CheckpointRollbackAgent.rollbackArray(strs, vs);
        CheckpointRollbackAgent.rollbackArray(longs, vl);

        boolean ok = ints[0] == 1 && ints[4] == 5
                && "b".equals(strs[1])
                && longs[2] == 30L;
        if (ok) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: ints=[" + ints[0] + "," + ints[4] + "]"
                    + " strs[1]=" + strs[1] + " longs[2]=" + longs[2]);
            System.exit(1);
        }
    }
}
