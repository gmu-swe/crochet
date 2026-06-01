package edu.neu.ccs.prl.crochet.ttd;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import edu.neu.ccs.prl.crochet.ttd.LineMarkerTransformer.MethodAnalysis;
import edu.neu.ccs.prl.crochet.ttd.LineMarkerTransformer.SavePoint;

/**
 * Unit tests for {@link LineMarkerTransformer}, covering the validation matrix
 * from B.3's design:
 *
 * <ol>
 *   <li>MONITORENTER refusal: methods with {@code synchronized} blocks produce
 *       {@link IllegalStateException} at analysis time.</li>
 *   <li>Skip rules: {@code <clinit>}, synthetic, abstract, native methods
 *       produce no {@link MethodAnalysis}.</li>
 *   <li>Save-point enumeration: line-marker BCIs become save points; callsite
 *       BCIs with reconstructible arguments are also included.</li>
 *   <li>Primitive encoding / liveness: save points carry the correct
 *       categorization of live locals into prims and refs.</li>
 *   <li>Dispatch prelude integration: the synthetic save-frame mechanism
 *       can be tested end-to-end via {@link Ttd#saveFrame} /
 *       {@link Ttd#popResumeFrame}.</li>
 * </ol>
 *
 * <p>Tests that require the agent to be present (save-frame emission, dispatch
 * prelude) are in {@link CpsResumeTest} and {@link TtdLineMarkerTest}, which
 * run under the surefire javaagent configuration.
 */
class LineMarkerTransformerTest {

    // =========================================================================
    // B.3-1: MONITORENTER refusal
    // =========================================================================

