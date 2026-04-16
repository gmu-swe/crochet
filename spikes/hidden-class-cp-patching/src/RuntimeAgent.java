// Stand-in for the CROCHET runtime. The specialized classes' bodies will call
// into the methods here, much like the real CheckpointRollbackAgent's
// fastOnCheckpoint/fastOnRollback/... family.
import java.util.concurrent.atomic.AtomicInteger;

public class RuntimeAgent {
    public static final AtomicInteger checkpointHits = new AtomicInteger();
    public static final AtomicInteger rollbackHits   = new AtomicInteger();

    // Method names used in the real Crochet: fastOnCheckpoint, slowOnRollback, ...
    // Here we only ship two, as stand-ins. The specialized class has its
    // constant-pool patched so its invokestatic resolves to one of these.
    public static void checkpointCalled(Object obj, Class<?> which) {
        checkpointHits.incrementAndGet();
    }

    public static void rollbackCalled(Object obj, Class<?> which) {
        rollbackHits.incrementAndGet();
    }
}
