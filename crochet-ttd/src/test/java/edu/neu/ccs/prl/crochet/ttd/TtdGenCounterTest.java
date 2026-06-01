package edu.neu.ccs.prl.crochet.ttd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C.1 unit tests for {@link Ttd#TTD_GEN}: parity-encoded generation counter.
 *
 * <p><b>Coverage:</b>
 * <ul>
 *   <li>Initial value is 0 (pristine, no session has ever fired).</li>
 *   <li>TTD_GEN is odd during a session (even→odd on entry).</li>
 *   <li>TTD_GEN is even and ≥ 2 after a session (odd→even on exit).</li>
 *   <li>TTD_GEN increments by 2 across N sequential sessions.</li>
 *   <li>Exceptional body exit still performs the odd→even transition.</li>
 *   <li>Reentrancy rejection (session-inside-session) still holds.</li>
 *   <li>Zero-alloc steady state: saveFrame/popResumeFrame allocate 0 bytes
 *       when {@code TTD_GEN == 0}.</li>
 * </ul>
 */
class TtdGenCounterTest {

    // =========================================================================
    // Helpers
    // =========================================================================

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
        // Defensive reset: if a previous test leaked TTD_GEN, restore pristine.
        Ttd.testSetTtdGen(0L);
    }

    @AfterEach
    void checkGenZero() {
        // After reset+test, we force 0 in @BeforeEach; any test that modifies
        // TTD_GEN must reset it before returning.  This final check catches leaks.
        assertEquals(0L, Ttd.TTD_GEN,
                "TTD_GEN must be 0 after each test (reset to 0 by test cleanup); "
                        + "actual=" + Ttd.TTD_GEN);
    }

    // =========================================================================
    // 1. Initial value
    // =========================================================================

    @Test
    void ttdGen_starts_at_zero() {
        // @BeforeEach already reset to 0; this test confirms the reset is effective.
        assertEquals(0L, Ttd.TTD_GEN,
                "TTD_GEN must be 0 before any session (pristine)");
    }

    // =========================================================================
    // 2. Odd during session, even after
    // =========================================================================

    @Test
    void ttdGen_odd_during_session_even_after() {
        Holder root = new Holder();
        long[] duringSession = new long[1];

        Ttd.sessionWithRepl(root, quitRepl(), () -> {
            duringSession[0] = Ttd.TTD_GEN;
        });

        // During session: 0 → 1 on entry (odd).
        assertEquals(1L, duringSession[0],
                "TTD_GEN must be 1 (odd) during the first session");
        // After session: 1 → 2 on exit (even, ≥ 2).
        assertEquals(2L, Ttd.TTD_GEN,
                "TTD_GEN must be 2 (even) after the first session");
        // Reset for @AfterEach.
        Ttd.testSetTtdGen(0L);
    }

    // =========================================================================
    // 3. Monotone increment across N sessions
    // =========================================================================

    @Test
    void ttdGen_increments_by_two_per_session() {
        Holder root = new Holder();
        int N = 5;
        for (int i = 0; i < N; i++) {
            final int idx = i;
            long[] duringSession = new long[1];
            Ttd.sessionWithRepl(root, quitRepl(), () -> {
                duringSession[0] = Ttd.TTD_GEN;
            });
            // After session i+1 (0-indexed): TTD_GEN = 2*(i+1)
            assertEquals(2L * (idx + 1), Ttd.TTD_GEN,
                    "After session " + (idx + 1) + " TTD_GEN must be " + 2 * (idx + 1));
            // During session i+1: TTD_GEN = 2*i + 1 (odd)
            assertEquals(2L * idx + 1L, duringSession[0],
                    "During session " + (idx + 1) + " TTD_GEN must be " + (2 * idx + 1));
        }
        // Reset for @AfterEach.
        Ttd.testSetTtdGen(0L);
    }

    // =========================================================================
    // 4. Exceptional exit still transitions odd→even
    // =========================================================================

    @Test
    void ttdGen_even_after_exceptional_session_exit() {
        Holder root = new Holder();
        assertThrows(RuntimeException.class, () ->
            Ttd.sessionWithRepl(root, quitRepl(), () -> {
                throw new RuntimeException("deliberate");
            }));

        // TTD_GEN must be even (odd→even in finally block).
        assertEquals(0L, Ttd.TTD_GEN % 2,
                "TTD_GEN must be even after exceptional session exit; actual=" + Ttd.TTD_GEN);
        // Reset for @AfterEach.
        Ttd.testSetTtdGen(0L);
    }

    // =========================================================================
    // 5. Reentrancy rejection still holds
    // =========================================================================

    @Test
    void ttdGen_reentrancy_is_rejected() {
        Holder root = new Holder();
        long[] genAtOuter = new long[1];
        long[] genAtNested = new long[1];

        assertThrows(IllegalStateException.class, () ->
            Ttd.sessionWithRepl(root, quitRepl(), () -> {
                genAtOuter[0] = Ttd.TTD_GEN;
                // Nested session: must throw before incrementing TTD_GEN again.
                Ttd.sessionWithRepl(root, quitRepl(), () -> {
                    genAtNested[0] = Ttd.TTD_GEN;
                });
            }));

        // Outer session entry: TTD_GEN went 0→1.
        assertEquals(1L, genAtOuter[0], "outer session TTD_GEN must be 1");
        // Nested attempt is rejected before any further increment.
        assertEquals(0L, genAtNested[0], "nested session body must not run");
        // Reset for @AfterEach.
        Ttd.testSetTtdGen(0L);
    }

    // =========================================================================
    // 6. Zero-alloc steady state (TTD_GEN == 0)
    // =========================================================================

    /**
     * Wraps {@code com.sun.management.ThreadMXBean} via reflection for Java 17
     * source compatibility.
     */
    private static final class AllocTracker {
        private final Object mxBean;
        private final MethodHandle getter;

        AllocTracker(Object mxBean, MethodHandle getter) {
            this.mxBean = mxBean;
            this.getter = getter;
        }

        long allocatedBytes(long threadId) {
            try { return (long) getter.invokeExact(mxBean, threadId); }
            catch (Throwable e) { return -1L; }
        }

        static AllocTracker create() {
            ThreadMXBean base = ManagementFactory.getThreadMXBean();
            try {
                Class<?> cls = Class.forName("com.sun.management.ThreadMXBean");
                if (!cls.isInstance(base)) return null;
                Method isSupported = cls.getMethod("isThreadAllocatedMemorySupported");
                if (!(boolean) isSupported.invoke(base)) return null;
                Method enable = cls.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
                enable.invoke(base, true);
                Method get = cls.getMethod("getThreadAllocatedBytes", long.class);
                get.setAccessible(true);
                MethodHandle mh = MethodHandles.lookup().unreflect(get)
                        .asType(MethodType.methodType(long.class, Object.class, long.class));
                return new AllocTracker(base, mh);
            } catch (Exception e) { return null; }
        }
    }

    @Test
    void saveFrame_zero_alloc_when_ttdGen_zero() {
        AllocTracker tracker = AllocTracker.create();
        if (tracker == null) return;  // Skip on non-HotSpot.

        int methodId = Ttd.internMethodId("TtdGen/zero.saveFrame()V");
        long[] prims = new long[2];
        Object[] refs = new Object[1];

        // Warm up to get C2 compilation before measurement.
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

    @Test
    void popResumeFrame_zero_alloc_when_ttdGen_zero() {
        AllocTracker tracker = AllocTracker.create();
        if (tracker == null) return;

        int methodId = Ttd.internMethodId("TtdGen/zero.popResumeFrame()V");

        for (int i = 0; i < 20_000; i++) {
            Ttd.popResumeFrame(methodId);
        }

        long tid = Thread.currentThread().getId();
        ResumeFrame last = null;
        long before = tracker.allocatedBytes(tid);
        for (int i = 0; i < 10_000; i++) {
            last = Ttd.popResumeFrame(methodId);
        }
        long after = tracker.allocatedBytes(tid);
        long delta = after - before;

        assertNull(last, "popResumeFrame must return null when TTD_GEN==0");
        assertEquals(0L, delta,
                "popResumeFrame with TTD_GEN==0 must allocate 0 bytes; "
                        + "allocated " + delta + " bytes");
    }
}