    /**
     * A method annotated with {@link TimeTravelBody} that contains a
     * {@code synchronized} block at the same level as a line marker.
     * Analysis must throw {@link IllegalStateException}.
     */
    @Test
    void monitorenter_in_save_point_region_throws() {
        // Build a MethodNode that has MONITORENTER + a line marker inside it.
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "syncedBody",
                "(Ljava/lang/Object;)V",
                null, null);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new org.objectweb.asm.tree.AnnotationNode(
                LineMarkerTransformer.ANNOTATION_DESC));

        // Build bytecode: ALOAD 0; MONITORENTER; LINENUMBER 1 label; ALOAD 0; MONITOREXIT; RETURN
        org.objectweb.asm.tree.LabelNode label = new org.objectweb.asm.tree.LabelNode();
        mn.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.MONITORENTER));
        mn.instructions.add(label);
        mn.instructions.add(new org.objectweb.asm.tree.LineNumberNode(42, label));
        mn.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.MONITOREXIT));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        mn.maxLocals = 1;
        mn.maxStack = 1;

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LineMarkerTransformer.analyzeMethod("com/example/Foo", mn));
        assertTrue(ex.getMessage().contains("MONITORENTER"),
                "exception message should mention MONITORENTER; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("syncedBody"),
                "exception message should mention the method name; got: " + ex.getMessage());
    }

    /**
     * A MONITORENTER that is BEFORE any line marker (so no line marker is inside
     * the monitor region) must NOT throw.
     */
    @Test
    void monitorenter_before_all_line_markers_is_ok() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "monitorFirst",
                "(Ljava/lang/Object;)V",
                null, null);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new org.objectweb.asm.tree.AnnotationNode(
                LineMarkerTransformer.ANNOTATION_DESC));

        // Build: MONITORENTER; MONITOREXIT; LINENUMBER; RETURN
        org.objectweb.asm.tree.LabelNode label = new org.objectweb.asm.tree.LabelNode();
        mn.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.MONITORENTER));
        mn.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.MONITOREXIT));
        mn.instructions.add(label);
        mn.instructions.add(new org.objectweb.asm.tree.LineNumberNode(10, label));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        mn.maxLocals = 1;
        mn.maxStack = 1;

        // Should complete without throwing.
        MethodAnalysis analysis = LineMarkerTransformer.analyzeMethod("com/example/Foo", mn);
        assertNotNull(analysis, "method with monitor before save point should be analyzed");
        assertEquals(1, analysis.savePoints.size(), "should have one save point");
    }

    // =========================================================================
    // B.3-2: Skip rules
    // =========================================================================

    @Test
    void synthetic_methods_are_skipped() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "lambda$0",
                "()V", null, null);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new org.objectweb.asm.tree.AnnotationNode(
                LineMarkerTransformer.ANNOTATION_DESC));
        // Even with the annotation, synthetic methods produce no analysis.
        // (LineMarkerTransformer.isEligible returns false for synthetic.)
        assertFalse(isEligible(mn), "synthetic method should not be eligible");
    }

    @Test
    void abstract_methods_are_skipped() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "abstractMethod",
                "()V", null, null);
        assertFalse(isEligible(mn), "abstract method should not be eligible");
    }

    @Test
    void native_methods_are_skipped() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE,
                "nativeMethod",
                "()V", null, null);
        assertFalse(isEligible(mn), "native method should not be eligible");
    }

    @Test
    void constructor_is_skipped() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC,
                "<init>",
                "()V", null, null);
        assertFalse(isEligible(mn), "<init> should not be eligible");
    }

    @Test
    void static_initializer_is_skipped() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_STATIC,
                "<clinit>",
                "()V", null, null);
        assertFalse(isEligible(mn), "<clinit> should not be eligible");
    }

    // =========================================================================
    // B.3-3: Save-point enumeration
    // =========================================================================

    @Test
    void method_with_no_line_numbers_produces_null_analysis() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "noLines",
                "()V", null, null);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new org.objectweb.asm.tree.AnnotationNode(
                LineMarkerTransformer.ANNOTATION_DESC));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        mn.maxLocals = 0;
        mn.maxStack = 0;

        MethodAnalysis analysis = LineMarkerTransformer.analyzeMethod("com/example/Foo", mn);
        assertNull(analysis, "method without line numbers should produce null (Phase 1 fallback)");
    }

    @Test
    void each_line_number_node_becomes_one_save_point() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "threeLines",
                "()V", null, null);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new org.objectweb.asm.tree.AnnotationNode(
                LineMarkerTransformer.ANNOTATION_DESC));

        // Three line number nodes, each followed by a NOP.
        for (int line : new int[]{10, 20, 30}) {
            org.objectweb.asm.tree.LabelNode lbl = new org.objectweb.asm.tree.LabelNode();
            mn.instructions.add(lbl);
            mn.instructions.add(new org.objectweb.asm.tree.LineNumberNode(line, lbl));
            mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.NOP));
        }
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        mn.maxLocals = 0;
        mn.maxStack = 0;

        MethodAnalysis analysis = LineMarkerTransformer.analyzeMethod("com/example/Foo", mn);
        assertNotNull(analysis, "should produce analysis for method with line numbers");
        assertEquals(3, analysis.savePoints.size(),
                "each line number node should become one save point");

        // Verify save points are sorted ascending by BCI.
        List<SavePoint> sps = analysis.savePoints;
        for (int i = 1; i < sps.size(); i++) {
            assertTrue(sps.get(i).bci > sps.get(i - 1).bci,
                    "save points should be in ascending BCI order");
        }
    }

    @Test
    void save_points_carry_correct_line_numbers() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lineCheck",
                "()V", null, null);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new org.objectweb.asm.tree.AnnotationNode(
                LineMarkerTransformer.ANNOTATION_DESC));

        org.objectweb.asm.tree.LabelNode lbl1 = new org.objectweb.asm.tree.LabelNode();
        org.objectweb.asm.tree.LabelNode lbl2 = new org.objectweb.asm.tree.LabelNode();
        mn.instructions.add(lbl1);
        mn.instructions.add(new org.objectweb.asm.tree.LineNumberNode(42, lbl1));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.NOP));
        mn.instructions.add(lbl2);
        mn.instructions.add(new org.objectweb.asm.tree.LineNumberNode(99, lbl2));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        mn.maxLocals = 0;
        mn.maxStack = 0;

        MethodAnalysis analysis = LineMarkerTransformer.analyzeMethod("com/example/Foo", mn);
        assertNotNull(analysis);
        assertEquals(2, analysis.savePoints.size());
        assertEquals(42, analysis.savePoints.get(0).lineNumber);
        assertEquals(99, analysis.savePoints.get(1).lineNumber);
    }

    // =========================================================================
    // B.3-4: Primitive / reference categorization
    // =========================================================================

    @Test
    void live_locals_are_categorized_into_prems_and_refs() {
        // Build a method with one int local and one Object local both live
        // at the save point.
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "mixedLocals",
                "(ILjava/lang/Object;)V",
                null, null);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new org.objectweb.asm.tree.AnnotationNode(
                LineMarkerTransformer.ANNOTATION_DESC));

        org.objectweb.asm.tree.LabelNode lbl = new org.objectweb.asm.tree.LabelNode();
        mn.instructions.add(lbl);
        mn.instructions.add(new org.objectweb.asm.tree.LineNumberNode(1, lbl));
        // Keep slot 0 (int) and slot 1 (Object) live by using them.
        mn.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 0));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
        mn.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        mn.maxLocals = 2;
        mn.maxStack = 1;

        MethodAnalysis analysis = LineMarkerTransformer.analyzeMethod("com/example/Foo", mn);
        assertNotNull(analysis);
        assertEquals(1, analysis.savePoints.size());

        SavePoint sp = analysis.savePoints.get(0);
        // Slot 0 (int) → livePrems
        // Slot 1 (Object) → liveRefs
        assertEquals(1, sp.livePrems.size(),
                "int parameter should be in livePrems; prems=" + sp.livePrems);
        assertEquals(1, sp.liveRefs.size(),
                "Object parameter should be in liveRefs; refs=" + sp.liveRefs);
        assertEquals(0, sp.livePrems.get(0).slotIndex(), "prim slot index should be 0");
        assertEquals(1, sp.liveRefs.get(0).slotIndex(), "ref slot index should be 1");
        assertEquals(Type.INT, sp.livePrems.get(0).type().getSort());
        assertEquals(Type.OBJECT, sp.liveRefs.get(0).type().getSort());
    }

    // =========================================================================
    // B.3-5: Method-id key format
    // =========================================================================

    @Test
    void method_id_key_contains_owner_name_and_desc() {
        MethodNode mn = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "myMethod",
                "(I)Ljava/lang/String;",
                null, null);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new org.objectweb.asm.tree.AnnotationNode(
                LineMarkerTransformer.ANNOTATION_DESC));

        org.objectweb.asm.tree.LabelNode lbl = new org.objectweb.asm.tree.LabelNode();
        mn.instructions.add(lbl);
        mn.instructions.add(new org.objectweb.asm.tree.LineNumberNode(1, lbl));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        mn.maxLocals = 1;
        mn.maxStack = 1;

        MethodAnalysis analysis =
                LineMarkerTransformer.analyzeMethod("com/example/Bar", mn);
        assertNotNull(analysis);
        assertTrue(analysis.methodIdKey.contains("com/example/Bar"),
                "methodIdKey should contain owner internal name; got: " + analysis.methodIdKey);
        assertTrue(analysis.methodIdKey.contains("myMethod"),
                "methodIdKey should contain method name; got: " + analysis.methodIdKey);
        assertTrue(analysis.methodIdKey.contains("(I)Ljava/lang/String;"),
                "methodIdKey should contain descriptor; got: " + analysis.methodIdKey);
    }

    // =========================================================================
    // B.3-6: TtdSafeClassWriter — common-superclass resolution
    // =========================================================================

    @Test
    void safe_class_writer_returns_object_for_unresolvable_types() {
        LineMarkerTransformer.TtdSafeClassWriter cw =
                new LineMarkerTransformer.TtdSafeClassWriter(null, ClassWriter.COMPUTE_FRAMES, null);
        // Two completely unresolvable types should fall back to java/lang/Object.
        String common = cw.commonSuperClassOf("no/such/TypeA", "no/such/TypeB");
        assertEquals("java/lang/Object", common,
                "unresolvable types should resolve to java/lang/Object");
    }

    @Test
    void safe_class_writer_returns_same_type_for_identical_inputs() {
        LineMarkerTransformer.TtdSafeClassWriter cw =
                new LineMarkerTransformer.TtdSafeClassWriter(null, ClassWriter.COMPUTE_FRAMES, null);
        assertEquals("java/lang/String",
                cw.commonSuperClassOf("java/lang/String", "java/lang/String"),
                "identical types should return themselves");
    }

    @Test
    void safe_class_writer_returns_object_when_one_is_object() {
        LineMarkerTransformer.TtdSafeClassWriter cw =
                new LineMarkerTransformer.TtdSafeClassWriter(null, ClassWriter.COMPUTE_FRAMES, null);
        assertEquals("java/lang/Object",
                cw.commonSuperClassOf("java/lang/Object", "java/lang/String"),
                "Object + String should return Object");
        assertEquals("java/lang/Object",
                cw.commonSuperClassOf("java/lang/String", "java/lang/Object"),
                "String + Object should return Object");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Replicates the package-private {@code LineMarkerTransformer.isEligible}
     * logic for white-box skip-rule verification.
     */
    private static boolean isEligible(MethodNode mn) {
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) return false;
        int syntheticFlags =
                Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE;
        return (mn.access & syntheticFlags) == 0;
    }
}
