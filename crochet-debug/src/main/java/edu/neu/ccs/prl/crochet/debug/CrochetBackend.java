package edu.neu.ccs.prl.crochet.debug;

import net.jonbell.crochet.annotation.Internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Client-side bridge to the Crochet REPL socket server running inside the
 * target JVM ({@code SocketRepl}).
 *
 * <p>Connects to {@code 127.0.0.1:replPort}, then drives the existing line-
 * oriented REPL text protocol. The REPL emits a {@code "(ttd) "} prompt
 * whenever it is ready for a command; this class uses that prompt as the
 * synchronization point between request and response.
 *
 * <p>Each public method sends one REPL command and collects the response lines
 * (everything between two consecutive {@code "(ttd) "} prompts), returning
 * them as a single string. The {@link UnifiedCommandRouter} wraps the string in
 * the appropriate JSON envelope before writing to the CLI stdout.
 *
 * <p><b>Protocol summary:</b>
 * <pre>
 *   CLI → target : "n\n"          (send command)
 *   target → CLI : "[ttd] at step 2  Foo.bar()V:42\n"
 *                  "(ttd) "        (prompt = ready for next command)
 * </pre>
 *
 * <p>The backend treats a closed socket as a normal session-end condition (the
 * target JVM finished the session body or was told to quit).
 *
 * @see SocketRepl (in crochet-ttd)
 */
@Internal
public final class CrochetBackend implements AutoCloseable {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean connected = false;

    /** Prompt string emitted by {@code Repl} when awaiting the next command. */
    static final String PROMPT = "(ttd) ";

    /**
     * Connect to the Crochet socket REPL on {@code 127.0.0.1:port}.
     * Retries up to {@code timeoutMs} milliseconds with 200 ms intervals.
     *
     * @param port      TCP port where {@code SocketRepl} is listening
     * @param timeoutMs maximum wait in milliseconds
     * @throws IOException if connection fails within the timeout
     */
    public void connect(int port, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                socket = new Socket("127.0.0.1", port);
                socket.setTcpNoDelay(true);
                out = new PrintWriter(socket.getOutputStream(), /*autoFlush=*/true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                // Drain the initial "[ttd] at step N..." lines until we see the prompt.
                drainUntilPrompt();
                return;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(200); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for REPL", ie);
                }
            }
        }
        throw new IOException("Failed to connect to Crochet REPL on port " + port
                + " within " + timeoutMs + " ms", last);
    }

    /** Whether the REPL connection is alive. */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Send a {@code back} command (back-step one breakpoint).
     *
     * @return the REPL's response text (may span multiple lines)
     */
    public String back() throws IOException {
        return sendCommand("b");
    }

    /**
     * Send a {@code next} command (forward one breakpoint).
     *
     * @return the REPL's response text
     */
    public String ttdNext() throws IOException {
        return sendCommand("n");
    }

    /**
     * Send a {@code goto N} command.
     *
     * @param n target breakpoint index (1-based)
     * @return the REPL's response text
     */
    public String ttdGoto(int n) throws IOException {
        return sendCommand("g " + n);
    }

    /**
     * Send an {@code inspect} command to dump the tracked root's fields.
     *
     * @return the REPL's response text
     */
    public String inspect() throws IOException {
        return sendCommand("i");
    }

    /**
     * Send a {@code where} command to print the current breakpoint index.
     *
     * @return the REPL's response text
     */
    public String where() throws IOException {
        return sendCommand("w");
    }

    /**
     * Send a {@code quit} command, closing the REPL session.
     */
    public void quit() throws IOException {
        if (!connected) return;
        try {
            out.println("q");
            out.flush();
        } finally {
            connected = false;
            close();
        }
    }

    /**
     * Send an arbitrary raw command to the REPL.
     * Used for extensibility; the caller is responsible for the command syntax.
     *
     * @param command raw REPL command text (no trailing newline needed)
     * @return the REPL's response text up to the next prompt
     */
    public String sendCommand(String command) throws IOException {
        if (!connected) throw new IOException("Not connected to Crochet REPL");
        out.println(command);
        out.flush();
        return drainUntilPrompt();
    }

    @Override
    public void close() {
        connected = false;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Read lines from the socket until we encounter the {@code "(ttd) "} prompt
     * (which may appear inline at the start of a line rather than on its own
     * line, as {@code PrintStream.print} does not add a newline after the prompt).
     *
     * <p>Strategy: use {@link BufferedReader#read(char[], int, int)} in small
     * chunks and accumulate until we see the prompt substring. This avoids the
     * blocking {@link BufferedReader#readLine()} stalling on the promptless line.
     */
    private String drainUntilPrompt() throws IOException {
        StringBuilder acc = new StringBuilder();
        char[] buf = new char[256];
        while (true) {
            // Check if we already have the prompt in the buffer.
            int idx = acc.indexOf(PROMPT);
            if (idx >= 0) {
                // Return everything before the prompt; discard the prompt itself.
                return acc.substring(0, idx).trim();
            }
            // Not yet — read more characters.
            int n;
            try {
                n = in.read(buf, 0, buf.length);
            } catch (IOException e) {
                connected = false;
                throw e;
            }
            if (n < 0) {
                // Socket closed — return what we have.
                connected = false;
                return acc.toString().trim();
            }
            acc.append(buf, 0, n);
        }
    }
}
