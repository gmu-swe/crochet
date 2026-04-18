import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * Demonstrates the legacy CROCHET {@code ClassCoverageProbe} /
 * {@code RootCollector} gap closure: {@code ConfigHolder} is initialised
 * via {@code Class.forName(..., true, ...)} — NOT via the usual
 * GETSTATIC/PUTSTATIC path that would drive {@code ClassMeta.of} and
 * populate {@code TOUCHED_CLASSES}. Its only entry into
 * {@code checkpointAll}'s root set is via the agent's
 * {@code registerInitializedClass} emit at the top of the synthesised
 * {@code <clinit>}, feeding
 * {@code CheckpointRollbackAgent.INITIALIZED_CLASSES}.
 *
 * <p>The test: force-init ConfigHolder, checkpoint, mutate its static
 * {@code CONFIG}, rollback — assert CONFIG is "initial" again.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        // Force <clinit> without any GETSTATIC/PUTSTATIC on ConfigHolder from
        // this class — Class.forName(init=true) is how reflection-heavy
        // frameworks (Spring, Jackson, etc.) typically force class init.
        Class<?> c = Class.forName("ConfigHolder", /*init=*/ true, Main.class.getClassLoader());

        // At this point, ConfigHolder's synthesised <clinit> has run and
        // registered the class with CheckpointRollbackAgent.INITIALIZED_CLASSES.
        // ClassMeta.of has NEVER been called on this class.

        // Read the initial CONFIG via reflection so we don't trip
        // ClassMeta.of via a direct GETSTATIC. java.lang.reflect.Field.get
        // does not route through our instrumented GETSTATIC pre-hook.
        java.lang.reflect.Field f = c.getDeclaredField("CONFIG");
        f.setAccessible(true);
        String before = (String) f.get(null);
        System.out.println("before checkpointAll: CONFIG=" + before);

        int v = CheckpointRollbackAgent.checkpointAll();
        System.out.println("checkpointAll version: " + v);

        // Mutate CONFIG — this first write routes through the instrumented
        // path (user code is compiled from source so the PUTSTATIC pre-hook
        // DOES fire), but the snapshot was already captured by checkpointAll
        // above via the INITIALIZED_CLASSES path, so rollback restores
        // "initial".
        ConfigHolder.CONFIG = "mutated";
        System.out.println("after mutation      : CONFIG=" + ConfigHolder.CONFIG);

        CheckpointRollbackAgent.rollbackAll(v);

        String after = (String) f.get(null);
        System.out.println("after rollbackAll   : CONFIG=" + after);

        if ("initial".equals(after)) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL");
            System.exit(1);
        }
    }
}
