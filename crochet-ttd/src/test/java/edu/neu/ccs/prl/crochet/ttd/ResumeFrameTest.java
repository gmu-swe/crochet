package edu.neu.ccs.prl.crochet.ttd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the B.2 ResumeFrame runtime:
 * <ul>
 *   <li>Zero-alloc steady state ({@link Ttd#TTD_GEN} == 0)</li>
 *   <li>Pop semantics (methodId match / mismatch)</li>
 *   <li>Session-exit cleanup (memory-leak check via WeakReference)</li>
 *   <li>Cross-thread isolation</li>
 *   <li>Method-id interning stability</li>
 *   <li>Session lifecycle counter</li>
 * </ul>
 */
class ResumeFrameTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Minimal scripted Repl: responds "q\n" to the first prompt. */
    private static Repl quitRepl() {
        ByteArrayInputStream in = new ByteArrayInputStream("q\n".getBytes());
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true);
        return new Repl(in, out);
    }

    /** Minimal state object so Ttd.sessionWithRepl doesn't throw NPE on root. */
    static final class Holder {
        int x;
    }

    // =========================================================================
    // Per-test bookkeeping
    // =========================================================================

    @BeforeEach
    void resetCounter() {
        // Defensive: if a previous test leaked the counter, reset it so
        // cold-path tests (TTD_GEN == 0) work reliably.
        Ttd.testSetTtdGen(0L);
    }

    @AfterEach
    void checkCounterEven() {
        // After each test, TTD_GEN must be even (no session currently active).
        // The @BeforeEach resets it to 0, but sessions run during the test
        // leave TTD_GEN at a non-zero even value (2, 4, ...).  The invariant
        // is "even = no session active", not "0 = pristine".
        assertEquals(0L, Ttd.TTD_GEN % 2,
                "TTD_GEN must be even after each test (no active session); "
                        + "actual=" + Ttd.TTD_GEN);
    }

    // =========================================================================
    // Allocation-tracking helper (reflection-based for Java 17 compatibility)
    // =========================================================================

    /**
     * Wraps {@code com.sun.management.ThreadMXBean} via a {@link MethodHandle}
     * so we avoid both a compile-time dependency on the JDK-internal type and
     * the boxing overhead of {@link Method#invoke} (which would itself
     * allocate a {@code Long} per call, polluting the measurement window).
     *
     * <p>MethodHandle invocations with a primitive return type are unboxed by
     * the JVM before returning to the caller, so {@code allocatedBytes} returns
     * a {@code long} with zero allocation.
     *
     * <p>Returns {@code null} on non-HotSpot JVMs or if allocation tracking
     * is disabled.
     */
    private static final class AllocTracker {
        private final Object mxBean;
        private final MethodHandle getter;  // (Object, long) -> long (unboxed)

        private AllocTracker(Object mxBean, MethodHandle getter) {
            this.mxBean = mxBean;
            this.getter = getter;
        }

        long allocatedBytes(long threadId) {
            try {
                return (long) getter.invokeExact(mxBean, threadId);
            } catch (Throwable e) {
                return -1L;
            }
        }

        static AllocTracker create() {
            ThreadMXBean base = ManagementFactory.getThreadMXBean();
            try {
                Class<?> cls = Class.forName("com.sun.management.ThreadMXBean");
                if (!cls.isInstance(base)) return null;
                // Check support.
                Method isSupported = cls.getMethod("isThreadAllocatedMemorySupported");
                if (!(boolean) isSupported.invoke(base)) return null;
                // Enable.
                Method enable = cls.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
                enable.invoke(base, true);
                // Build a MethodHandle with signature (Object, long) -> long so
                // invokeExact returns a primitive long with zero allocation.
                Method get = cls.getMethod("getThreadAllocatedBytes", long.class);
                get.setAccessible(true);
                MethodHandle mh = MethodHandles.lookup().unreflect(get);
                // Adapt: the receiver type is the concrete class; erase to Object
                // so invokeExact(mxBean, tid) compiles without a cast.
                mh = mh.asType(MethodType.methodType(long.class, Object.class, long.class));
                return new AllocTracker(base, mh);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // =========================================================================
    // 1. Zero-alloc steady state
    // =========================================================================

    /**
     * When {@code TTD_GEN == 0} (pristine — no session has ever fired),
     * {@link Ttd#saveFrame} must allocate ZERO bytes on the calling thread.
     *
     * <p>Measured using {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes}
     * (accessed via reflection for Java 17 source-compat).  Skips if the JVM
     * does not support per-thread allocation tracking.
     */
    @Test
    void saveFrame_allocates_nothing_outside_session() {
        AllocTracker tracker = AllocTracker.create();
        if (tracker == null) return;   // Skip on non-HotSpot JVMs.

        int methodId = Ttd.internMethodId("Zero/alloc.saveFrame()V");
        long[] prims = new long[2];
        Object[] refs = new Object[1];

        // Warm up generously so HotSpot C2-compiles the target method before
        // the measurement window.  Without sufficient warm-up, JIT compilation
        // allocates code-cache and deopt structures mid-loop, inflating the
        // per-thread byte counter.
        for (int i = 0; i < 20_000; i++) {
            Ttd.saveFrame(methodId, i, prims, refs);
        }

        long tid = Thread.currentThread().getId();
        long before = tracker.allocatedBytes(tid);
        for (int i = 0; i < 10_000; i++) {
            Ttd.saveFrame(methodId, i, prims, refs);
        }
        long after = tracker.allocatedBytes(tid);

        long delta = after - before;
        assertEquals(0L, delta,
                "saveFrame with TTD_GEN==0 must allocate 0 bytes; "
                        + "allocated " + delta + " bytes across 10 000 calls");
    }

    /**
     * When {@code TTD_GEN == 0} (pristine), {@link Ttd#popResumeFrame} must
     * allocate ZERO bytes.
     *
     * <p>We avoid JUnit assertions inside the measurement window — the
     * assertion scaffolding may itself allocate.
     */
    @Test
    void popResumeFrame_allocates_nothing_outside_session() {
        AllocTracker tracker = AllocTracker.create();
        if (tracker == null) return;

        int methodId = Ttd.internMethodId("Zero/alloc.popResumeFrame()V");

        // Warm up.
        for (int i = 0; i < 20_000; i++) {
            Ttd.popResumeFrame(methodId);
        }

        long tid = Thread.currentThread().getId();
        // Use a scratch variable to prevent the JIT from eliminating the calls.
        ResumeFrame last = null;
        long before = tracker.allocatedBytes(tid);
        for (int i = 0; i < 10_000; i++) {
            last = Ttd.popResumeFrame(methodId);
        }
        long after = tracker.allocatedBytes(tid);

        long delta = after - before;
        // Correctness check outside the measurement window.
        assertNull(last, "popResumeFrame must return null outside session");
        assertEquals(0L, delta,
                "popResumeFrame with TTD_GEN==0 must allocate 0 bytes; "
                        + "allocated " + delta + " bytes");
    }

    // =========================================================================
    // 2. Pop semantics
    // =========================================================================

    /**
     * Push a frame with methodId=42; popResumeFrame(99) returns null (wrong
     * id); popResumeFrame(42) returns the frame (correct id); a second
     * popResumeFrame(42) returns null (deque now empty).
     */
    @Test
    void popResumeFrame_semantics() {
        Holder root = new Holder();
        List<String> ops = new ArrayList<>();

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            int idA = Ttd.internMethodId("Test/Pop.semantics()V");
            long[] prims = new long[]{10L, 20L};
            Object[] refs = new Object[]{root};

            Ttd.saveFrame(idA, 5, prims, refs);

            // Wrong methodId — peek must leave frame intact.
            int idB = Ttd.internMethodId("Test/Pop.other()V");
            ResumeFrame miss = Ttd.popResumeFrame(idB);
            ops.add(miss == null ? "null" : "hit");

            // Correct methodId — must pop and return.
            ResumeFrame hit = Ttd.popResumeFrame(idA);
            ops.add(hit == null ? "null" : "hit");

            // Deque now empty — another pop must return null.
            ResumeFrame empty = Ttd.popResumeFrame(idA);
            ops.add(empty == null ? "null" : "hit");

            if (hit != null) {
                ops.add("methodId=" + hit.methodId);
                ops.add("bci=" + hit.bci);
                ops.add("prims[0]=" + hit.prims[0]);
                ops.add("refs[0]=" + (hit.refs[0] == root ? "root" : "wrong"));
            }
        });

        int idA = Ttd.internMethodId("Test/Pop.semantics()V");
        assertEquals(List.of("null", "hit", "null",
                "methodId=" + idA,
                "bci=5", "prims[0]=10", "refs[0]=root"), ops,
                "pop semantics failed: " + ops);
    }

    /**
     * Push two frames with different methodIds; verify LIFO order and that
     * each pop only fires for the matching id.
     */
    @Test
    void popResumeFrame_lifo_order() {
        Holder root = new Holder();
        List<String> log = new ArrayList<>();

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            int id1 = Ttd.internMethodId("Test/Lifo.method1()V");
            int id2 = Ttd.internMethodId("Test/Lifo.method2()V");

            Ttd.saveFrame(id1, 10, new long[0], new Object[0]);
            Ttd.saveFrame(id2, 20, new long[0], new Object[0]);

            // Top is id2; pop with id1 must miss.
            log.add("miss1=" + (Ttd.popResumeFrame(id1) == null ? "null" : "hit"));
            // Top is still id2; pop with id2 must hit.
            ResumeFrame f2 = Ttd.popResumeFrame(id2);
            log.add("hit2=bci" + (f2 != null ? f2.bci : "null"));
            // Now top is id1; pop with id1 must hit.
            ResumeFrame f1 = Ttd.popResumeFrame(id1);
            log.add("hit1=bci" + (f1 != null ? f1.bci : "null"));
        });

        assertEquals(List.of("miss1=null", "hit2=bci20", "hit1=bci10"), log,
                "LIFO order failed: " + log);
    }

    // =========================================================================
    // 3. Cross-thread isolation
    // =========================================================================

    /**
     * Two threads each run a session concurrently, push 100 frames each,
     * and verify they pop their own frames without interference from the
     * other thread.
     */
    @Test
    void cross_thread_isolation() throws Exception {
        int frames = 100;
        CountDownLatch bothPushed = new CountDownLatch(2);
        CountDownLatch bothDone = new CountDownLatch(2);
        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        Runnable makeWorker = () -> {
            Holder root = new Holder();
            // Each thread uses a unique method key so ids are distinct.
            String key = "Test/Thread.worker" + Thread.currentThread().getId() + "()V";
            List<Integer> popped = new ArrayList<>();

            Ttd.sessionWithRepl(root, quitRepl(), () -> {
                int myId = Ttd.internMethodId(key);

                for (int i = 0; i < frames; i++) {
                    Ttd.saveFrame(myId, i, new long[0], new Object[0]);
                }
                bothPushed.countDown();
                // Wait for both threads to have pushed all their frames before
                // either thread starts popping, maximising the chance of
                // cross-thread interference if the deque were shared.
                try {
                    bothPushed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Pop all frames and verify they're mine.
                for (int i = 0; i < frames; i++) {
                    ResumeFrame f = Ttd.popResumeFrame(myId);
                    assertNotNull(f, "frame " + i + " must not be null");
                    assertEquals(myId, f.methodId, "frame.methodId must match my id");
                    popped.add(f.bci);
                }
                // Verify LIFO: bci decreases from frames-1 down to 0.
                for (int i = 0; i < frames; i++) {
                    assertEquals(frames - 1 - i, (int) popped.get(i),
                            "LIFO bci mismatch at position " + i);
                }
                // No more frames.
                assertNull(Ttd.popResumeFrame(myId), "deque must be empty after all pops");
                bothDone.countDown();
            });
        };

        Thread t1 = new Thread(() -> {
            try { makeWorker.run(); }
            catch (Throwable t) {
                error1.set(t);
                bothPushed.countDown();
                bothDone.countDown();
            }
        }, "cross-thread-worker-1");
        Thread t2 = new Thread(() -> {
            try { makeWorker.run(); }
            catch (Throwable t) {
                error2.set(t);
                bothPushed.countDown();
                bothDone.countDown();
            }
        }, "cross-thread-worker-2");

        t1.start();
        t2.start();
        bothDone.await();
        t1.join();
        t2.join();

        if (error1.get() != null) throw new AssertionError("Thread 1 failed", error1.get());
        if (error2.get() != null) throw new AssertionError("Thread 2 failed", error2.get());
    }

    // =========================================================================
    // 4. Session-exit cleanup / memory-leak guard
    // =========================================================================

    /**
     * After a session ends (normal exit), the resume deque must be drained.
     * Verified by starting a second session and confirming the deque is empty
     * at its start.
     */
    @Test
    void session_exit_clears_frame_deque() {
        Holder root = new Holder();
        int methodId = Ttd.internMethodId("Test/Cleanup.session()V");

        // First session: push a frame without popping — session exit must drain.
        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            Ttd.saveFrame(methodId, 1, new long[0], new Object[0]);
            // intentionally don't pop
        });

        // Second session: deque must be clean (thread-local removed then
        // re-created by withInitial on the first get inside the new session).
        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            assertNull(Ttd.popResumeFrame(methodId),
                    "deque must be empty at start of new session — clearSessionState fired");
        });
    }

    /**
     * Stronger memory-leak test: after a session ends, a WeakReference to a
     * ResumeFrame that was pushed during the session must be cleared by GC,
     * confirming the frame is not retained on the thread-local.
     */
    @Test
    void frame_not_retained_after_session_exit() throws Exception {
        Holder root = new Holder();
        WeakReference<ResumeFrame> ref = runSessionAndReturnWeakRef(root);

        // Force GC.  Retry a few times to account for generational collectors.
        for (int i = 0; i < 10; i++) {
            System.gc();
            System.runFinalization();
            if (ref.get() == null) break;
            Thread.sleep(50);
        }

        assertNull(ref.get(),
                "ResumeFrame must not be reachable after session exit — "
                        + "thread-local deque was not cleared");
    }

    /**
     * Helper: run a session that pushes a frame, capture a WeakReference to
     * it, and return the reference after the session exits.  Extracted into
     * its own method so the strong local reference to the frame goes out of
     * scope before the caller forces GC.
     */
    @SuppressWarnings("unchecked")
    private static WeakReference<ResumeFrame> runSessionAndReturnWeakRef(Holder root) {
        WeakReference<ResumeFrame>[] weakRef = new WeakReference[1];
        int methodId = Ttd.internMethodId("Test/Leak.check()V");

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            // Push a frame.
            Ttd.saveFrame(methodId, 99, new long[0], new Object[0]);
            // Pop it so we can capture a reference to it.
            ResumeFrame frame = Ttd.popResumeFrame(methodId);
            assertNotNull(frame);
            // Push it back so the session-exit cleanup has something to drain.
            Ttd.saveFrame(methodId, 99, new long[0], new Object[0]);
            weakRef[0] = new WeakReference<>(frame);
        });
        // session has exited — clearSessionState() has run.
        return weakRef[0];
    }

    // =========================================================================
    // 5. Method-id interning
    // =========================================================================

    /** Same key must always return the same id; different keys get different ids. */
    @Test
    void internMethodId_stable_and_distinct() {
        String key1 = "Test/Intern.method1()V";
        String key2 = "Test/Intern.method2(I)Z";

        int id1a = Ttd.internMethodId(key1);
        int id1b = Ttd.internMethodId(key1);
        int id2  = Ttd.internMethodId(key2);

        assertEquals(id1a, id1b, "same key must produce same id");
        assertNotEquals(id1a, id2, "different keys must produce different ids");
        assertTrue(id1a >= 0, "id must be non-negative");
        assertTrue(id2  >= 0, "id must be non-negative");
    }

    /**
     * Interning is thread-safe: 10 threads all intern the same key concurrently;
     * all must receive the same id.
     */
    @Test
    void internMethodId_concurrent_same_key() throws Exception {
        String key = "Test/Concurrent.methodConcurrent()V";
        // Intern once first to establish the canonical id.
        int expected = Ttd.internMethodId(key);

        int N = 10;
        int[] ids = new int[N];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            final int idx = i;
            threads.add(new Thread(() -> ids[idx] = Ttd.internMethodId(key)));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();

        for (int i = 0; i < N; i++) {
            assertEquals(expected, ids[i], "concurrent intern result must match at index " + i);
        }
    }

    // =========================================================================
    // 6. Session lifecycle counter
    // =========================================================================

    @Test
    void session_counter_lifecycle() {
        assertEquals(0L, Ttd.TTD_GEN, "TTD_GEN starts at 0");
        Holder root = new Holder();
        long[] duringSession = new long[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            duringSession[0] = Ttd.TTD_GEN;
        });

        // During session: TTD_GEN is odd (0→1 on entry).
        assertEquals(1L, duringSession[0], "TTD_GEN must be 1 (odd) during first session");
        // After session: TTD_GEN is even (1→2 on exit).
        assertEquals(2L, Ttd.TTD_GEN, "TTD_GEN must be 2 (even) after first session");
        // Reset for AfterEach check.
        Ttd.testSetTtdGen(0L);
    }

    @Test
    void session_counter_decrements_on_exception() {
        Holder root = new Holder();
        // A plain RuntimeException from the body escapes sessionWithRepl
        // (only CpsBackstep and Quit are caught internally).  The finally block
        // must still run and increment TTD_GEN back to even.
        assertThrows(RuntimeException.class, () ->
            Ttd.sessionWithRepl(root, quitRepl(), () -> {
                throw new RuntimeException("test exception");
            }));

        assertEquals(0L, Ttd.TTD_GEN % 2,
                "TTD_GEN must be even even after exceptional session exit");
        Ttd.testSetTtdGen(0L);
    }

    /**
     * Two sequential sessions on the same thread: counter returns to 0
     * between them and the deque from the first session does not leak into
     * the second.
     */
    @Test
    void sequential_sessions_independent() {
        Holder root = new Holder();
        int idFirst  = Ttd.internMethodId("Test/Seq.first()V");
        int idSecond = Ttd.internMethodId("Test/Seq.second()V");

        // First session: push a frame, let exit drain it.
        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            Ttd.saveFrame(idFirst, 1, new long[0], new Object[0]);
        });

        assertEquals(0L, Ttd.TTD_GEN % 2, "TTD_GEN must be even between sessions");

        // Second session: deque must be clean (no leftover from first session).
        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            assertNull(Ttd.popResumeFrame(idFirst),
                    "first session's frames must not leak into second session");
            Ttd.saveFrame(idSecond, 2, new long[0], new Object[0]);
            ResumeFrame f = Ttd.popResumeFrame(idSecond);
            assertNotNull(f, "second session frame must be present");
            assertEquals(2, f.bci, "second session frame bci must match");
        });
    }
}
