package net.jonbell.crochet.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InstrumentedSurfaceVerifier} and
 * {@link InstrumentedSurfaceVerifierTestBridge}.
 *
 * <p>These tests exercise the ASM-based surface scanning logic directly,
 * without a full agent-load cycle, so they work under plain {@code mvn test}
 * (no instrumented JDK needed).
 *
 * <h2>Negative test</h2>
 * <p>A class with a deliberately-stripped surface ({@code $$crochetAccess}
 * removed) is detected as a surface mismatch naming the missing element.
 * This simulates the scenario where a downstream agent (e.g. Byte Buddy in
 * a bad composition) silently removes the method.
 *
 * <h2>Positive tests</h2>
 * <ul>
 *   <li>A properly-instrumented class passes silently (empty mismatch).</li>
 *   <li>A class in the {@code shouldSkip} list is never checked.</li>
 *   <li>An interface is silently skipped.</li>
 * </ul>
 */
class InstrumentedSurfaceVerifierTest {

    /**
     * Negative test: class with all surface elements except
     * {@code $$crochetAccess} → mismatch names the missing element, does NOT
     * throw {@code ClassFormatError}.
     */
    @Test
    void detectsMissingCrochetAccessMethod() {
        byte[] brokenClass = InstrumentedSurfaceVerifierTestBridge.buildClassMissingAccessMethod();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(
                "com/example/BrokenBean", brokenClass);
        assertTrue(mismatch.contains("$$crochetAccess"),
                "Expected mismatch to name $$crochetAccess, got: " + mismatch);
        assertFalse(mismatch.contains("$$crochetVersion"),
                "$$crochetVersion is present; mismatch should not include it. Got: " + mismatch);
        assertFalse(mismatch.contains("$$crochetCheckpoint"),
                "$$crochetCheckpoint is present; mismatch should not include it. Got: " + mismatch);
    }

    /**
     * Positive test: a fully-instrumented class → empty mismatch (no error).
     */
    @Test
    void passesWhenSurfaceIsComplete() {
        byte[] goodClass = InstrumentedSurfaceVerifierTestBridge.buildClassWithFullSurface();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(
                "com/example/GoodBean", goodClass);
        assertTrue(mismatch.isEmpty(),
                "Expected no mismatch for complete surface, got: " + mismatch);
    }

    /**
     * Positive test: a class in the {@code shouldSkip} list (e.g.
     * {@code java/lang/String}) → silently skipped, empty mismatch.
     */
    @Test
    void skipsClassesInSkipList() {
        // Use a class that is definitely in shouldSkip.
        byte[] stripped = InstrumentedSurfaceVerifierTestBridge.buildClassMissingAccessMethod();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(
                "java/lang/String", stripped);
        assertTrue(mismatch.isEmpty(),
                "shouldSkip class should produce no mismatch, got: " + mismatch);
    }

    /**
     * Positive test: an interface → silently skipped (interfaces are not
     * instrumented), empty mismatch.
     */
    @Test
    void skipsInterfaces() {
        byte[] iface = InstrumentedSurfaceVerifierTestBridge.buildInterfaceWithoutSurface();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(
                "com/example/MyInterface", iface);
        assertTrue(mismatch.isEmpty(),
                "Interface should produce no mismatch, got: " + mismatch);
    }

    /**
     * Positive test: null className → silently skipped, empty mismatch.
     */
    @Test
    void handlesNullClassName() {
        byte[] goodClass = InstrumentedSurfaceVerifierTestBridge.buildClassWithFullSurface();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(null, goodClass);
        assertTrue(mismatch.isEmpty(), "null className should be silently skipped");
    }

    /**
     * Edge-case test: a class with ALL surface elements missing → mismatch
     * names all seven elements.
     */
    @Test
    void detectsAllMissingSurfaceElements() {
        byte[] bare = InstrumentedSurfaceVerifierTestBridge.buildInterfaceWithoutSurface();
        // Use a non-interface bare class by building a class with no surface.
        // Build a minimal non-interface class with nothing.
        byte[] minimalClass = buildMinimalClassWithNoSurface();
        String mismatch = InstrumentedSurfaceVerifierTestBridge.scanBytes(
                "com/example/MinimalBean", minimalClass);
        assertTrue(mismatch.contains("@CrochetInstrumented"), "missing: " + mismatch);
        assertTrue(mismatch.contains("$$crochetVersion"), "missing: " + mismatch);
        assertTrue(mismatch.contains("$$crochetSnap"), "missing: " + mismatch);
        assertTrue(mismatch.contains("$$crochetAccess"), "missing: " + mismatch);
        assertTrue(mismatch.contains("$$crochetCheckpoint"), "missing: " + mismatch);
        assertTrue(mismatch.contains("$$crochetRollback"), "missing: " + mismatch);
        assertTrue(mismatch.contains("CRIJInstrumented"), "missing: " + mismatch);
    }

    private static byte[] buildMinimalClassWithNoSurface() {
        org.objectweb.asm.ClassWriter cw =
                new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cw.visit(org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "com/example/MinimalBean", null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
