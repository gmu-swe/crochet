import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * Exercises the paper's top-level {@code checkpointAll} / {@code rollbackAll}
 * API. {@code checkpointAll} walks every class the runtime has touched
 * (snapping their non-final statics) plus every live thread plus the system
 * classloader. {@code rollbackAll(v)} restores all of them in one call.
 *
 * <p>The scenario stages three user classes with mutable static state and
 * primes the runtime (via {@link CheckpointRollbackAgent#checkpointClass})
 * so each lands in {@code TOUCHED_CLASSES}. One {@code checkpointAll} /
 * {@code rollbackAll} pair then snaps and restores all three classes'
 * statics in one shot.
 *
 * <p>Per-object user state (a.value on an arbitrary heap object) is NOT
 * what {@code checkpointAll} captures — the paper's "live world" API snaps
 * static roots, from which instrumented objects can be reached via user
 * code but are not themselves individual roots of this API. Scenarios that
 * want per-object rollback use the {@code checkpoint(obj)} /
 * {@code rollback(obj, v)} API shown in {@code 01-basic}.
 */
public class Main {
    public static void main(String[] args) {
        HolderA.counter = 0;
        HolderB.counter = 0;
        HolderC.counter = 0;
        HolderA.label = "a-initial";
        HolderB.label = "b-initial";
        HolderC.label = "c-initial";

        // Prime ClassMeta for each user class. One priming
        // checkpoint/rollback pair per class warms the SF-helper machinery
        // and registers the class in TOUCHED_CLASSES. Subsequent
        // {@code checkpointAll} will discover them.
        int pv = CheckpointRollbackAgent.checkpointClass(HolderA.class);
        CheckpointRollbackAgent.rollbackClass(HolderA.class, pv);
        pv = CheckpointRollbackAgent.checkpointClass(HolderB.class);
        CheckpointRollbackAgent.rollbackClass(HolderB.class, pv);
        pv = CheckpointRollbackAgent.checkpointClass(HolderC.class);
        CheckpointRollbackAgent.rollbackClass(HolderC.class, pv);

        System.out.println("before checkpointAll:"
                + " A{" + HolderA.counter + "," + HolderA.label + "}"
                + " B{" + HolderB.counter + "," + HolderB.label + "}"
                + " C{" + HolderC.counter + "," + HolderC.label + "}");

        int v = CheckpointRollbackAgent.checkpointAll();
        System.out.println("checkpointAll version: " + v);

        HolderA.counter = 100;
        HolderA.label = "a-mutated";
        HolderB.counter = 200;
        HolderB.label = "b-mutated";
        HolderC.counter = 300;
        HolderC.label = "c-mutated";

        System.out.println("after mutation      :"
                + " A{" + HolderA.counter + "," + HolderA.label + "}"
                + " B{" + HolderB.counter + "," + HolderB.label + "}"
                + " C{" + HolderC.counter + "," + HolderC.label + "}");

        CheckpointRollbackAgent.rollbackAll(v);

        System.out.println("after rollbackAll   :"
                + " A{" + HolderA.counter + "," + HolderA.label + "}"
                + " B{" + HolderB.counter + "," + HolderB.label + "}"
                + " C{" + HolderC.counter + "," + HolderC.label + "}");

        boolean ok = HolderA.counter == 0 && "a-initial".equals(HolderA.label)
                && HolderB.counter == 0 && "b-initial".equals(HolderB.label)
                && HolderC.counter == 0 && "c-initial".equals(HolderC.label);
        if (ok) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL");
            System.exit(1);
        }
    }
}
