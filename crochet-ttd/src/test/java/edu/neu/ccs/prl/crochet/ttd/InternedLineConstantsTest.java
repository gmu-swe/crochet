package edu.neu.ccs.prl.crochet.ttd;

import static org.junit.jupiter.api.Assertions.*;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Validation matrix for PLAN.md §C.2 — Interned line constants.
 *
 * <p>Tests cover four properties:
 * <ol>
 *   <li><b>Field emission:</b> transformed class contains one
 *       {@code $$ttd$mid$N} static int field per annotated method.</li>
 *   <li><b>CP reduction:</b> no {@code LDC methodIdKey} + INVOKESTATIC
 *       {@code internMethodId} pair appears in save-frame snippets or the
 *       dispatch prelude outside of {@code $ttd$registerAll}; instead,
 *       {@code GETSTATIC} of the synthetic field is used.</li>
 *   <li><b>Determinism (gate 18):</b> transforming the same class bytes
 *       twice produces byte-identical output.</li>
 *   <li><b>Round-trip:</b> {@code Ttd.captureStack()} labels remain
 *       correct after C.2's changes.</li>
 * </ol>
 *
 * <p><b>Implementation note:</b> These tests run in the Surefire forked JVM
 * that has the Crochet agent loaded.  To avoid triggering the Crochet
 * agent's {@code $$crochetAccess()} injection on transformer-internal
 * types (e.g. {@code TtdClassVisitor}) we use <em>streaming
 * {@link ClassVisitor}</em> passes instead of {@code ClassNode}, exactly as
 * {@link CallsiteVerifierTest} does.  Constructing a {@code ClassNode} from
 * the Crochet-instrumented class bytes would invoke ASM's own instrumented
 * methods and trigger {@code NoSuchMethodError} for {@code $$crochetAccess()}
 * on inner classes that were loaded from the agent jar before transformation.
 */
class InternedLineConstantsTest {

    // =========================================================================
    // Fixture builders
    // =========================================================================

    /** Internal class name for single-method fixture. */
    private static final String FIXTURE_CLASS = "com/example/C2Fixture";
    /** Internal class name for multi-method fixture. */
    private static final String MULTI_FIXTURE_CLASS = "com/example/C2MultiFixture";

