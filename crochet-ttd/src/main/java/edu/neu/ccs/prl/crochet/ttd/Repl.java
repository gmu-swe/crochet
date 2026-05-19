package edu.neu.ccs.prl.crochet.ttd;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import edu.neu.ccs.prl.crochet.ttd.nondet.NondetDivergenceEvent;
import edu.neu.ccs.prl.crochet.ttd.nondet.NondetRecorder;

/**
 * REPL frontend for {@link Ttd}. Phase 0: line-oriented stdin/stdout.
 *
 * <p>Commands:
 * <ul>
 *   <li>{@code n} / {@code next} — continue to the next breakpoint</li>
 *   <li>{@code b} / {@code back} — rollback heap, replay to the previous
 *       breakpoint</li>
 *   <li>{@code g N} / {@code goto N} — go to breakpoint index N (forward
 *       continues; backward rolls back and replays)</li>
 *   <li>{@code i} / {@code inspect} — print the tracked root's fields via
 *       reflection</li>
 *   <li>{@code w} / {@code where} — print current breakpoint index</li>
 *   <li>{@code q} / {@code quit} — exit the session</li>
 *   <li>{@code h} / {@code help} — print this list</li>
 * </ul>
 *
 * <p>The REPL is intentionally minimal — no expression evaluator. For
 * inspecting non-root state, use {@link #setRoot}-style overloads in a
 * future phase or print from inside the body via {@link #println}.
 */
public final class Repl {

    private final BufferedReader in;
    private final PrintStream out;

    Repl(InputStream stdin, PrintStream stdout) {
        this.in = new BufferedReader(new InputStreamReader(stdin));
        this.out = stdout;
    }

    /** Default REPL bound to {@link System#in} / {@link System#out}. */
    public static Repl fromStdin() {
        return new Repl(System.in, System.out);
    }

    /**
     * Install this REPL's output stream as the nondet divergence handler.
     * Call once per session; divergence events will be printed to the REPL
     * output alongside normal REPL output.
     *
     * <p>The previous handler is restored by {@link #uninstallDivergenceHandler}.
     */
    void installDivergenceHandler() {
        NondetRecorder.setDivergenceHandler(event -> {
            out.println("[ttd-nondet] " + event.toString());
            out.flush();
        });
    }

    /**
     * Restore the default divergence handler (stderr).
     */
    void uninstallDivergenceHandler() {
        NondetRecorder.setDivergenceHandler(
                event -> System.err.println(event.toString()));
    }

    /**
     * Emit a structured nondet divergence event to the REPL output stream.
     * May be called from outside the prompt loop (e.g., from a recording
     * session's divergence callback).
     *
     * @param event the divergence event to display
     */
    public void emitDivergence(NondetDivergenceEvent event) {
        out.println("[ttd-nondet] " + event.toString());
        out.flush();
    }

    void println(String s) {
        out.println(s);
        out.flush();
    }

    /**
     * Prompt the user for the next command at a breakpoint hit.
     *
     * @param ctx       current TTD session context (read-only here)
     * @param atEnd     true iff the body has run to completion (no more
     *                  forward steps possible)
     * @return user's chosen action
     */
    Action prompt(Ttd.TtdContext ctx, boolean atEnd) {
        String suffix = atEnd ? " (end of body)" : "";
        if (ctx.currentLineCtx != null) {
            out.printf("[ttd] at step %d  %s%s%n",
                    ctx.currentIdx, ctx.currentLineCtx, suffix);
        } else {
            out.printf("[ttd] at breakpoint %d%s%n", ctx.currentIdx, suffix);
        }
        out.flush();
        while (true) {
            out.print("(ttd) ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (java.io.IOException e) {
                return Action.quit();
            }
            if (line == null) {
                return Action.quit();
            }
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 2);
            String cmd = parts[0];
            String arg = parts.length > 1 ? parts[1] : null;
            try {
                switch (cmd) {
                    case "n": case "next":
                        if (atEnd) {
                            out.println("[ttd] already at end; use 'back' or 'goto N'");
                            continue;
                        }
                        return Action.cont(ctx.currentIdx + 1);
                    case "b": case "back":
                        if (ctx.currentIdx <= 1) {
                            out.println("[ttd] already at first breakpoint; "
                                    + "use 'goto 1' to re-enter from session start");
                            continue;
                        }
                        return Action.restart(ctx.currentIdx - 1);
                    case "g": case "goto": {
                        if (arg == null) {
                            out.println("[ttd] usage: goto N");
                            continue;
                        }
                        int target = Integer.parseInt(arg);
                        if (target < 1) {
                            out.println("[ttd] target must be >= 1");
                            continue;
                        }
                        if (target > ctx.currentIdx) {
                            return Action.cont(target);
                        } else if (target == ctx.currentIdx && !atEnd) {
                            out.println("[ttd] already at breakpoint " + target);
                            continue;
                        } else {
                            return Action.restart(target);
                        }
                    }
                    case "i": case "inspect":
                        printRoot(ctx.root);
                        continue;
                    case "w": case "where":
                        if (ctx.currentLineCtx != null) {
                            out.printf("[ttd] step %d  %s%s%n",
                                    ctx.currentIdx, ctx.currentLineCtx,
                                    atEnd ? " (end of body)" : "");
                        } else {
                            out.printf("[ttd] breakpoint %d%s%n", ctx.currentIdx,
                                    atEnd ? " (end of body)" : "");
                        }
                        continue;
                    case "q": case "quit":
                        return Action.quit();
                    case "h": case "help":
                        printHelp();
                        continue;
                    default:
                        out.println("[ttd] unknown command: " + cmd
                                + " (try 'help')");
                        continue;
                }
            } catch (NumberFormatException e) {
                out.println("[ttd] bad number: " + arg);
            }
        }
    }

    private void printRoot(Object root) {
        out.println("[ttd] " + root.getClass().getSimpleName() + " {");
        for (Class<?> c = root.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getName().startsWith("$$crochet")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(root);
                    out.printf("  %s = %s%n", f.getName(), formatValue(v));
                } catch (Throwable t) {
                    out.printf("  %s = <error: %s>%n", f.getName(), t);
                }
            }
        }
        out.println("}");
    }

    private static String formatValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "\"" + v + "\"";
        return String.valueOf(v);
    }

    private void printHelp() {
        out.println("commands:");
        out.println("  n / next        continue to next breakpoint");
        out.println("  b / back        rollback + replay to previous breakpoint");
        out.println("  g N / goto N    jump to breakpoint N (forward or backward)");
        out.println("  i / inspect     dump tracked root's fields");
        out.println("  w / where       print current breakpoint index");
        out.println("  q / quit        exit session");
        out.println("  h / help        this message");
    }

    /** Action returned by the REPL to {@link Ttd#breakpoint}. */
    static final class Action {
        enum Kind { CONTINUE, RESTART, QUIT }
        final Kind kind;
        final int targetIdx;

        private Action(Kind kind, int targetIdx) {
            this.kind = kind;
            this.targetIdx = targetIdx;
        }

        static Action cont(int target)    { return new Action(Kind.CONTINUE, target); }
        static Action restart(int target) { return new Action(Kind.RESTART,  target); }
        static Action quit()              { return new Action(Kind.QUIT,     -1); }
    }
}
