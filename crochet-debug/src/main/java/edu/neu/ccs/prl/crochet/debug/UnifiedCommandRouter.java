package edu.neu.ccs.prl.crochet.debug;

import net.jonbell.crochet.annotation.Experimental;

import java.io.IOException;

/**
 * Routes unified CLI commands to either {@link JdiBackend} (forward commands)
 * or {@link CrochetBackend} (backward/TTD commands).
 *
 * <p>Each {@code dispatch} call:
 * <ol>
 *   <li>Parses the command word and optional argument.</li>
 *   <li>Delegates to the appropriate backend.</li>
 *   <li>Returns a single JSON line (no trailing newline) ready for stdout.</li>
 * </ol>
 *
 * <p>The JSON envelope is always {@code {"ok":true/false,"result":<...>}} or
 * {@code {"ok":false,"error":"<msg>"}}.
 *
 * <h2>Command set</h2>
 *
 * <h3>Forward (JDI) commands</h3>
 * <ul>
 *   <li>{@code step} — step into</li>
 *   <li>{@code next} — step over</li>
 *   <li>{@code step-out} — step out</li>
 *   <li>{@code break <class>:<line>} — set breakpoint</li>
 *   <li>{@code clear <class>:<line>} — clear breakpoint</li>
 *   <li>{@code continue} — resume; wait for next suspend</li>
 *   <li>{@code where} — JDI stack trace</li>
 *   <li>{@code locals} — top-frame locals</li>
 *   <li>{@code eval <expr>} — evaluate simple expression</li>
 *   <li>{@code print <var>} — alias for eval</li>
 * </ul>
 *
 * <h3>Crochet TTD commands</h3>
 * <ul>
 *   <li>{@code back-step} — one REPL {@code back}</li>
 *   <li>{@code ttd-next} — one REPL {@code next}</li>
 *   <li>{@code ttd-goto <N>} — REPL {@code goto N}</li>
 *   <li>{@code capture-stack} — REPL {@code where} + Ttd stack info</li>
 *   <li>{@code inspect} — REPL {@code inspect}</li>
 *   <li>{@code ttd-where} — REPL {@code where}</li>
 *   <li>{@code diff <var>} — inspect named var via REPL {@code inspect} (best-effort)</li>
 *   <li>{@code session-end} — REPL {@code quit}</li>
 * </ul>
 *
 * <h3>Meta</h3>
 * <ul>
 *   <li>{@code quit} — quit CLI (and REPL if connected)</li>
 *   <li>{@code help} — command list</li>
 * </ul>
 */
@Experimental
public final class UnifiedCommandRouter {

    private final JdiBackend jdi;
    private final CrochetBackend crochet;

    /** Set to true when a {@code quit} command is received. */
    private boolean done = false;

    /**
     * Create a router backed by the given backends. Either backend may be
     * {@code null} if that transport is not available (e.g., no JDI when only
     * TTD is connected, or no REPL when running pure JDI).
     */
    public UnifiedCommandRouter(JdiBackend jdi, CrochetBackend crochet) {
        this.jdi = jdi;
        this.crochet = crochet;
    }

    /** Returns {@code true} after a {@code quit} command was processed. */
    public boolean isDone() {
        return done;
    }

