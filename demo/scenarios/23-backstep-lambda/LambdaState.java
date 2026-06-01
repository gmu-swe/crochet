import java.util.ArrayList;
import java.util.List;

/**
 * Scenario 23: State object modified both in the outer method and
 * via a lambda passed to forEach.
 */
public class LambdaState {
    public int phase;
    public final List<String> log;

    public LambdaState() {
        this.phase = 0;
        this.log = new ArrayList<>();
    }

    @Override
    public String toString() {
        return "LambdaState{phase=" + phase + ", log=" + log + "}";
    }
}
