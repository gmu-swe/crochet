package edu.neu.ccs.prl.crochet.debug;

import net.jonbell.crochet.annotation.Experimental;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unified JDI/JDWP + Crochet TTD debugging CLI.
 *
 * <h2>Launch modes</h2>
 *
 * <h3>Attach mode (default)</h3>
 * <pre>
 *   java --add-modules jdk.jdi -jar crochet-debug.jar \
 *       --attach \
 *       --jdwp-port 5005 \
 *       [--repl-port 5006]
 * </pre>
 * Connects to an already-running JVM. The target must have been started with
 * {@code -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005}.
 *
 * <h3>Launch mode</h3>
 * <pre>
 *   java --add-modules jdk.jdi -jar crochet-debug.jar \
 *       --jdwp-port 5005 \
 *       [--repl-port 5006] \
 *       --target &lt;jar-or-classpath&gt; \
 *       [--jvm-args "&lt;space-separated extra JVM args&gt;"]
 * </pre>
 * Spawns the target JVM with JDWP and the Crochet agent enabled, then
 * connects to both JDWP and the Crochet socket REPL.
 *
 * <h2>Input / output</h2>
 * Reads one command per line from stdin. Writes one JSON line per response to
 * stdout. Blank lines and comments ({@code #...}) are ignored.
 *
 * <p>See {@link UnifiedCommandRouter} for the full command catalog and JSON
 * response shapes.
 *
 * <h2>JDI module note</h2>
 * JDI lives in {@code jdk.jdi}, which is not in the default module graph.
 * The CLI jar must be launched with {@code --add-modules jdk.jdi} (the shade
 * plugin does not embed JDI classes — they ship with the JDK).
 */
@Experimental
public final class CrochetDebugCli {

    /** Default JDWP listen port. */
    public static final int DEFAULT_JDWP_PORT = 5005;

    /** Default Crochet socket REPL port. */
    public static final int DEFAULT_REPL_PORT = 5006;

    /** Timeout for connecting to JDWP / REPL (ms). */
    private static final long CONNECT_TIMEOUT_MS = 15_000;

    public static void main(String[] args) throws Exception {
        CliArgs a = CliArgs.parse(args);

        JdiBackend jdi = null;
        CrochetBackend crochet = null;
        Process targetProcess = null;

        try {
            // ---- Launch or attach target JVM --------------------------------
            if (a.targetJar != null) {
                targetProcess = launchTarget(a);
                // Give JDWP a moment to start listening.
                Thread.sleep(500);
            }

            // ---- Connect JDI ------------------------------------------------
            if (a.jdwpPort > 0) {
                jdi = new JdiBackend();
                emit("{\"ok\":true,\"event\":\"connecting\",\"transport\":\"jdwp\","
                        + "\"port\":" + a.jdwpPort + "}");
                jdi.connect("127.0.0.1", a.jdwpPort, CONNECT_TIMEOUT_MS);
                String startLoc = jdi.awaitStart();
                emit("{\"ok\":true,\"event\":\"suspended\","
                        + "\"reason\":\"start\",\"location\":"
                        + JdiBackend.jsonStr(startLoc) + "}");

                // If we also need the Crochet REPL: the target JVM is currently
                // suspended at VMStart (before main() runs). We must resume it so
                // HelloBuggy can bind the REPL socket. We then wait for it to
                // reach the first TTD breakpoint (which re-suspends it).
                if (a.replPort > 0) {
                    emit("{\"ok\":true,\"event\":\"resuming-for-repl\",\"note\":"
                            + "\"resuming JVM so target can bind REPL port\"}");
                    jdi.resume();
                }
            }

            // ---- Connect Crochet REPL ---------------------------------------
            if (a.replPort > 0) {
                crochet = new CrochetBackend();
                emit("{\"ok\":true,\"event\":\"connecting\",\"transport\":\"repl\","
                        + "\"port\":" + a.replPort + "}");
                // The REPL socket will be bound shortly after the JVM starts running.
                // CrochetBackend.connect() retries for up to CONNECT_TIMEOUT_MS.
                crochet.connect(a.replPort, CONNECT_TIMEOUT_MS);
                emit("{\"ok\":true,\"event\":\"repl-connected\",\"port\":" + a.replPort + "}");
                // The REPL connect() call drains the initial REPL output (the first
                // "[ttd] at step N..." lines) and leaves us at a prompt. The JVM is
                // now running inside the TTD session body, paused at the first
                // breakpoint.
                // NOTE: JDI does not know the VM is suspended (it was resumed above).
                // JDI forward commands (step/where/locals) require the JVM to be
                // suspended via JDWP. For the benchmark agent, TTD commands are the
                // primary interface once the REPL is connected; JDI commands can be
                // issued after setting a breakpoint + continue.
            }

            // ---- Command loop -----------------------------------------------
            UnifiedCommandRouter router = new UnifiedCommandRouter(jdi, crochet);
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while (!router.isDone() && (line = stdin.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String response = router.dispatch(trimmed);
                if (response != null) emit(response);
            }

        } catch (Exception e) {
            emit("{\"ok\":false,\"error\":" + JdiBackend.jsonStr(e.getMessage()) + "}");
            System.exit(1);
        } finally {
            if (crochet != null) crochet.close();
            if (jdi != null)     jdi.close();
            if (targetProcess != null) targetProcess.destroyForcibly();
        }
    }

    private static void emit(String json) {
        System.out.println(json);
        System.out.flush();
    }

    /**
     * Spawn the target JVM with JDWP enabled.
     *
     * <p>The target program is responsible for calling
     * {@code Ttd.sessionWithRepl(root, SocketRepl.onPort(replPort), body)}.
     */
    private static Process launchTarget(CliArgs a) throws Exception {
        List<String> cmd = new ArrayList<>();
        String javaHome = System.getProperty("java.home");
        cmd.add(javaHome + "/bin/java");
        cmd.add("--add-modules");
        cmd.add("jdk.jdi");
        if (a.jdwpPort > 0) {
            cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:" + a.jdwpPort);
        }
        if (a.extraJvmArgs != null) {
            cmd.addAll(Arrays.asList(a.extraJvmArgs.split("\\s+")));
        }
        cmd.add("-jar");
        cmd.add(a.targetJar);

        emit("{\"ok\":true,\"event\":\"launching\",\"cmd\":"
                + JdiBackend.jsonStr(String.join(" ", cmd)) + "}");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        return pb.start();
    }

    // -------------------------------------------------------------------------
    // Argument parsing
    // -------------------------------------------------------------------------

    static final class CliArgs {
        boolean attach = false;
        int jdwpPort = DEFAULT_JDWP_PORT;
        int replPort = DEFAULT_REPL_PORT;
        String targetJar = null;
        String extraJvmArgs = null;

        static CliArgs parse(String[] argv) {
            CliArgs a = new CliArgs();
            for (int i = 0; i < argv.length; i++) {
                switch (argv[i]) {
                    case "--attach"    -> a.attach = true;
                    case "--jdwp-port" -> a.jdwpPort = Integer.parseInt(argv[++i]);
                    case "--repl-port" -> a.replPort = Integer.parseInt(argv[++i]);
                    case "--target"    -> a.targetJar = argv[++i];
                    case "--jvm-args"  -> a.extraJvmArgs = argv[++i];
                    case "--no-repl"   -> a.replPort = -1;
                    case "--no-jdwp"   -> a.jdwpPort = -1;
                    default -> {
                        System.err.println("Unknown argument: " + argv[i]);
                        printUsage();
                        System.exit(2);
                    }
                }
            }
            return a;
        }

        private static void printUsage() {
            System.err.println("Usage:");
            System.err.println("  crochet-debug [--attach] [--jdwp-port N] [--repl-port N]");
            System.err.println("                [--target <jar>] [--jvm-args \"...\"]");
            System.err.println("                [--no-repl] [--no-jdwp]");
        }
    }
}
