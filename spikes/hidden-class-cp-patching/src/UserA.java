// A "user" class — the kind CROCHET specializes against. Has state, so the
// specialized subclass can intercept its behavior. Injected accessor provides
// the Lookup used for defineHiddenClass so the hidden class lands in this
// class's package and shares its module/package privileges.
import java.lang.invoke.MethodHandles;

public class UserA {
    public int counter;

    public int doWork() {
        counter++;
        return counter;
    }

    public String tag() { return "UserA"; }

    // CROCHET's instrumenter would add this to every user class.
    public static MethodHandles.Lookup $$crijLookup() {
        return MethodHandles.lookup();
    }
}
