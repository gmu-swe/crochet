/**
 * Scenario 25: State tracked by Crochet (not @CrochetSkip).
 * Mutations to this class are rolled back by Crochet checkpoints.
 */
public class TrackedState {
    public int phase;
    public String tag;

    public TrackedState(int phase, String tag) {
        this.phase = phase;
        this.tag = tag;
    }

    @Override
    public String toString() {
        return "TrackedState{phase=" + phase + ", tag=" + tag + "}";
    }
}
