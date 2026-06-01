package edu.neu.ccs.prl.crochet.debug.fixture;

import edu.neu.ccs.prl.crochet.ttd.SocketRepl;
import edu.neu.ccs.prl.crochet.ttd.TimeTravelBody;
import edu.neu.ccs.prl.crochet.ttd.Ttd;

/**
 * Simple buggy fixture for the crochet-debug smoke test.
 *
 * <p>The program computes a running sum of the first N integers. The bug is
 * an off-by-one: the loop starts at 1 but should start at 0 (it misses
 * the zero contribution, which for sums doesn't matter, but for a "maximum
 * value seen" tracker the first element is missed).
 *
 * <p>Launch as:
 * <pre>
 *   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
 *        -javaagent:crochet-agent.jar \
 *        -cp crochet-debug-test.jar \
 *        edu.neu.ccs.prl.crochet.debug.fixture.HelloBuggy [replPort]
 * </pre>
 *
 * <p>The {@code replPort} argument (default 5006) is passed to
 * {@link SocketRepl#onPort}. The program blocks until the REPL client connects,
 * then runs the body.
 */
public class HelloBuggy {

    /** The "state" root that Crochet checkpoints. */
    static final class State {
        int sum = 0;
        int maxSeen = Integer.MIN_VALUE;
        int step = 0;

        @Override
        public String toString() {
            return "State{sum=" + sum + ", maxSeen=" + maxSeen + ", step=" + step + "}";
        }
    }

    /**
     * Body annotated with {@code @TimeTravelBody} so the TTD transformer
     * inserts save-points at each source line.
     */
    @TimeTravelBody
    static void runBody(State s, int n) {
        // Bug: should be i=0 to catch s.maxSeen = 0 on first iteration.
        for (int i = 1; i <= n; i++) {
            s.step = i;
            s.sum += i;
            if (i > s.maxSeen) {  // maxSeen starts at MIN_VALUE, so this fires every step
                s.maxSeen = i;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int replPort = args.length > 0 ? Integer.parseInt(args[0]) : 5006;
        int n = 5;

        State s = new State();
        System.out.println("[HelloBuggy] Starting. replPort=" + replPort + " n=" + n);
        System.out.flush();

        // Bind the REPL port first so the CLI can connect before we start.
        java.net.ServerSocket serverSocket = SocketRepl.bindPort(replPort);
        System.out.println("[HelloBuggy] REPL listening on port " + serverSocket.getLocalPort());
        System.out.flush();

        SocketRepl repl = SocketRepl.acceptFrom(serverSocket);
        System.out.println("[HelloBuggy] REPL client connected");
        System.out.flush();

        try {
            Ttd.sessionWithRepl(s, repl, () -> runBody(s, n));
        } finally {
            repl.close();
        }

        System.out.println("[HelloBuggy] Session ended. Final state: " + s);
        System.out.flush();
    }
}
