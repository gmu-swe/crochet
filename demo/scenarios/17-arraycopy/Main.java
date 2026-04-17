import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * System.arraycopy is a native bulk write that bypasses the per-element
 * xASTORE pre-hook installed by ArrayAccessWrapper. Without the
 * ArrayCopyInterceptor visitor, a System.arraycopy into a registered
 * array would not snapshot — rollback would produce the mutated state.
 *
 * This scenario verifies that the interceptor redirects System.arraycopy
 * to CheckpointRollbackAgent.interceptedArraycopy, which calls
 * ArrayRegistry.beforeStore(dst) before doing the copy.
 */
public class Main {
    public static void main(String[] args) {
        Container c = new Container(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        int v = CheckpointRollbackAgent.checkpoint(c);

        int[] src = {99, 98, 97, 96, 95};
        System.arraycopy(src, 0, c.data, 0, 5);
        if (c.data[0] != 99 || c.data[4] != 95 || c.data[5] != 6) {
            System.out.println("SCENARIO FAIL mid-mutation: " + java.util.Arrays.toString(c.data));
            System.exit(1);
        }

        CheckpointRollbackAgent.rollback(c, v);

        // force fastAccess via a read (so the array field's registry is walked)
        int[] restored = c.data;
        boolean ok = restored[0] == 1 && restored[4] == 5 && restored[9] == 10;
        if (ok) {
            System.out.println("SCENARIO OK after=" + java.util.Arrays.toString(restored));
        } else {
            System.out.println("SCENARIO FAIL after=" + java.util.Arrays.toString(restored));
            System.exit(1);
        }
    }
}