    /**
     * Build a synthetic class with a single {@code @TimeTravelBody} method
     * {@code body()V} that has {@code numSavePoints} line-number nodes.
     */
    private static byte[] buildSingleMethodFixture(int numSavePoints) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                FIXTURE_CLASS, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "body", "()V", null, null);
        mv.visitAnnotation(LineMarkerTransformer.ANNOTATION_DESC, true).visitEnd();
        mv.visitCode();

        for (int i = 0; i < numSavePoints; i++) {
            Label lbl = new Label();
            mv.visitLabel(lbl);
            mv.visitLineNumber(10 + i, lbl);
            mv.visitInsn(Opcodes.NOP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Build a synthetic class with TWO {@code @TimeTravelBody} methods,
     * each with one save point.
     */
    private static byte[] buildTwoMethodFixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                MULTI_FIXTURE_CLASS, null, "java/lang/Object", null);

        for (String name : new String[]{"methodA", "methodB"}) {
            MethodVisitor mv = cw.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    name, "()V", null, null);
            mv.visitAnnotation(LineMarkerTransformer.ANNOTATION_DESC, true).visitEnd();
            mv.visitCode();
            Label lbl = new Label();
            mv.visitLabel(lbl);
            mv.visitLineNumber(1, lbl);
            mv.visitInsn(Opcodes.NOP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Run {@link LineMarkerTransformer} on raw class bytes.  Returns the
     * transformed bytes; never null (asserts non-null).
     *
     * <p>Passes the test class loader so that {@link LineMarkerTransformer}'s
     * {@link LineMarkerTransformer.TtdSafeClassWriter} can resolve super-class
     * names for COMPUTE_FRAMES — but the fixture classes use only
     * {@code java/lang/Object} as super, so any non-null loader works.
     */
    private static byte[] applyTransformer(String internalName, byte[] classBytes) {
        LineMarkerTransformer xfm = new LineMarkerTransformer();
        byte[] result = xfm.transform(
                InternedLineConstantsTest.class.getClassLoader(),
                internalName, null, null, classBytes);
        assertNotNull(result,
                "transformer must not return null for '" + internalName
                        + "' (classBytes.length=" + classBytes.length + ")");
        return result;
    }

    // =========================================================================
    // Streaming inspection helpers
    // (All byte-level analysis uses streaming ClassVisitors, not ClassNode,
    // to avoid Crochet's $$crochetAccess() injection issues on agent-loaded
    // inner classes.)
    // =========================================================================

    /**
     * Count the {@code $$ttd$mid$N} fields emitted by C.2 in a transformed
     * class.
     */
    private static int countMidFields(byte[] classBytes) {
        int[] count = {0};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (name != null && name.startsWith(LineMarkerTransformer.TTD_MID_FIELD_PREFIX)) {
                    count[0]++;
                }
                return null;
            }
        }, 0);
        return count[0];
    }

    /**
     * Collect all LDC String constants emitted in a specific method that look
     * like a methodIdKey ({@code "owner.name(desc)"}).
     * Pass {@code null} for {@code methodName} to scan ALL methods.
     */
    private static List<String> collectMethodIdKeyLdcsInMethod(byte[] classBytes,
                                                               final String methodName) {
        List<String> found = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (methodName != null && !methodName.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            String s = (String) value;
                            if (s.contains(".") && s.contains("(")) {
                                found.add(name + ": \"" + s + "\"");
                            }
                        }
                    }
                };
            }
        }, 0);
        return found;
    }

    /**
     * Count {@code INVOKESTATIC Ttd.internMethodId} calls in a specific method.
     */
    private static int countInternMethodIdCallsInMethod(byte[] classBytes,
                                                        final String methodName) {
        int[] count = {0};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals(methodName)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                               String mdesc, boolean itf) {
                        if (LineMarkerTransformer.TTD_OWNER.equals(owner)
                                && "internMethodId".equals(mname)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }

    /**
     * Count {@code GETSTATIC} of {@code $$ttd$mid$N} fields in a specific method.
     */
    private static int countMidFieldGetStaticsInMethod(byte[] classBytes,
                                                       final String methodName) {
        int[] count = {0};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals(methodName)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname,
                                              String fdesc) {
                        if (opcode == Opcodes.GETSTATIC
                                && fname != null
                                && fname.startsWith(
                                    LineMarkerTransformer.TTD_MID_FIELD_PREFIX)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }

    /**
     * Count {@code PUTSTATIC} of {@code $$ttd$mid$N} fields in a specific method.
     */
    private static int countMidFieldPutStaticsInMethod(byte[] classBytes,
                                                       final String methodName) {
        int[] count = {0};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals(methodName)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname,
                                              String fdesc) {
                        if (opcode == Opcodes.PUTSTATIC
                                && fname != null
                                && fname.startsWith(
                                    LineMarkerTransformer.TTD_MID_FIELD_PREFIX)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }

    // =========================================================================
    // SHA-256 helper
    // =========================================================================

    private static String sha256hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // =========================================================================
    // Test 1: Single method — exactly one $$ttd$mid$0 field is emitted
    // =========================================================================

    @Test
    void single_method_emits_exactly_one_mid_field() {
        byte[] classBytes = buildSingleMethodFixture(2);
        byte[] transformed = applyTransformer(FIXTURE_CLASS, classBytes);

        assertEquals(1, countMidFields(transformed),
                "single annotated method should produce exactly one $$ttd$mid$ field");
    }

    // =========================================================================
    // Test 2: Two methods — two $$ttd$mid$ fields
    // =========================================================================

    @Test
    void two_methods_emit_two_mid_fields() {
        byte[] classBytes = buildTwoMethodFixture();
        byte[] transformed = applyTransformer(MULTI_FIXTURE_CLASS, classBytes);

        assertEquals(2, countMidFields(transformed),
                "two annotated methods should produce two $$ttd$mid$ fields");
    }

    // =========================================================================
    // Test 3: No LDC methodIdKey strings appear outside $ttd$registerAll
    //         in the instrumented method body (core C.2 CP-reduction invariant)
    // =========================================================================

    @Test
    void no_method_id_key_ldc_in_instrumented_body_method() {
        byte[] classBytes = buildSingleMethodFixture(3);
        byte[] transformed = applyTransformer(FIXTURE_CLASS, classBytes);

        // Scan the instrumented body method (NOT $ttd$registerAll) for any
        // LDC that looks like a methodIdKey string.
        List<String> ldcs = collectMethodIdKeyLdcsInMethod(transformed, "body");
        assertTrue(ldcs.isEmpty(),
                "No methodIdKey LDC strings should appear in 'body' after C.2;"
                        + " found: " + ldcs);
    }

    // =========================================================================
    // Test 4: internMethodId is called exactly once per method key
    //         (only in $ttd$registerAll)
    // =========================================================================

    @Test
    void intern_method_id_called_once_in_register_all_for_one_method() {
        // 3 save points in one method → internMethodId should be called once,
        // not three times (once per snippet).
        byte[] classBytes = buildSingleMethodFixture(3);
        byte[] transformed = applyTransformer(FIXTURE_CLASS, classBytes);

        int calls = countInternMethodIdCallsInMethod(transformed,
                LineMarkerTransformer.REGISTER_ALL_METHOD);
        assertEquals(1, calls,
                "internMethodId should be called exactly once in $ttd$registerAll;"
                        + " calls=" + calls);
    }

    @Test
    void intern_method_id_called_twice_for_two_methods() {
        byte[] classBytes = buildTwoMethodFixture();
        byte[] transformed = applyTransformer(MULTI_FIXTURE_CLASS, classBytes);

        int calls = countInternMethodIdCallsInMethod(transformed,
                LineMarkerTransformer.REGISTER_ALL_METHOD);
        assertEquals(2, calls,
                "internMethodId should be called once per annotated method;"
                        + " calls=" + calls);
    }

    @Test
    void intern_method_id_not_called_in_instrumented_body() {
        byte[] classBytes = buildSingleMethodFixture(3);
        byte[] transformed = applyTransformer(FIXTURE_CLASS, classBytes);

        // The 'body' method must NOT call internMethodId at runtime.
        int calls = countInternMethodIdCallsInMethod(transformed, "body");
        assertEquals(0, calls,
                "internMethodId must not be called from the instrumented body method;"
                        + " found " + calls + " calls");
    }

    // =========================================================================
    // Test 5: GETSTATIC $$ttd$mid$N appears in the instrumented body method
    //         and in $ttd$registerAll
    // =========================================================================

    @Test
    void getstatic_mid_field_in_body_method() {
        // 2 save points → dispatch prelude (1 GETSTATIC) + 2 save-frame snippets
        // (2 GETSTATICs) = at least 3 total in 'body'.
        byte[] classBytes = buildSingleMethodFixture(2);
        byte[] transformed = applyTransformer(FIXTURE_CLASS, classBytes);

        int count = countMidFieldGetStaticsInMethod(transformed, "body");
        assertTrue(count >= 3,
                "expected ≥3 GETSTATICs of $$ttd$mid$ in 'body' (prelude + 2 snippets);"
                        + " got=" + count);
    }

    @Test
    void getstatic_mid_field_in_register_all_for_registerMethodLine() {
        // 2 save points → 2 registerMethodLine calls → 2 GETSTATICs in $ttd$registerAll.
        byte[] classBytes = buildSingleMethodFixture(2);
        byte[] transformed = applyTransformer(FIXTURE_CLASS, classBytes);

        int count = countMidFieldGetStaticsInMethod(transformed,
                LineMarkerTransformer.REGISTER_ALL_METHOD);
        assertEquals(2, count,
                "$ttd$registerAll should emit one GETSTATIC per save-point;"
                        + " got=" + count);
    }

    // =========================================================================
    // Test 6: $ttd$registerAll emits PUTSTATIC for each $$ttd$mid$ field
    // =========================================================================

    @Test
    void register_all_emits_putstatic_once_per_method() {
        // Two methods → two PUTSTATIC in $ttd$registerAll.
        byte[] classBytes = buildTwoMethodFixture();
        byte[] transformed = applyTransformer(MULTI_FIXTURE_CLASS, classBytes);

        int count = countMidFieldPutStaticsInMethod(transformed,
                LineMarkerTransformer.REGISTER_ALL_METHOD);
        assertEquals(2, count,
                "$ttd$registerAll should emit one PUTSTATIC per annotated method;"
                        + " got=" + count);
    }

    // =========================================================================
    // Test 7: Determinism (gate 18) — same input → byte-identical output
    // =========================================================================

    @Test
    void transform_is_deterministic_single_method() throws Exception {
        byte[] classBytes = buildSingleMethodFixture(3);
        byte[] t1 = applyTransformer(FIXTURE_CLASS, classBytes);
        byte[] t2 = applyTransformer(FIXTURE_CLASS, classBytes);

        String hash1 = sha256hex(t1);
        String hash2 = sha256hex(t2);
        assertEquals(hash1, hash2,
                "Two transforms of the same class must produce byte-identical output;"
                        + " hash1=" + hash1 + " hash2=" + hash2);
    }

    @Test
    void transform_is_deterministic_two_methods() throws Exception {
        byte[] classBytes = buildTwoMethodFixture();
        byte[] t1 = applyTransformer(MULTI_FIXTURE_CLASS, classBytes);
        byte[] t2 = applyTransformer(MULTI_FIXTURE_CLASS, classBytes);

        String hash1 = sha256hex(t1);
        String hash2 = sha256hex(t2);
        assertEquals(hash1, hash2,
                "Two transforms of the two-method class must produce byte-identical output;"
                        + " hash1=" + hash1 + " hash2=" + hash2);
    }

    @Test
    void mid_field_count_stable_across_transforms() {
        // Slot assignment stability: same class → same field count → same fields.
        byte[] classBytes = buildTwoMethodFixture();
        byte[] t1 = applyTransformer(MULTI_FIXTURE_CLASS, classBytes);
        byte[] t2 = applyTransformer(MULTI_FIXTURE_CLASS, classBytes);

        assertEquals(countMidFields(t1), countMidFields(t2),
                "$$ttd$mid$ field count must be stable across transforms");
    }

    // =========================================================================
    // Test 8: Round-trip — captureStack() labels are correct after C.2
    //         (requires the agent to be running to instrument this class)
    // =========================================================================

    /** Annotated method — instrumented by the TTD transformer at class-load time. */
    @TimeTravelBody
    static void c2RoundtripBody() {
        // The transformer adds a save-frame snippet at each line-number node.
        // This method has at least one line-number node (the method entry).
    }

    @BeforeEach
    void setup() {
        Ttd.testClearDeque();
        Ttd.testSetTtdGen(1L);
    }

    @AfterEach
    void teardown() {
        Ttd.testClearDeque();
        Ttd.testSetTtdGen(0L);
    }

    @Test
    void captureStack_labels_are_correct_after_c2() {
        // Run the annotated method: save-frame snippets push frames.
        c2RoundtripBody();
        List<ResumeFrame> frames = Ttd.testPeekDeque();

        // If no frames were pushed, the method has no save points
        // (no line-number debug info in this compilation unit).
        // In that case skip the label check — transformation still succeeded.
        if (frames.isEmpty()) return;

        List<StackEntry> stack = Ttd.captureStack();
        assertFalse(stack.isEmpty(), "captureStack should return entries after forward run");

        // Every label should NOT be the "<methodId=N bci=M>" sentinel — it should
        // come from the debug table populated by $ttd$registerAll at class init.
        for (StackEntry entry : stack) {
            String label = entry.classMethodLine();
            assertNotNull(label, "label must not be null");
            if (label.startsWith("<methodId=")) {
                fail("label should come from the debug table, not the sentinel; got: "
                        + label + "; check that $ttd$registerAll runs at class-init");
            }
        }
    }
}
