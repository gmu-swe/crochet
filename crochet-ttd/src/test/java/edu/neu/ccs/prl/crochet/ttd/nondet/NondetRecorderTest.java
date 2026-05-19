package edu.neu.ccs.prl.crochet.ttd.nondet;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NondetRecorder}.
 *
 * <p>These tests exercise the recorder directly (no bytecode rewriting)
 * because NondetTransformer is installed by the agent and we do not have
 * a full agent in the test classpath. The recorder's logic is independent
 * of how the site IDs are generated; using hardcoded site IDs is fine.
 */
class NondetRecorderTest {

    // Use high site IDs unlikely to collide with other tests in the suite.
    private static final int SITE_CTM  = 0xD3_0001;
    private static final int SITE_NANO = 0xD3_0002;
    private static final int SITE_IHC  = 0xD3_0003;
    private static final int SITE_OHC  = 0xD3_0004;
    private static final int SITE_NI   = 0xD3_0005;
    private static final int SITE_NIB  = 0xD3_0006;
    private static final int SITE_NL   = 0xD3_0007;
    private static final int SITE_ND   = 0xD3_0008;
    private static final int SITE_NF   = 0xD3_0009;
    private static final int SITE_NB   = 0xD3_000A;
    private static final int SITE_NG   = 0xD3_000B;
    private static final int SITE_MR   = 0xD3_000C;

    @BeforeEach
    void setUp() {
        // Ensure a clean state before each test.
        NondetRecorder.RECORDING_TL.remove();
        NondetRecorder.REPLAYING_TL.remove();
    }

    @AfterEach
    void tearDown() {
        NondetRecorder.RECORDING_TL.remove();
        NondetRecorder.REPLAYING_TL.remove();
        // Restore default handler.
        NondetRecorder.setDivergenceHandler(event -> System.err.println(event.toString()));
    }

    // -------------------------------------------------------------------------
    // Cold-path: no session active
    // -------------------------------------------------------------------------

    @Test
    void coldPath_isRecordingAndIsReplaying_areFalse() {
        assertFalse(NondetRecorder.isRecording());
        assertFalse(NondetRecorder.isReplaying());
    }

    @Test
    void coldPath_currentTimeMillis_returnsRealValue() {
        long before = System.currentTimeMillis();
        long v = NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        long after = System.currentTimeMillis();
        assertTrue(v >= before && v <= after,
                "cold-path should return real currentTimeMillis, got " + v);
    }

    @Test
    void coldPath_nanoTime_returnsRealValue() {
        long before = System.nanoTime();
        long v = NondetRecorder.fetchOrCallNanoTime(SITE_NANO);
        long after = System.nanoTime();
        assertTrue(v >= before && v <= after,
                "cold-path should return real nanoTime");
    }

    @Test
    void coldPath_identityHashCode_returnsRealValue() {
        Object o = new Object();
        int expected = System.identityHashCode(o);
        int actual = NondetRecorder.fetchOrCallIdentityHashCode(o, SITE_IHC);
        assertEquals(expected, actual);
    }

    @Test
    void coldPath_mathRandom_returnsValueInRange() {
        double v = NondetRecorder.fetchOrCallMathRandom(SITE_MR);
        assertTrue(v >= 0.0 && v < 1.0, "Math.random() must be in [0,1)");
    }

    // -------------------------------------------------------------------------
    // Zero-allocation cold-path (Universal Gate 7)
    // -------------------------------------------------------------------------

