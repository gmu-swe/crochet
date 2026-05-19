/**
 * Scenario 22: Heap state object tracked across cross-method back-steps.
 * Each field records the phase the session has reached.
 */
public class State {
    public int phase;
    public String tag;

    public State(int phase, String tag) {
        this.phase = phase;
        this.tag = tag;
    }

    @Override
    public String toString() {
        return "State{phase=" + phase + ", tag=" + tag + "}";
    }
}
