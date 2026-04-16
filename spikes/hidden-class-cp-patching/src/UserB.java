// Second user class — demonstrates that one template can specialize into two
// distinct user-class hierarchies without sharing identity.
import java.lang.invoke.MethodHandles;

public class UserB {
    public long total;

    public int doWork() {
        total += 2;
        return (int) total;
    }

    public String tag() { return "UserB"; }

    public static MethodHandles.Lookup $$crijLookup() {
        return MethodHandles.lookup();
    }
}