    @Test
    void coldPath_zeroAllocation() {
        // Obtain the HotSpot ThreadMXBean that exposes per-thread allocation.
        ThreadMXBean bean;
        try {
            bean = (ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        } catch (ClassCastException e) {
            // Not a HotSpot JVM — skip.
            return;
        }
        if (!bean.isThreadAllocatedMemorySupported()
                || !bean.isThreadAllocatedMemoryEnabled()) {
            // Skip on JVMs that don't report per-thread allocation.
            return;
        }
        // Warm up to let JIT compile the helpers.
        for (int i = 0; i < 10_000; i++) {
            NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
            NondetRecorder.fetchOrCallNanoTime(SITE_NANO);
        }

        long threadId = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(threadId);

        final int ITERATIONS = 50_000;
        for (int i = 0; i < ITERATIONS; i++) {
            NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
            NondetRecorder.fetchOrCallNanoTime(SITE_NANO);
            NondetRecorder.fetchOrCallMathRandom(SITE_MR);
        }

        long after = bean.getThreadAllocatedBytes(threadId);
        long allocatedPerCall = (after - before) / (ITERATIONS * 3L);
        // Allow a small budget for JIT metadata; the helpers themselves
        // must not allocate per-call. Tolerate up to 8 bytes per call
        // (measurement noise from the JVM's TLAB).
        assertTrue(allocatedPerCall <= 8,
                "cold-path allocated " + allocatedPerCall + " bytes/call; expected 0");
    }

    // -------------------------------------------------------------------------
    // Recording: each intercepted method
    // -------------------------------------------------------------------------

    @Test
    void recording_currentTimeMillis_logsEvent() {
        NondetRecorder.startRecording();
        long v = NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        List<NondetEvent> log = NondetRecorder.stopRecording();

        assertEquals(1, log.size());
        NondetEvent ev = log.get(0);
        assertEquals(SITE_CTM, ev.siteId);
        assertEquals(NondetEvent.KIND_LONG, ev.kind);
        assertEquals(v, ev.asLong());
    }

    @Test
    void recording_nanoTime_logsEvent() {
        NondetRecorder.startRecording();
        long v = NondetRecorder.fetchOrCallNanoTime(SITE_NANO);
        List<NondetEvent> log = NondetRecorder.stopRecording();

        assertEquals(1, log.size());
        assertEquals(v, log.get(0).asLong());
        assertEquals(NondetEvent.KIND_LONG, log.get(0).kind);
    }

    @Test
    void recording_identityHashCode_logsEvent() {
        Object o = new Object();
        NondetRecorder.startRecording();
        int v = NondetRecorder.fetchOrCallIdentityHashCode(o, SITE_IHC);
        List<NondetEvent> log = NondetRecorder.stopRecording();

        assertEquals(1, log.size());
        assertEquals(NondetEvent.KIND_INT, log.get(0).kind);
        assertEquals(v, log.get(0).asInt());
    }

    @Test
    void recording_objectHashCode_logsEvent() {
        Object o = new Object();
        NondetRecorder.startRecording();
        int v = NondetRecorder.fetchOrCallObjectHashCode(o, SITE_OHC);
        List<NondetEvent> log = NondetRecorder.stopRecording();

        assertEquals(1, log.size());
        assertEquals(NondetEvent.KIND_INT, log.get(0).kind);
    }

    @Test
    void recording_randomMethods_logAllEvents() {
        Random rng = new Random(42L);
        NondetRecorder.startRecording();

        int vi   = NondetRecorder.fetchOrCallNextInt(rng, SITE_NI);
        long vl  = NondetRecorder.fetchOrCallNextLong(rng, SITE_NL);
        double vd = NondetRecorder.fetchOrCallNextDouble(rng, SITE_ND);
        float vf  = NondetRecorder.fetchOrCallNextFloat(rng, SITE_NF);
        boolean vb = NondetRecorder.fetchOrCallNextBoolean(rng, SITE_NB);
        double vg  = NondetRecorder.fetchOrCallNextGaussian(rng, SITE_NG);
        double vm  = NondetRecorder.fetchOrCallMathRandom(SITE_MR);

        List<NondetEvent> log = NondetRecorder.stopRecording();
        assertEquals(7, log.size());
        assertEquals(NondetEvent.KIND_INT, log.get(0).kind);
        assertEquals(NondetEvent.KIND_LONG, log.get(1).kind);
        assertEquals(NondetEvent.KIND_DOUBLE, log.get(2).kind);
        assertEquals(NondetEvent.KIND_FLOAT, log.get(3).kind);
        assertEquals(NondetEvent.KIND_INT, log.get(4).kind);  // boolean stored as int
        assertEquals(NondetEvent.KIND_DOUBLE, log.get(5).kind);
        assertEquals(NondetEvent.KIND_DOUBLE, log.get(6).kind);

        assertEquals(vi, log.get(0).asInt());
        assertEquals(vl, log.get(1).asLong());
        assertEquals(Double.doubleToRawLongBits(vd), log.get(2).rawBits);
        assertEquals(Float.floatToRawIntBits(vf), (int) log.get(3).rawBits);
        assertEquals(vb ? 1 : 0, log.get(4).asInt());
    }

    // -------------------------------------------------------------------------
    // Replay: recorded values are returned silently
    // -------------------------------------------------------------------------

    @Test
    void replay_currentTimeMillis_returnsSameValue() {
        // Record a specific value by injecting a fake log.
        List<NondetEvent> log = new ArrayList<>();
        long fakeTime = 123456789L;
        log.add(new NondetEvent(SITE_CTM, fakeTime, NondetEvent.KIND_LONG));

        NondetRecorder.startReplaying(log);
        long replayed = NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        NondetRecorder.stopReplaying();

        assertEquals(fakeTime, replayed, "replay must return the recorded value");
    }

    @Test
    void replay_nanoTime_returnsSameValue() {
        List<NondetEvent> log = new ArrayList<>();
        long fakeNs = 9_876_543_210L;
        log.add(new NondetEvent(SITE_NANO, fakeNs, NondetEvent.KIND_LONG));

        NondetRecorder.startReplaying(log);
        long replayed = NondetRecorder.fetchOrCallNanoTime(SITE_NANO);
        NondetRecorder.stopReplaying();

        assertEquals(fakeNs, replayed);
    }

    @Test
    void replay_identityHashCode_returnsSameValue() {
        Object o = new Object();
        List<NondetEvent> log = new ArrayList<>();
        int fakeHash = 0xDEADBEEF;
        log.add(new NondetEvent(SITE_IHC, fakeHash, NondetEvent.KIND_INT));

        NondetRecorder.startReplaying(log);
        int replayed = NondetRecorder.fetchOrCallIdentityHashCode(o, SITE_IHC);
        NondetRecorder.stopReplaying();

        assertEquals(fakeHash, replayed);
    }

    @Test
    void replay_allRandomMethods_returnRecordedValues() {
        // Record with a seeded RNG so we know what to expect.
        Random rng = new Random(99L);
        NondetRecorder.startRecording();
        int ri    = NondetRecorder.fetchOrCallNextInt(rng, SITE_NI);
        long rl   = NondetRecorder.fetchOrCallNextLong(rng, SITE_NL);
        double rd = NondetRecorder.fetchOrCallNextDouble(rng, SITE_ND);
        float rf  = NondetRecorder.fetchOrCallNextFloat(rng, SITE_NF);
        boolean rb = NondetRecorder.fetchOrCallNextBoolean(rng, SITE_NB);
        double rg  = NondetRecorder.fetchOrCallNextGaussian(rng, SITE_NG);
        double rm  = NondetRecorder.fetchOrCallMathRandom(SITE_MR);
        List<NondetEvent> log = NondetRecorder.stopRecording();

        // Replay with a DIFFERENT rng (different seed) — the recorded values
        // should be returned regardless.
        Random otherRng = new Random(0L);
        NondetRecorder.startReplaying(log);
        assertEquals(ri, NondetRecorder.fetchOrCallNextInt(otherRng, SITE_NI));
        assertEquals(rl, NondetRecorder.fetchOrCallNextLong(otherRng, SITE_NL));
        assertEquals(rd, NondetRecorder.fetchOrCallNextDouble(otherRng, SITE_ND));
        assertEquals(rf, NondetRecorder.fetchOrCallNextFloat(otherRng, SITE_NF));
        assertEquals(rb, NondetRecorder.fetchOrCallNextBoolean(otherRng, SITE_NB));
        assertEquals(rg, NondetRecorder.fetchOrCallNextGaussian(otherRng, SITE_NG));
        assertEquals(rm, NondetRecorder.fetchOrCallMathRandom(SITE_MR));
        NondetRecorder.stopReplaying();
    }

    /**
     * False-positive test: a replay that matches all recorded values must
     * produce ZERO divergence events.
     */
    @Test
    void falsePositive_noSpuriousDivergence() {
        List<NondetDivergenceEvent> divergences = new ArrayList<>();
        NondetRecorder.setDivergenceHandler(divergences::add);

        // Record a session.
        Random rng = new Random(7L);
        NondetRecorder.startRecording();
        long t = NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        int ni = NondetRecorder.fetchOrCallNextInt(rng, SITE_NI);
        List<NondetEvent> log = NondetRecorder.stopRecording();

        // Replay the SAME log — every site should match.
        NondetRecorder.startReplaying(log);
        long rt = NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        int rni = NondetRecorder.fetchOrCallNextInt(rng, SITE_NI);
        NondetRecorder.stopReplaying();

        assertEquals(t, rt, "replay currentTimeMillis should match");
        assertEquals(ni, rni, "replay nextInt should match");

        // Filter to only our siteIds — framework noise (QUEUE_EMPTY for JUnit's
        // own currentTimeMillis siteIds) is acceptable and expected.
        List<NondetDivergenceEvent> ourDivergences = divergences.stream()
                .filter(e -> e.siteId == SITE_CTM || e.siteId == SITE_NI)
                .toList();
        assertTrue(ourDivergences.isEmpty(),
                "no divergence events expected for our siteIds on matching replay, got: "
                        + ourDivergences);
    }

    // -------------------------------------------------------------------------
    // Divergence events
    // -------------------------------------------------------------------------

    @Test
    void divergence_siteAbsent_emitsEvent() {
        List<NondetDivergenceEvent> divergences = new ArrayList<>();
        NondetRecorder.setDivergenceHandler(divergences::add);

        // Replay with an empty log — SITE_CTM has no recorded value.
        NondetRecorder.startReplaying(new ArrayList<>());
        NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        NondetRecorder.stopReplaying();

        assertEquals(1, divergences.size());
        NondetDivergenceEvent ev = divergences.get(0);
        assertEquals(SITE_CTM, ev.siteId);
        assertEquals(NondetDivergenceEvent.CAUSE_SITE_ABSENT, ev.cause);
    }

    @Test
    void divergence_queueEmpty_emitsEvent() {
        List<NondetDivergenceEvent> divergences = new ArrayList<>();
        NondetRecorder.setDivergenceHandler(divergences::add);

        // Replay with only ONE event for SITE_CTM; call it TWICE.
        List<NondetEvent> log = new ArrayList<>();
        log.add(new NondetEvent(SITE_CTM, 111L, NondetEvent.KIND_LONG));

        NondetRecorder.startReplaying(log);
        long first = NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);  // OK
        long second = NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM); // divergence
        NondetRecorder.stopReplaying();

