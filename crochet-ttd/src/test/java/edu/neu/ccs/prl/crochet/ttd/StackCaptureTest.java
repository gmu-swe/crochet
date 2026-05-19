package edu.neu.ccs.prl.crochet.ttd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for B.5 stack-as-data:
 * <ul>
 *   <li>captureStack() LIFO order at depths 1..5 (synthetic frames)</li>
 *   <li>classMethodLine with and without registration</li>
 *   <li>Local-variable name resolution (present vs. absent LVT)</li>
 *   <li>Serialization stability (same chain → byte-identical JSON)</li>
 * </ul>
 *
 * <p>B.3's bytecode rewrite isn't in yet; frames are pushed manually via
 * {@link Ttd#saveFrame} to exercise the pure runtime API layer.
 */
class StackCaptureTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Minimal scripted Repl: responds "q\n" to the first prompt. */
    private static Repl quitRepl() {
        ByteArrayInputStream in = new ByteArrayInputStream("q\n".getBytes());
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true);
        return new Repl(in, out);
    }

    static final class Holder { int x; }

    // =========================================================================
    // Per-test bookkeeping
    // =========================================================================

    @BeforeEach
    void resetGen() {
        // Reset TTD_GEN to 0 (pristine) so saveFrame / popResumeFrame take the
        // early-return path.  Also clear any stale deque entries left by tests
        // that bypass sessionWithRepl's clearSessionState() (e.g., tests that
        // call testSetTtdGen() directly without running a full session).
        Ttd.testSetTtdGen(0L);
        Ttd.testClearDeque();
    }

    @AfterEach
    void checkCounterEven() {
        // After each test, TTD_GEN must be even (no session currently active).
        // Sessions run during the test leave TTD_GEN at a positive even value.
        assertEquals(0L, Ttd.TTD_GEN % 2,
                "TTD_GEN must be even after each test (no session active); "
                        + "actual=" + Ttd.TTD_GEN);
    }

    // =========================================================================
    // 1. captureStack() returns empty list outside any session
    // =========================================================================

    @Test
    void captureStack_empty_outside_session() {
        List<StackEntry> stack = Ttd.captureStack();
        assertNotNull(stack, "should never return null");
        assertTrue(stack.isEmpty(), "must be empty outside session");
    }

    // =========================================================================
    // 2. captureStack() LIFO order at depths 1..5
    // =========================================================================

    /**
     * Push N synthetic frames with distinct bcis (0, 1, ..., N-1); verify that
     * {@link Ttd#captureStack()} returns N entries in innermost-first (LIFO)
     * order — i.e., entry 0 is the most-recently-pushed frame.
     */
    @ParameterizedTest(name = "captureStack_lifo_depth_{0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void captureStack_lifo_order(int depth) {
        Holder root = new Holder();
        int methodId = Ttd.internMethodId("Test/StackCapture.lifo" + depth + "()V");

        List<StackEntry>[] captured = new List[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            // Push depth frames with bcis 0..depth-1 (bci=0 first, bci=depth-1 last).
            for (int i = 0; i < depth; i++) {
                Ttd.saveFrame(methodId, i, new long[0], new Object[0]);
            }

            // captureStack() is called while the frames are still on the deque.
            captured[0] = Ttd.captureStack();
        });

        List<StackEntry> stack = captured[0];
        assertNotNull(stack, "captureStack must not return null");
        assertEquals(depth, stack.size(), "must have " + depth + " entries");

        // Deque uses ArrayDeque.push = addFirst, so iteration order is LIFO:
        // innermost (last pushed, bci=depth-1) is at index 0.
        for (int i = 0; i < depth; i++) {
            int expectedBci = depth - 1 - i;
            String sentinel = "<methodId=" + methodId + " bci=" + expectedBci + ">";
            assertEquals(sentinel, stack.get(i).classMethodLine(),
                    "frame " + i + " classMethodLine mismatch");
        }
    }

    // =========================================================================
    // 3. classMethodLine with a registered label
    // =========================================================================

    @Test
    void captureStack_registered_label() {
        Holder root = new Holder();
        int methodId = Ttd.internMethodId("Test/StackCapture.registeredLabel()V");
        int bci = 77;
        String label = "com/example/Foo.doWork(I)V:42";
        Ttd.registerMethodLine(methodId, bci, label);

        List<StackEntry>[] captured = new List[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            Ttd.saveFrame(methodId, bci, new long[0], new Object[0]);
            captured[0] = Ttd.captureStack();
        });

        assertEquals(1, captured[0].size());
        assertEquals(label, captured[0].get(0).classMethodLine(),
                "registered label must appear in classMethodLine");
    }

    // =========================================================================
    // 4. classMethodLine sentinel for unregistered (methodId, bci)
    // =========================================================================

    @Test
    void captureStack_sentinel_for_unregistered() {
        Holder root = new Holder();
        // Use a fresh unique key so we're sure no prior registration exists.
        int methodId = Ttd.internMethodId("Test/StackCapture.unregistered_" + System.nanoTime() + "()V");
        int bci = 999;

        List<StackEntry>[] captured = new List[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            Ttd.saveFrame(methodId, bci, new long[0], new Object[0]);
            captured[0] = Ttd.captureStack();
        });

        assertEquals(1, captured[0].size());
        String expected = "<methodId=" + methodId + " bci=" + bci + ">";
        assertEquals(expected, captured[0].get(0).classMethodLine(),
                "unregistered save-point must produce sentinel");
    }

    // =========================================================================
    // 5. Local-variable name resolution: LVT present (registered)
    // =========================================================================

    @Test
    void captureStack_locals_with_registered_names() {
        Holder root = new Holder();
        int methodId = Ttd.internMethodId("Test/StackCapture.withNames()V");
        int bci = 10;
        String label = "com/example/Bar.compute()V:10";

        // Simulate what B.3 would emit at class-load time:
        // 1 prim slot (int "count"), 1 ref slot (Object "result")
        Ttd.registerMethodLine(methodId, bci, label,
                new String[]{"count"}, new String[]{"I"},
                new String[]{"result"}, new String[]{"Ljava/lang/Object;"});

        List<StackEntry>[] captured = new List[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            // prims: count=42 (stored as long), refs: result="hello"
            Ttd.saveFrame(methodId, bci, new long[]{42L}, new Object[]{"hello"});
            captured[0] = Ttd.captureStack();
        });

        assertEquals(1, captured[0].size());
        List<LocalSnapshot> locals = captured[0].get(0).locals();
        assertEquals(2, locals.size(), "must have 2 locals");

        // Prim slot 0: count=42
        assertEquals("count",  locals.get(0).name(),           "prim name");
        assertEquals("I",      locals.get(0).typeDescriptor(),  "prim descriptor");
        assertEquals("42",     locals.get(0).value(),           "prim value");

        // Ref slot 0: result="hello"
        assertEquals("result",               locals.get(1).name(),           "ref name");
        assertEquals("Ljava/lang/Object;",   locals.get(1).typeDescriptor(),  "ref descriptor");
        assertEquals("hello",                locals.get(1).value(),           "ref value");
    }

    // =========================================================================
    // 6. Local-variable name resolution: -g:none fallback ($slotN / ?)
    // =========================================================================

    @Test
    void captureStack_locals_fallback_when_no_lvt() {
        Holder root = new Holder();
        // Use a unique key and do NOT register any MethodLineInfo.
        int methodId = Ttd.internMethodId("Test/StackCapture.noLvt_" + System.nanoTime() + "()V");
        int bci = 5;

        List<StackEntry>[] captured = new List[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            // 2 prim slots, 1 ref slot — no name info registered
            Ttd.saveFrame(methodId, bci, new long[]{10L, 20L}, new Object[]{null});
            captured[0] = Ttd.captureStack();
        });

        assertEquals(1, captured[0].size());
        List<LocalSnapshot> locals = captured[0].get(0).locals();
        assertEquals(3, locals.size(), "2 prims + 1 ref = 3 locals");

        // Fallback names
        assertEquals("$slot0", locals.get(0).name(), "fallback prim slot 0");
        assertEquals("?",      locals.get(0).typeDescriptor(), "fallback prim desc 0");
        assertEquals("10",     locals.get(0).value(), "prim value 0");

        assertEquals("$slot1", locals.get(1).name(), "fallback prim slot 1");
        assertEquals("?",      locals.get(1).typeDescriptor(), "fallback prim desc 1");
        assertEquals("20",     locals.get(1).value(), "prim value 1");

        assertEquals("$slot0", locals.get(2).name(), "fallback ref slot 0");
        assertEquals("?",      locals.get(2).typeDescriptor(), "fallback ref desc 0");
        assertEquals("null",   locals.get(2).value(), "null ref value");
    }

    // =========================================================================
    // 7. Serialization stability: same chain → byte-identical JSON
    // =========================================================================

    @Test
    void serializeStack_stable() {
        Holder root = new Holder();
        int methodId = Ttd.internMethodId("Test/StackCapture.serialize()V");
        int bci = 33;
        String label = "com/example/Baz.run()V:33";
        Ttd.registerMethodLine(methodId, bci, label,
                new String[]{"n"}, new String[]{"J"},
                new String[]{"s"}, new String[]{"Ljava/lang/String;"});

        List<StackEntry>[] c1 = new List[1];
        List<StackEntry>[] c2 = new List[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            Ttd.saveFrame(methodId, bci, new long[]{7L}, new Object[]{"world"});
            c1[0] = Ttd.captureStack();
            c2[0] = Ttd.captureStack();
        });

        String json1 = Ttd.serializeStack(c1[0]);
        String json2 = Ttd.serializeStack(c2[0]);

        assertNotNull(json1, "serialized output must not be null");
        assertEquals(json1, json2, "two serializations of the same chain must be byte-identical");

        // Basic schema checks
        assertTrue(json1.contains("\"schemaVersion\":1"), "must include schemaVersion:1");
        assertTrue(json1.contains("\"frames\":"), "must include frames array");
        assertTrue(json1.contains(label), "must include label");
        assertTrue(json1.contains("\"n\""), "must include local name 'n'");
        assertTrue(json1.contains("\"7\""), "must include prim value '7'");
        assertTrue(json1.contains("\"world\""), "must include ref value 'world'");
    }

    // =========================================================================
    // 8. captureStack() decoupled from live deque
    // =========================================================================

    @Test
    void captureStack_decoupled_from_deque() {
        Holder root = new Holder();
        int methodId = Ttd.internMethodId("Test/StackCapture.decouple()V");

        List<StackEntry>[] before = new List[1];
        List<StackEntry>[] after  = new List[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            Ttd.saveFrame(methodId, 1, new long[0], new Object[0]);
            before[0] = Ttd.captureStack();  // snapshot with 1 frame

            // Push a second frame — should NOT appear in the already-captured list.
            Ttd.saveFrame(methodId, 2, new long[0], new Object[0]);
            after[0] = Ttd.captureStack();   // snapshot with 2 frames

            // Pop both so deque is empty at session exit.
            Ttd.popResumeFrame(methodId);
            Ttd.popResumeFrame(methodId);
        });

        assertEquals(1, before[0].size(), "first snapshot must have 1 frame");
        assertEquals(2, after[0].size(),  "second snapshot must have 2 frames");
    }

    // =========================================================================
    // 9. serializeStack() on empty list
    // =========================================================================

    @Test
    void serializeStack_empty() {
        String json = Ttd.serializeStack(List.of());
        assertEquals("{\"schemaVersion\":1,\"frames\":[]}", json,
                "empty stack must serialize to versioned JSON with empty frames array");
    }

    // =========================================================================
    // 10. JSON escaping in LocalSnapshot
    // =========================================================================

    @Test
    void local_snapshot_json_escaping() {
        // A ref whose toString() contains special JSON characters.
        Holder root = new Holder();
        int methodId = Ttd.internMethodId("Test/StackCapture.escaping()V");
        int bci = 1;

        List<StackEntry>[] captured = new List[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            // Push a ref with quotes and backslash.
            Ttd.saveFrame(methodId, bci, new long[0], new Object[]{"say \"hi\"\\"});
            captured[0] = Ttd.captureStack();
        });

        String json = Ttd.serializeStack(captured[0]);
        // The value field should have escaped quotes and backslash.
        assertTrue(json.contains("\\\"hi\\\""), "double-quotes must be escaped");
        assertTrue(json.contains("\\\\"), "backslash must be escaped");
    }
}
