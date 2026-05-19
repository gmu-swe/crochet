/**
 * Scenario 24: State object for try/catch back-step demo.
 */
public class TryCatchState {
    public int phase;
    public String lastStep;
    public boolean caughtException;

    public TryCatchState() {
        this.phase = 0;
        this.lastStep = "init";
        this.caughtException = false;
    }

    @Override
    public String toString() {
        return "TryCatchState{phase=" + phase + ", lastStep=" + lastStep
                + ", caughtException=" + caughtException + "}";
    }
}
