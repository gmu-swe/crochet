package edu.neu.ccs.prl.crochet.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

import net.jonbell.crochet.annotation.Internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around {@link VirtualMachine} providing the forward-debugging
 * commands for the unified CLI.
 *
 * <p>Connects to a running JVM via JDWP socket attach. Manages the JDI event
 * queue to deliver step and breakpoint events to the CLI.
 *
 * <p>All methods are called from the CLI's command-loop thread. Step operations
 * resume the VM, wait for the corresponding event, and return the new location.
 *
 * @see UnifiedCommandRouter
 */
@Internal
public final class JdiBackend implements AutoCloseable {

    private VirtualMachine vm;
    private ThreadReference mainThread;
    private boolean vmAlive = false;

    /**
     * Connect to a JDWP listener on {@code host:port}.
     * Retries for up to {@code timeoutMs} milliseconds with 200 ms intervals.
     *
     * @param host      hostname (usually {@code "127.0.0.1"})
     * @param port      JDWP listen port
     * @param timeoutMs maximum wait in milliseconds
     * @throws Exception if connection fails within the timeout
     */
    public void connect(String host, int port, long timeoutMs) throws Exception {
        AttachingConnector connector = findSocketConnector();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(String.valueOf(port));
        args.get("timeout").setValue("2000");

        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                vm = connector.attach(args);
                vmAlive = true;
                break;
            } catch (Exception e) {
                last = e;
                Thread.sleep(200);
            }
        }
        if (!vmAlive) {
            throw new RuntimeException("Failed to connect to JDWP at " + host + ":" + port
                    + " within " + timeoutMs + " ms", last);
        }
    }

    /**
     * Wait for the initial VM-start event. After this returns, the VM is
     * suspended and ready for commands.
     *
     * @return description of start location (may be an empty string)
     * @throws Exception on event queue error or unexpected VM death
     */
    public String awaitStart() throws Exception {
        EventQueue queue = vm.eventQueue();
        while (true) {
            EventSet set = queue.remove(5000);
            if (set == null) throw new RuntimeException("Timed out waiting for VM start event");
            boolean gotStart = false;
            ThreadReference startThread = null;
            for (Event e : set) {
                if (e instanceof VMStartEvent vse) {
                    startThread = vse.thread();
                    gotStart = true;
                } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                    vmAlive = false;
                    try { set.resume(); } catch (Exception ignored) {}
                    throw new RuntimeException("VM exited before start event");
                }
            }
            if (gotStart) {
                // The VM starts in a suspended state (suspend=y). Do NOT resume
                // the event set — keep the VM suspended for the first command.
                mainThread = startThread;
                return "vm-started";
            }
            set.resume();
        }
    }

    /**
     * Execute a single-step (into calls) on {@link #mainThread}.
     * Returns the new source location as {@code "ClassName:line"}.
     */
    public String step() throws Exception {
        return doStep(StepRequest.STEP_LINE, StepRequest.STEP_INTO);
    }

    /**
     * Execute a next-line (over calls) step on {@link #mainThread}.
     * Returns the new source location as {@code "ClassName:line"}.
     */
    public String next() throws Exception {
        return doStep(StepRequest.STEP_LINE, StepRequest.STEP_OVER);
    }

    /**
     * Step out of the current method.
     * Returns the new source location as {@code "ClassName:line"}.
     */
    public String stepOut() throws Exception {
        return doStep(StepRequest.STEP_LINE, StepRequest.STEP_OUT);
    }

    /**
     * Set a breakpoint at {@code className:line}.
     *
     * @param className  simple or fully-qualified class name (e.g. {@code "HelloBuggy"})
     * @param line       source line number
     * @return description of the set location
     * @throws Exception if the class is not yet loaded or line is invalid
     */
    public String setBreakpoint(String className, int line) throws Exception {
        List<ReferenceType> types = vm.classesByName(className);
        if (types.isEmpty()) {
            throw new IllegalArgumentException("Class not loaded: " + className);
        }
        ReferenceType type = types.get(0);
        List<Location> locs = type.locationsOfLine(line);
        if (locs.isEmpty()) {
            throw new IllegalArgumentException("No executable location at "
                    + className + ":" + line);
        }
        EventRequestManager erm = vm.eventRequestManager();
        BreakpointRequest br = erm.createBreakpointRequest(locs.get(0));
        br.enable();
        return locationString(locs.get(0));
    }

    /**
     * Clear all breakpoints at {@code className:line}.
     *
     * @return description of the cleared location
     */
    public String clearBreakpoint(String className, int line) throws Exception {
        List<ReferenceType> types = vm.classesByName(className);
        if (types.isEmpty()) {
            throw new IllegalArgumentException("Class not loaded: " + className);
        }
        ReferenceType type = types.get(0);
        List<Location> locs = type.locationsOfLine(line);
        EventRequestManager erm = vm.eventRequestManager();
        List<BreakpointRequest> toDelete = new ArrayList<>();
        for (BreakpointRequest br : erm.breakpointRequests()) {
            if (locs.contains(br.location())) {
                toDelete.add(br);
            }
        }
        for (BreakpointRequest br : toDelete) {
            erm.deleteEventRequest(br);
        }
        return className + ":" + line;
    }

    /**
     * Resume execution and wait for the next breakpoint or step event.
     *
     * @return location where execution suspended, e.g. {@code "Foo:42"}
     */
    public String resumeAndWait() throws Exception {
        vm.resume();
        return waitForSuspend();
    }

    /**
     * Resume execution (fire-and-forget). The CLI will not wait for a suspend.
     * Use this for a final "continue" at end of session.
     */
    public void resume() {
        if (vmAlive) vm.resume();
    }

    /**
     * Return the current stack trace as a JSON array of
     * {@code {"class":"...","method":"...","line":N}} objects.
     */
    public String whereJson() throws Exception {
        checkSuspended();
        List<StackFrame> frames;
        try {
            frames = mainThread.frames();
        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException("Thread not suspended", e);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) sb.append(",");
            Location loc = frames.get(i).location();
            sb.append("{\"class\":").append(jsonStr(loc.declaringType().name()));
            sb.append(",\"method\":").append(jsonStr(loc.method().name()));
            sb.append(",\"line\":").append(loc.lineNumber());
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Return locals of the top frame as a JSON array of
     * {@code {"name":"...","type":"...","value":"..."}} objects.
     */
    public String localsJson() throws Exception {
        checkSuspended();
        StackFrame frame;
        try {
            frame = mainThread.frame(0);
        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException("Thread not suspended", e);
        }
        List<LocalVariable> vars;
        try {
            vars = frame.visibleVariables();
        } catch (AbsentInformationException e) {
            return "[{\"note\":\"no-debug-info\"}]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (LocalVariable lv : vars) {
            if (!first) sb.append(",");
            first = false;
            Value val = frame.getValue(lv);
            sb.append("{\"name\":").append(jsonStr(lv.name()));
            sb.append(",\"type\":").append(jsonStr(lv.typeName()));
            sb.append(",\"value\":").append(jsonStr(val == null ? "null" : val.toString()));
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Evaluate a simple field or local expression in the current frame.
     * Supported forms:
     * <ul>
     *   <li>{@code varName} — local variable in top frame</li>
     *   <li>{@code this.fieldName} — field of the current {@code this}</li>
     * </ul>
     *
     * @param expr expression string
     * @return string representation of the value
     */
    public String eval(String expr) throws Exception {
        checkSuspended();
        StackFrame frame;
        try {
            frame = mainThread.frame(0);
        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException("Thread not suspended", e);
        }
        expr = expr.trim();

        // Handle "this.field" form
        if (expr.startsWith("this.")) {
            String fieldName = expr.substring(5).trim();
            Value thisVal = null;
            try {
                List<LocalVariable> vars = frame.visibleVariables();
                for (LocalVariable lv : vars) {
                    if ("this".equals(lv.name())) {
                        thisVal = frame.getValue(lv);
                        break;
                    }
                }
            } catch (AbsentInformationException ignored) {}
            if (thisVal instanceof com.sun.jdi.ObjectReference ref) {
                ReferenceType type = ref.referenceType();
                Field f = type.fieldByName(fieldName);
                if (f == null) throw new IllegalArgumentException("No field: " + fieldName);
                return valueToString(ref.getValue(f));
            }
            // Fallback: try frame's declaring type static field
            Location loc = frame.location();
            ReferenceType type = loc.declaringType();
            Field f = type.fieldByName(fieldName);
            if (f != null) return valueToString(type.getValue(f));
            throw new IllegalArgumentException("Cannot resolve: " + expr);
        }

        // Try local variable
        try {
            List<LocalVariable> vars = frame.visibleVariables();
            for (LocalVariable lv : vars) {
                if (lv.name().equals(expr)) {
                    return valueToString(frame.getValue(lv));
                }
            }
        } catch (AbsentInformationException ignored) {}

        // Try static field of the current class
        Location loc = frame.location();
        ReferenceType type = loc.declaringType();
        Field f = type.fieldByName(expr);
        if (f != null) return valueToString(type.getValue(f));

        throw new IllegalArgumentException("Cannot resolve: " + expr);
    }

    /** Whether the target VM is still alive. */
    public boolean isAlive() {
        return vmAlive;
    }

    @Override
    public void close() {
        if (vmAlive) {
            try {
                vm.dispose();
            } catch (Exception ignored) {}
            vmAlive = false;
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private String doStep(int granularity, int depth) throws Exception {
        checkSuspended();
        EventRequestManager erm = vm.eventRequestManager();
        // Delete any existing step requests for this thread to avoid conflicts.
        for (StepRequest existing : erm.stepRequests()) {
            if (existing.thread().equals(mainThread)) {
                erm.deleteEventRequest(existing);
            }
        }
        StepRequest req = erm.createStepRequest(mainThread, granularity, depth);
        req.addCountFilter(1);
        req.enable();
        vm.resume();
        String loc = waitForSuspend();
        // Clean up the step request after it fires.
        try { erm.deleteEventRequest(req); } catch (Exception ignored) {}
        return loc;
    }

    private String waitForSuspend() throws Exception {
        EventQueue queue = vm.eventQueue();
        while (true) {
            EventSet set = queue.remove(30_000);
            if (set == null) throw new RuntimeException("Timed out waiting for VM suspend");
            boolean foundSuspend = false;
            String location = null;
            for (Event e : set) {
                if (e instanceof StepEvent se) {
                    mainThread = se.thread();
                    location = locationString(se.location());
                    foundSuspend = true;
                } else if (e instanceof BreakpointEvent be) {
                    mainThread = be.thread();
                    location = locationString(be.location());
                    foundSuspend = true;
                } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                    vmAlive = false;
                    // Resume the event set so JDI doesn't deadlock on shutdown.
                    try { set.resume(); } catch (Exception ignored) {}
                    return "vm-exited";
                }
            }
            if (foundSuspend) {
                // Leave the VM suspended — do NOT call set.resume() here.
                // The thread stays suspended so subsequent where/locals/eval work.
                return location;
            }
            // Non-step/breakpoint events (class-prepare, thread-start, etc.):
            // resume the event set to let the VM continue running.
            set.resume();
        }
    }

    private void checkSuspended() {
        if (!vmAlive) throw new IllegalStateException("VM is not alive");
    }

    private static String locationString(Location loc) {
        return loc.declaringType().name() + ":" + loc.lineNumber();
    }

    private static String valueToString(Value v) {
        return v == null ? "null" : v.toString();
    }

    static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static AttachingConnector findSocketConnector() {
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (c.name().contains("SocketAttach")) return c;
        }
        throw new RuntimeException("No socket attaching connector found");
    }
}
