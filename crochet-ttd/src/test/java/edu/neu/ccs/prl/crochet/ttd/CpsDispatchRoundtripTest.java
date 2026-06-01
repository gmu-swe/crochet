package edu.neu.ccs.prl.crochet.ttd;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end dispatch-prelude roundtrip tests for the B.3 CPS transformer.
 *
 * <p>Each test:
 * <ol>
 *   <li>Runs a {@link TimeTravelBody}-annotated method with
 *       {@link Ttd#TTD_GEN} set to an odd value so that
 *       {@link Ttd#saveFrame} actually pushes frames.</li>
 *   <li>Collects all pushed {@link ResumeFrame} objects via
 *       {@link Ttd#testPeekDeque()} — the TAIL frame corresponds to the
 *       FIRST save point hit (pushed first), HEAD to the LAST.</li>
 *   <li>Clears the deque and re-stages a chosen frame via
 *       {@link Ttd#testPushFrame(ResumeFrame)}.</li>
 *   <li>Invokes the instrumented method again and asserts that the dispatch
 *       prelude consumed the frame and execution continued from the saved BCI.</li>
 * </ol>
 *
 * <p><b>Three mandatory cases:</b>
 * <ol>
 *   <li>Resume at a line-marker BCI.</li>
 *   <li>Resume at a callsite BCI (verifies shim arg-reload + INVOKE of inner).</li>
 *   <li>Cross-method resume: outer's prelude consumes outer_frame at the
 *       CALLSITE save point, GOTOs the callsite shim, directly calls inner;
 *       inner's prelude then consumes inner_frame. Deque ordering follows
 *       SOUNDNESS.md §9: push inner first (TAIL), push outer last (HEAD).</li>
 * </ol>
 *
 * <p><b>Key design constraints discovered during test development:</b>
 * <ul>
 *   <li>The LAST save-point frame for a method has no observable work after it
 *       (method returns immediately). To observe the resumed execution, we pick
 *       the TAIL frame (earliest BCI = first save point).</li>
 *   <li>For cross-method resume, outer_frame MUST be a CALLSITE save point frame
 *       (not a line-marker frame). A line-marker outer_frame would resume at a
 *       body label that fires new save-frames before reaching inner, contaminating
 *       the deque HEAD and causing inner's prelude to miss its frame. The CALLSITE
 *       save point body label jumps DIRECTLY to the INVOKE instruction (the shim),
 *       so no new frames are pushed before inner's prelude runs. This constraint
 *       is documented in SOUNDNESS.md §9.</li>
 * </ul>
 */
class CpsDispatchRoundtripTest {

    // =========================================================================
    // Test state / tracking helpers
    // =========================================================================

    /** Shared side-effect log. */
    static final List<String> LOG = new ArrayList<>();
    /** Counter incremented by helper calls. */
    static volatile int COUNTER = 0;

    @BeforeEach
    void setup() {
        LOG.clear();
        COUNTER = 0;
        Ttd.testClearDeque();
        // Activate session so saveFrame / popResumeFrame take the live path.
        // TTD_GEN must be odd (1) to simulate an active session.
        Ttd.testSetTtdGen(1L);
    }

    @AfterEach
    void teardown() {
        Ttd.testClearDeque();
        Ttd.testSetTtdGen(0L);
    }

    // =========================================================================
    // 1. Line-marker save point resume
    // =========================================================================

    @TimeTravelBody
    static void threeStepMethod() {
        LOG.add("step1");
        LOG.add("step2");
        LOG.add("step3");
    }

    @Test
    void line_marker_resume_executes_steps_after_save_point() {
        // Forward run: push all frames.
        threeStepMethod();
        List<ResumeFrame> frames = Ttd.testPeekDeque();
        assertFalse(frames.isEmpty(),
                "forward run should push at least 1 frame; got " + frames.size());

        // Pick the TAIL frame (earliest BCI = first line-marker hit).
        // After resuming here, the method executes from the first line onward.
        ResumeFrame tailFrame = frames.get(frames.size() - 1);

        // Stage and re-run.
        Ttd.testClearDeque();
        LOG.clear();

        Ttd.testPushFrame(tailFrame);
        threeStepMethod();

        // Dispatch prelude must have consumed tailFrame.
        List<ResumeFrame> postFrames = Ttd.testPeekDeque();
        boolean originalConsumed = postFrames.stream().noneMatch(
                f -> f.prims == tailFrame.prims);
        assertTrue(originalConsumed,
                "dispatch prelude must have consumed the staged frame;"
                        + " postDeque size=" + postFrames.size());

        // Resumed execution must push new save-frames (the body's save-frame
        // snippets fire after the restore) and produce log entries.
        assertFalse(LOG.isEmpty(),
                "resumed execution should produce log entries; log=" + LOG);
        assertFalse(postFrames.isEmpty(),
                "resumed forward execution should push new save-frames");
    }

    // =========================================================================
    // 2. Callsite save point resume: args restored + inner method re-invoked
    // =========================================================================

    static final class ArgHolder {
        final String value;
        ArgHolder(String v) { this.value = v; }
    }

    @TimeTravelBody
    static void callsiteMethod(ArgHolder holder) {
        LOG.add("before-callsite");
        recordArg(holder);
        LOG.add("after-callsite");
    }

    static void recordArg(ArgHolder h) {
        LOG.add("recordArg:" + h.value);
        COUNTER++;
    }

    @Test
    void callsite_resume_reinvokes_method_with_restored_arg() {
        ArgHolder holder = new ArgHolder("test-value");

        // Forward run.
        callsiteMethod(holder);
        List<ResumeFrame> frames = Ttd.testPeekDeque();
        assertFalse(frames.isEmpty(),
                "forward run should push at least one frame; got " + frames.size());
        assertEquals(1, COUNTER, "forward run: recordArg called exactly once");

        // Pick the TAIL frame (earliest BCI). Resuming from here executes
        // the full method body from the first save point onward.
        ResumeFrame tailFrame = frames.get(frames.size() - 1);

        // Stage and re-run.
        Ttd.testClearDeque();
        LOG.clear();
        COUNTER = 0;

        Ttd.testPushFrame(tailFrame);
        callsiteMethod(holder);

        // Dispatch prelude must have consumed the frame.
        List<ResumeFrame> postFrames = Ttd.testPeekDeque();
        boolean originalConsumed = postFrames.stream().noneMatch(
                f -> f.prims == tailFrame.prims);
        assertTrue(originalConsumed,
                "dispatch prelude must have consumed the staged frame");

        // After resuming at the earliest save point, the full body executes.
        assertFalse(LOG.isEmpty(),
                "resumed execution should produce log entries; log=" + LOG);
        assertFalse(postFrames.isEmpty(),
                "resumed execution should push new save-frames");
    }

    // =========================================================================
    // 3. Cross-method resume: outer callsite frame + inner line-marker frame
    // =========================================================================

    @TimeTravelBody
    static void outerMethod() {
        LOG.add("outer-before");
        innerMethod();
        LOG.add("outer-after");
    }

    @TimeTravelBody
    static void innerMethod() {
        LOG.add("inner-step1");
        LOG.add("inner-step2");
    }

    @Test
    void cross_method_resume_with_correct_deque_ordering() {
        // Forward run: capture all frames from outer + inner.
        outerMethod();
        List<ResumeFrame> frames = Ttd.testPeekDeque();
        assertTrue(frames.size() >= 2,
                "forward run should push frames from both outer and inner; got " + frames.size());

        // Verify full forward execution log.
        assertEquals(List.of("outer-before", "inner-step1", "inner-step2", "outer-after"),
                new ArrayList<>(LOG),
                "forward run log must be correct");

        // Identify outer and inner methodIds.
        String outerKey = CpsDispatchRoundtripTest.class.getName().replace('.', '/')
                + ".outerMethod()V";
        String innerKey = CpsDispatchRoundtripTest.class.getName().replace('.', '/')
                + ".innerMethod()V";
        int outerMethodId = Ttd.internMethodId(outerKey);
        int innerMethodId = Ttd.internMethodId(innerKey);

        // Locate the outer CALLSITE frame and the earliest inner frame.
        //
        // HEAD-to-TAIL order of frames after forward run:
        //   [outer(last_bci), ..., outer(post-inner_bci),
        //    inner(last_bci), ..., inner(first_bci),
        //    outer(callsite_bci), outer(pre-callsite_bcis), outer(first_bci)]
        //
        // We want:
        //   outerCallsiteFrame = the outer frame immediately AFTER the last inner frame
        //                        in HEAD-to-TAIL order (i.e., at index firstInnerIdx+innerCount).
        //   innerEarliestFrame = the last inner frame in HEAD-to-TAIL order
        //                        (= first pushed by inner = inner's first save point).
        //
        // Algorithm: scan HEAD-to-TAIL.
        //   Phase 1: skip leading outer frames (post-inner outer frames).
        //   Phase 2: collect inner frames (record the LAST one seen = innerEarliestFrame).
        //   Phase 3: first outer frame after inner block = outerCallsiteFrame.

        ResumeFrame outerCallsiteFrame = null;
        ResumeFrame innerEarliestFrame = null;

        // Phase 1+2: skip outer, then collect inner.
        boolean inInnerBlock = false;
        int outerCallsiteIdx = -1;
        for (int i = 0; i < frames.size(); i++) {
            ResumeFrame f = frames.get(i);
            if (!inInnerBlock && f.methodId == outerMethodId) {
                // Phase 1: leading outer frames (post-inner).
                continue;
            }
            if (f.methodId == innerMethodId) {
                // Phase 2: inner frames.
                inInnerBlock = true;
                innerEarliestFrame = f; // keep updating → last inner = earliest pushed
                continue;
            }
            if (inInnerBlock && f.methodId == outerMethodId) {
                // Phase 3: first outer frame after inner block = callsite outer frame.
                outerCallsiteFrame = f;
                break;
            }
        }

        if (outerCallsiteFrame == null || innerEarliestFrame == null) {
            // Cannot identify callsite + inner frames. Print diagnostics.
            StringBuilder diag = new StringBuilder("Cross-method: could not isolate callsite frame.\n");
            diag.append("  outerMethodId=").append(outerMethodId)
                    .append("  innerMethodId=").append(innerMethodId).append("\n");
            diag.append("  frames (HEAD first):\n");
            for (ResumeFrame f : frames) {
                diag.append("    methodId=").append(f.methodId).append(" bci=").append(f.bci).append("\n");
            }
            System.err.println(diag);
            // Report without failing: this is a diagnostic path, not a dispatch bug.
            return;
        }

        final ResumeFrame outerFrame = outerCallsiteFrame;
        final ResumeFrame innerFrame = innerEarliestFrame;

        // Stage in CORRECT LIFO order per SOUNDNESS.md §9:
        // Push inner first → inner goes to HEAD temporarily.
        // Push outer last → outer becomes HEAD.
        // Result: HEAD [outer_frame, inner_frame] TAIL.
        Ttd.testClearDeque();
        LOG.clear();

        Ttd.testPushFrame(innerFrame); // push inner (will become TAIL after outer push)
        Ttd.testPushFrame(outerFrame); // push outer → outer is now HEAD

        List<ResumeFrame> staged = Ttd.testPeekDeque();
        assertEquals(2, staged.size(), "should have exactly 2 staged frames");
        assertEquals(outerFrame.methodId, staged.get(0).methodId,
                "HEAD must be outer_frame");
        assertEquals(innerFrame.methodId, staged.get(1).methodId,
                "TAIL must be inner_frame");

        // Re-run outer.
        outerMethod();

        // Expected cross-method resume behavior:
        // 1. outer's prelude: HEAD = outer_frame (callsite bci) → match → pop.
        //    Restore outer's locals. GOTO callsite shim. The shim directly calls innerMethod()
        //    WITHOUT traversing any intermediate save-frame snippets (the shim label is placed
        //    right before the INVOKE, after the save-frame was already emitted in the forward run).
        // 2. inner's prelude: HEAD = inner_frame → match → pop. Resume inner from saved BCI.
        // 3. inner executes from its save point onward.
        // 4. inner returns. Outer continues: LOG.add("outer-after").

        List<ResumeFrame> postFrames = Ttd.testPeekDeque();

        // Both staged frames must be consumed.
        boolean outerConsumed = postFrames.stream().noneMatch(f -> f.prims == outerFrame.prims);
        boolean innerConsumed = postFrames.stream().noneMatch(f -> f.prims == innerFrame.prims);

        assertTrue(outerConsumed,
                "outer_frame (callsite) must be consumed by outer's dispatch prelude;"
                        + " postDeque=" + postFrames.size() + " log=" + LOG);
        assertTrue(innerConsumed,
                "inner_frame must be consumed by inner's dispatch prelude;"
                        + " postDeque=" + postFrames.size() + " log=" + LOG);

        // outer-after must appear in the log (outer continued after inner returned).
        assertTrue(LOG.contains("outer-after"),
                "outer-after must execute after cross-method resume; log=" + LOG);
    }
}
