package edu.neu.ccs.prl.crochet.ttd;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import net.jonbell.crochet.annotation.Experimental;

/**
 * Socket-hosted REPL frontend for {@link Ttd}.
 *
 * <p>A {@code SocketRepl} binds a {@link ServerSocket} on the given TCP port,
 * accepts exactly one client connection, and wraps that connection's streams as
 * the REPL's input/output. The rest of the command loop is inherited from
 * {@link Repl} unchanged — the same line-oriented text protocol is served over
 * the socket.
 *
 * <p>Intended for the {@code crochet-debug} CLI (architecture b): the CLI's
 * {@code CrochetBackend} connects to this port and speaks the REPL protocol
 * while translating to/from JSON on the CLI side.
 *
 * <p>Usage in the target JVM:
 * <pre>
 *   Ttd.sessionWithRepl(root, SocketRepl.onPort(5006), () -&gt; { ... });
 * </pre>
 *
 * <p>The {@link ServerSocket} is bound before {@link Ttd#sessionWithRepl} is
 * called (i.e., before the session starts), so the CLI can rely on the port
 * being open before the body begins executing. {@link #onPort(int)} blocks
 * until one client connection is accepted; the session body does not start
 * until the CLI is connected.
 *
 * <p><b>Thread safety:</b> {@code SocketRepl} is not thread-safe; use only
 * from the thread running {@link Ttd#sessionWithRepl}.
 *
 * @see Repl
 */
@Experimental
public final class SocketRepl extends Repl {

    /** The accepted client socket; closed when the session ends. */
    private final Socket client;

    private SocketRepl(Socket client, InputStream in, PrintStream out) {
        super(in, out);
        this.client = client;
    }

    /**
     * Bind a {@link ServerSocket} on {@code port}, accept one connection, and
     * return a {@code SocketRepl} backed by that connection.
     *
     * <p>Binds only on localhost ({@code 127.0.0.1}) to avoid exposing the
     * REPL port on network interfaces in multi-tenant environments.
     *
     * <p>Blocks until a client connects. The CLI should connect before the
     * session body starts executing, so this call should be made immediately
     * before {@link Ttd#sessionWithRepl} in the target program.
     *
     * @param port TCP port to listen on; must be in range [1, 65535]
     * @return a {@code SocketRepl} backed by the accepted connection
     * @throws IOException if the socket cannot be bound or accepted
     */
    public static SocketRepl onPort(int port) throws IOException {
        // Use try-with-resources for the ServerSocket: we only need it to
        // accept one connection, after which it can be closed.
        try (ServerSocket server = new ServerSocket(port, 1,
                InetAddress.getByName("127.0.0.1"))) {
            server.setReuseAddress(true);
            Socket client = server.accept();
            client.setTcpNoDelay(true);
            PrintStream out = new PrintStream(client.getOutputStream(), /*autoFlush=*/true);
            return new SocketRepl(client, client.getInputStream(), out);
        }
    }

    /**
     * Bind a server socket on {@code port} and return it (without accepting).
     * The caller is responsible for calling {@link #acceptFrom(ServerSocket)}.
     *
     * <p>Use this two-phase form when you need to advertise the port as "ready"
     * (e.g., print the port number to stdout) before blocking on accept.
     *
     * @param port TCP port to bind; 0 for OS-assigned ephemeral port
     * @return bound (but not yet accepted) {@link ServerSocket}
     * @throws IOException if the socket cannot be bound
     */
    public static ServerSocket bindPort(int port) throws IOException {
        ServerSocket server = new ServerSocket(port, 1,
                InetAddress.getByName("127.0.0.1"));
        server.setReuseAddress(true);
        return server;
    }

    /**
     * Accept one connection from the given (already-bound) {@link ServerSocket}
     * and return a {@code SocketRepl} backed by that connection. The
     * {@code ServerSocket} is closed after accepting.
     *
     * @param server a bound {@link ServerSocket} as returned by {@link #bindPort(int)}
     * @return a {@code SocketRepl} backed by the accepted connection
     * @throws IOException if accept fails
     */
    public static SocketRepl acceptFrom(ServerSocket server) throws IOException {
        try (ServerSocket s = server) {
            Socket client = s.accept();
            client.setTcpNoDelay(true);
            PrintStream out = new PrintStream(client.getOutputStream(), /*autoFlush=*/true);
            return new SocketRepl(client, client.getInputStream(), out);
        }
    }

    /**
     * Close the underlying client socket. Safe to call after the session ends.
     * Idempotent.
     */
    public void close() {
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }
}
