package net.jonbell.crochet.compose;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import net.jonbell.crochet.agent.InstrumentedSurfaceVerifierTestBridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link net.jonbell.crochet.agent.InstrumentedSurfaceVerifier}:
 * the negative-composition detection path.
 *
 * <p><b>Negative test</b>: a class with a deliberately-stripped surface
 * (missing {@code $$crochetAccess} method) is detected at agent-load time
 * with a structured log entry naming the offending class and the missing
 * surface element. NOT a {@code ClassFormatError}.
 *
 * <p><b>Positive test</b>: a properly-instrumented class passes the check
 * silently (no {@code [Crochet-Verify] SURFACE_MISMATCH} log line).
 *
 * <p>The verifier is called directly via a test bridge that exercises the
 * ASM-based surface scanning logic without needing a full agent-load cycle.
 */
class InstrumentedSurfaceVerifierTest {

    private PrintStream originalErr;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void captureStderr() {
        originalErr = System.err;
        capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
    }

    /**
     * Negative test: a class file whose {@code $$crochetAccess} method was
     * stripped (simulating a Byte Buddy rewrite that removes the method) is
     * detected as a surface mismatch.
     *
     * <p>The test uses {@link InstrumentedSurfaceVerifierTestBridge#scanBytes}
     * to invoke the verifier's scanning logic on a synthetic class file that
     * has all required elements except {@code $$crochetAccess}.
     */
    @Test
    void detectsMissingCrochetAccessMethod() {
        byte[] brokenClass = InstrumentedSurfaceVerifierTestBridge.buildClassMissingAccessMethod();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(
                "com/example/BrokenBean", brokenClass);
        assertTrue(mismatch.contains("$$crochetAccess"),
                "Expected SURFACE_MISMATCH for missing $$crochetAccess, got: " + mismatch);
        assertFalse(mismatch.contains("$$crochetVersion"),
                "$$crochetVersion should be present; mismatch should only mention $$crochetAccess");
    }

    /**
     * Negative test: a class file with all required surface elements present
     * passes the check silently (empty mismatch set).
     */
    @Test
    void passesWhenSurfaceIsComplete() {
        byte[] goodClass = InstrumentedSurfaceVerifierTestBridge.buildClassWithFullSurface();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(
                "com/example/GoodBean", goodClass);
        assertTrue(mismatch.isEmpty(),
                "Expected no SURFACE_MISMATCH for a properly-instrumented class, got: " + mismatch);
    }

    /**
     * Positive test: a class in the shouldSkip list is never checked (passes
     * through without any log output).
     */
    @Test
    void skipsClassesInSkipList() {
        // java/lang/String is in shouldSkip — verifier must not log for it.
        byte[] stringClass = InstrumentedSurfaceVerifierTestBridge.buildClassMissingAccessMethod();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(
                "java/lang/String", stringClass);
        assertTrue(mismatch.isEmpty(),
                "shouldSkip class should produce no mismatch output");
    }

    /**
     * Positive test: an interface is never checked (interfaces are skipped by
     * the transformer and should produce no mismatch output).
     */
    @Test
    void skipsInterfaces() {
        byte[] iface = InstrumentedSurfaceVerifierTestBridge.buildInterfaceWithoutSurface();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(
                "com/example/MyInterface", iface);
        assertTrue(mismatch.isEmpty(),
                "Interface should produce no mismatch output");
    }
}