    /**
     * Dispatch a single command line and return the JSON response string.
     * Never throws — all errors are returned as {@code {"ok":false,"error":"..."}}
     * JSON.
     *
     * @param line raw command line from stdin (may be blank or null)
     * @return JSON response line; {@code null} if the line was blank/null
     */
    public String dispatch(String line) {
        if (line == null || line.isBlank()) return null;
        String trimmed = line.trim();
        String[] parts = trimmed.split("\\s+", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1] : null;

        try {
            return switch (cmd) {
                // ---- Forward (JDI) -----------------------------------------
                case "step"     -> okResult("stepped", requireJdi().step());
                case "next"     -> okResult("stepped", requireJdi().next());
                case "step-out" -> okResult("stepped", requireJdi().stepOut());
                case "break"    -> handleBreak(arg, false);
                case "clear"    -> handleBreak(arg, true);
                case "continue" -> okResult("location", requireJdi().resumeAndWait());
                case "where"    -> okRaw("result", requireJdi().whereJson());
                case "locals"   -> okRaw("result", requireJdi().localsJson());
                case "eval", "print" -> {
                    if (arg == null) yield error("usage: " + cmd + " <expr>");
                    yield okResult("value", requireJdi().eval(arg));
                }
                // ---- Crochet TTD -------------------------------------------
                case "back-step"   -> okResult("ttd-response", requireRepl().back());
                case "ttd-next"    -> okResult("ttd-response", requireRepl().ttdNext());
                case "ttd-goto"    -> {
                    if (arg == null) yield error("usage: ttd-goto <N>");
                    yield okResult("ttd-response", requireRepl().ttdGoto(Integer.parseInt(arg)));
                }
                case "capture-stack" -> okResult("ttd-response", requireRepl().where());
                case "inspect"     -> okResult("ttd-response", requireRepl().inspect());
                case "ttd-where"   -> okResult("ttd-response", requireRepl().where());
                case "diff"        -> {
                    if (arg == null) yield error("usage: diff <var>");
                    // Best-effort: send inspect + filter for var name in output.
                    String resp = requireRepl().inspect();
                    yield okResult("diff", resp);
                }
                case "session-end" -> {
                    if (crochet != null) crochet.quit();
                    yield ok("session-ended");
                }
                // ---- Meta --------------------------------------------------
                case "quit"  -> {
                    done = true;
                    if (crochet != null) { try { crochet.quit(); } catch (IOException ignored) {} }
                    if (jdi != null)     { jdi.resume(); jdi.close(); }
                    yield ok("bye");
                }
                case "help"  -> helpJson();
                default      -> error("unknown-command: " + cmd);
            };
        } catch (NumberFormatException e) {
            return error("bad-number: " + e.getMessage());
        } catch (Exception e) {
            return error(sanitize(e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JdiBackend requireJdi() {
        if (jdi == null || !jdi.isAlive())
            throw new IllegalStateException("JDI backend not connected");
        return jdi;
    }

    private CrochetBackend requireRepl() {
        if (crochet == null || !crochet.isConnected())
            throw new IllegalStateException("Crochet REPL not connected");
        return crochet;
    }

    private String handleBreak(String arg, boolean clear) throws Exception {
        if (arg == null) return error("usage: " + (clear ? "clear" : "break") + " <class>:<line>");
        int colon = arg.lastIndexOf(':');
        if (colon < 0) return error("expected <class>:<line>");
        String cls = arg.substring(0, colon);
        int lineNo = Integer.parseInt(arg.substring(colon + 1));
        if (clear) {
            String loc = requireJdi().clearBreakpoint(cls, lineNo);
            return okResult("breakpoint-cleared", loc);
        } else {
            String loc = requireJdi().setBreakpoint(cls, lineNo);
            return okResult("breakpoint-set", loc);
        }
    }

    private static String ok(String result) {
        return "{\"ok\":true,\"result\":" + JdiBackend.jsonStr(result) + "}";
    }

    private static String okResult(String key, String value) {
        return "{\"ok\":true,\"" + key + "\":" + JdiBackend.jsonStr(value) + "}";
    }

    /**
     * Emit a JSON line where the value is already a JSON fragment (e.g., an
     * array) rather than a plain string.
     */
    private static String okRaw(String key, String jsonFragment) {
        return "{\"ok\":true,\"" + key + "\":" + jsonFragment + "}";
    }

    static String error(String msg) {
        return "{\"ok\":false,\"error\":" + JdiBackend.jsonStr(msg) + "}";
    }

    private static String sanitize(String s) {
        // Replace newlines so the error fits on one JSON line.
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static String helpJson() {
        String[] commands = {
            "step                 - step into (JDI)",
            "next                 - step over (JDI)",
            "step-out             - step out (JDI)",
            "break <class>:<line> - set breakpoint (JDI)",
            "clear <class>:<line> - clear breakpoint (JDI)",
            "continue             - resume + wait for next suspend (JDI)",
            "where                - JDI stack trace",
            "locals               - top-frame locals (JDI)",
            "eval <expr>          - evaluate expression (JDI)",
            "print <var>          - print variable value (JDI)",
            "back-step            - TTD back one breakpoint",
            "ttd-next             - TTD forward one breakpoint",
            "ttd-goto <N>         - TTD jump to breakpoint N",
            "capture-stack        - TTD current stack context",
            "inspect              - TTD inspect root object",
            "ttd-where            - TTD current breakpoint location",
            "diff <var>           - TTD inspect (best-effort diff for var)",
            "session-end          - end TTD session",
            "quit                 - quit CLI",
            "help                 - this message"
        };
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"result\":[");
        for (int i = 0; i < commands.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(JdiBackend.jsonStr(commands[i]));
        }
        sb.append("]}");
        return sb.toString();
    }
}
