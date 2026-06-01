import net.jonbell.crochet.annotation.CrochetSkip;

/**
 * A helper class annotated with {@code @CrochetSkip} to opt out of Crochet's
 * bytecode transformation. Instances of this class are NOT {@code CRIJInstrumented};
 * their field mutations survive Crochet rollbacks.
 *
 * <p>Used by scenario 25 to demonstrate that {@code @CrochetSkip} is orthogonal
 * to TTD instrumentation: the containing method's {@code @TimeTravelBody}
 * annotation still generates CPS save points around calls into this class.
 */
@CrochetSkip
public class SkippedHelper {
    public int counter;
    public String name;

    public SkippedHelper(String name) {
        this.counter = 0;
        this.name = name;
    }

    /** Increment the counter — this mutation is NOT rolled back by Crochet. */
    public void increment() {
        counter++;
    }

    /** Return the current counter value (avoids GETFIELD from instrumented callers). */
    public int getCounter() {
        return counter;
    }

    @Override
    public String toString() {
        return "SkippedHelper{counter=" + counter + ", name=" + name + "}";
    }
}