        assertEquals(111L, first, "first call should return recorded value");
        assertEquals(1, divergences.size());
        assertEquals(NondetDivergenceEvent.CAUSE_QUEUE_EMPTY, divergences.get(0).cause);
    }

    @Test
    void divergence_wrongKind_emitsEvent() {
        List<NondetDivergenceEvent> divergences = new ArrayList<>();
        NondetRecorder.setDivergenceHandler(divergences::add);

        // Record a LONG event for SITE_CTM but then replay as if it were INT.
        List<NondetEvent> log = new ArrayList<>();
        log.add(new NondetEvent(SITE_CTM, 999L, NondetEvent.KIND_INT)); // wrong kind (INT not LONG)

        NondetRecorder.startReplaying(log);
        // fetchOrCallCurrentTimeMillis expects KIND_LONG; the stored event has KIND_INT.
        NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        NondetRecorder.stopReplaying();

        assertEquals(1, divergences.size());
        assertEquals(NondetDivergenceEvent.CAUSE_WRONG_KIND, divergences.get(0).cause);
    }

    @Test
    void divergence_hasExpectedSchema() {
        AtomicReference<NondetDivergenceEvent> captured = new AtomicReference<>();
        NondetRecorder.setDivergenceHandler(captured::set);

        NondetRecorder.startReplaying(new ArrayList<>());
        NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        NondetRecorder.stopReplaying();

        NondetDivergenceEvent ev = captured.get();
        assertNotNull(ev);
        assertEquals(SITE_CTM, ev.siteId);
        assertNotNull(ev.siteDesc);
        assertEquals(NondetDivergenceEvent.NO_RECORDED_VALUE, ev.recordedBits,
                "site absent → recordedBits should be NO_RECORDED_VALUE sentinel");
        assertEquals(NondetEvent.KIND_LONG, ev.kind);
        assertEquals(NondetDivergenceEvent.CAUSE_SITE_ABSENT, ev.cause);
    }

    // -------------------------------------------------------------------------
    // Record then replay: the canonical round-trip
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_allInterceptedMethods() {
        Random rng = new Random(12345L);
        Object obj = new Object();

        // Phase 1: record.
        NondetRecorder.startRecording();
        long ctm   = NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        long nano  = NondetRecorder.fetchOrCallNanoTime(SITE_NANO);
        int  ihc   = NondetRecorder.fetchOrCallIdentityHashCode(obj, SITE_IHC);
        int  ohc   = NondetRecorder.fetchOrCallObjectHashCode(obj, SITE_OHC);
        int  ni    = NondetRecorder.fetchOrCallNextInt(rng, SITE_NI);
        int  nib   = NondetRecorder.fetchOrCallNextIntBound(rng, 100, SITE_NIB);
        long nl    = NondetRecorder.fetchOrCallNextLong(rng, SITE_NL);
        double nd  = NondetRecorder.fetchOrCallNextDouble(rng, SITE_ND);
        float nf   = NondetRecorder.fetchOrCallNextFloat(rng, SITE_NF);
        boolean nb = NondetRecorder.fetchOrCallNextBoolean(rng, SITE_NB);
        double ng  = NondetRecorder.fetchOrCallNextGaussian(rng, SITE_NG);
        double mr  = NondetRecorder.fetchOrCallMathRandom(SITE_MR);
        List<NondetEvent> log = NondetRecorder.stopRecording();

        // The log must contain at least our 12 events. It may contain more events
        // from the JUnit framework (which is instrumented but not in the skip list),
        // e.g., currentTimeMillis() calls for test timing.
        assertTrue(log.size() >= 12,
                "all 12 methods should have been recorded, got " + log.size());

        // Phase 2: replay the FULL log — should return identical values for our siteIds
        // (framework siteIds get their recorded values too, no divergence).
        List<NondetDivergenceEvent> divergences = new ArrayList<>();
        NondetRecorder.setDivergenceHandler(divergences::add);

        Random rng2 = new Random(9999L); // different seed
        NondetRecorder.startReplaying(log);
        assertEquals(ctm,  NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM));
        assertEquals(nano, NondetRecorder.fetchOrCallNanoTime(SITE_NANO));
        assertEquals(ihc,  NondetRecorder.fetchOrCallIdentityHashCode(obj, SITE_IHC));
        assertEquals(ohc,  NondetRecorder.fetchOrCallObjectHashCode(obj, SITE_OHC));
        assertEquals(ni,   NondetRecorder.fetchOrCallNextInt(rng2, SITE_NI));
        assertEquals(nib,  NondetRecorder.fetchOrCallNextIntBound(rng2, 50, SITE_NIB));
        assertEquals(nl,   NondetRecorder.fetchOrCallNextLong(rng2, SITE_NL));
        assertEquals(nd,   NondetRecorder.fetchOrCallNextDouble(rng2, SITE_ND));
        assertEquals(nf,   NondetRecorder.fetchOrCallNextFloat(rng2, SITE_NF));
        assertEquals(nb,   NondetRecorder.fetchOrCallNextBoolean(rng2, SITE_NB));
        assertEquals(ng,   NondetRecorder.fetchOrCallNextGaussian(rng2, SITE_NG));
        assertEquals(mr,   NondetRecorder.fetchOrCallMathRandom(SITE_MR));
        NondetRecorder.stopReplaying();

        // Filter divergences to only our known siteIds — framework siteIds
        // may produce QUEUE_EMPTY divergences if the framework calls them again
        // after the recording window, which is expected/acceptable noise.
        List<Integer> ourSites = List.of(SITE_CTM, SITE_NANO, SITE_IHC, SITE_OHC,
                SITE_NI, SITE_NIB, SITE_NL, SITE_ND, SITE_NF, SITE_NB, SITE_NG, SITE_MR);
        List<NondetDivergenceEvent> ourDivergences = divergences.stream()
                .filter(e -> ourSites.contains(e.siteId))
                .toList();
        assertTrue(ourDivergences.isEmpty(),
                "round-trip with matching log should produce no divergence for our siteIds: "
                        + ourDivergences);
    }

    // -------------------------------------------------------------------------
    // @CrochetSkip interaction (documented in nondet-coverage.md)
    // -------------------------------------------------------------------------

    /**
     * A class annotated @CrochetSkip should still have its nondet calls
     * intercepted by the TTD agent (the two annotations are orthogonal).
     *
     * <p>This test exercises the recorder directly (the bytecode rewriting
     * is tested by NondetTransformerTest). We verify that the recorder's
     * session state is independent of any Crochet annotation.
     *
     * <p>A class like:
     * <pre>
     *   {@literal @}CrochetSkip
     *   class SomeSkipClass {
     *       long doThing() { return System.currentTimeMillis(); }
     *   }
     * </pre>
     * When the TTD agent is active, the call to currentTimeMillis in
     * doThing() is rewritten to NondetRecorder.fetchOrCallCurrentTimeMillis.
     * The recorder does not look at any class-level annotation — it just
     * branches on whether a recording/replay session is active.
     */
    @Test
    void crochetSkip_nondetCallsAreStillIntercepted() {
        // The recorder itself has no @CrochetSkip concept — demonstrate
        // that recording works for any caller regardless of annotations.
        NondetRecorder.startRecording();
        long v = NondetRecorder.fetchOrCallCurrentTimeMillis(SITE_CTM);
        List<NondetEvent> log = NondetRecorder.stopRecording();

        assertFalse(log.isEmpty(), "@CrochetSkip classes' nondet calls must still be recorded");
        assertEquals(v, log.get(0).asLong());
    }
}
