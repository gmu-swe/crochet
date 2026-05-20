package edu.neu.ccs.prl.crochet.ttd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.ClassReader;

import edu.neu.ccs.prl.crochet.ttd.LineMarkerTransformer.MethodAnalysis;
import edu.neu.ccs.prl.crochet.ttd.LineMarkerTransformer.SavePoint;

/**
 * B.7 Phase 1 Diagnosis: Analyze why B.3's callsite-save-point transformer
 * refuses callsites in Lucene's {@code Sorter.sort(LeafReader)} and
 * {@code IndexSorter$IntSorter.getDocComparator(LeafReader, int)}.
 *
 * <p>This test does NOT require @TimeTravelBody to be present in the Lucene
 * source; it injects the annotation programmatically to force B.3 analysis.
 *
 * <p>Run with:
 * <pre>
 *   mvn -pl crochet-ttd test \
 *     -Dtest=LuceneSorterDiagTest \
 *     -Dlucene.jar=/path/to/lucene-core-*.jar \
 *     -Dcrochet.ttd.debug=true
 * </pre>
 *
 * <p>The test passes unconditionally when the Lucene jar is not present
 * (so CI is not broken). When the jar IS present, it prints a structured
 * diagnosis of each refused callsite.
 */
class LuceneSorterDiagTest {

    private static final String LUCENE_JAR_PROP = "lucene.jar";

    /** Default path to the Lucene 10.0.0-SNAPSHOT jar used during H-phase work. */
    private static final String DEFAULT_LUCENE_JAR =
            "/home/jon/tapestry/external/lucene/lucene/core/build/libs/lucene-core-10.0.0-SNAPSHOT.jar";

    // -------------------------------------------------------------------------
    // Structural diagnosis: Sorter.sort(LeafReader)
    // -------------------------------------------------------------------------

    /**
     * Diagnosis for {@code Sorter.sort(LeafReader)}: enumerate all INVOKE
     * instructions, report which B.3 acceptance criteria each fails, and
     * verify that the refuse-reasons match the H.3 patch-file description.
     */
    @Test
    void diagnose_sorter_sort_callsite_refusals() throws Exception {
        String jarPath = System.getProperty(LUCENE_JAR_PROP, DEFAULT_LUCENE_JAR);
        byte[] bytes = loadClass(jarPath, "org/apache/lucene/index/Sorter.class");
        if (bytes == null) {
            System.out.println("[b7-diag] Lucene jar not found, skipping: " + jarPath);
            return;
        }

        DiagResult result = diagnoseClass(
                bytes,
                "org/apache/lucene/index/Sorter",
                "sort",
                "(Lorg/apache/lucene/index/LeafReader;)Lorg/apache/lucene/index/Sorter$DocMap;");

        System.out.println("\n=== B.7 Diagnosis: Sorter.sort(LeafReader) ===");
        printDiagResult(result);

        // Assertions: verify structural facts about the method
        // (these hold regardless of whether we fix CPS later)
        assertNotNull(result, "should find Sorter.sort method");
        assertTrue(result.totalNonTtdInvokes > 0,
                "Sorter.sort should have INVOKE instructions");

        // The FAILURES.md says 14 callsites were skipped.
        // In our 10.0.0-SNAPSHOT the count may differ slightly, but
        // we assert at least several are rejected.
        System.out.println("[b7-diag] accepted=" + result.accepted +
                " rejected=" + result.rejected +
                " total=" + result.totalNonTtdInvokes);

        // Verify that rejections exist and capture WHY
        assertTrue(result.rejected > 0,
                "Sorter.sort should have at least 1 rejected callsite");

        // At least one rejection should be argBase > 0 (non-empty stack)
        // based on the H.3 patch description (stack-expr arguments)
        boolean hasArgBaseRefusal = result.refusalReasons.stream()
                .anyMatch(r -> r.reason.contains("argBase") ||
                          r.reason.contains("multi-producer") ||
                          r.reason.contains("non-reconstructible"));
        assertTrue(hasArgBaseRefusal,
                "Expected at least one argBase/producer refusal in Sorter.sort; " +
                "got: " + result.refusalReasons);
    }

    /**
     * Diagnosis for {@code IndexSorter$IntSorter.getDocComparator}: the
     * H.3 patch says 4 callsites were skipped and the AIOOBE was triggered
     * by lambda-closure corruption.
     */
    @Test
    void diagnose_intsorter_getdoccomparator_callsite_refusals() throws Exception {
        String jarPath = System.getProperty(LUCENE_JAR_PROP, DEFAULT_LUCENE_JAR);
        byte[] bytes = loadClass(jarPath, "org/apache/lucene/index/IndexSorter$IntSorter.class");
        if (bytes == null) {
            System.out.println("[b7-diag] Lucene jar not found, skipping: " + jarPath);
            return;
        }

        DiagResult result = diagnoseClass(
                bytes,
                "org/apache/lucene/index/IndexSorter$IntSorter",
                "getDocComparator",
                "(Lorg/apache/lucene/index/LeafReader;I)Lorg/apache/lucene/index/IndexSorter$DocComparator;");

        System.out.println("\n=== B.7 Diagnosis: IntSorter.getDocComparator(LeafReader, int) ===");
        printDiagResult(result);

        assertNotNull(result, "should find IntSorter.getDocComparator method");

        System.out.println("[b7-diag] accepted=" + result.accepted +
                " rejected=" + result.rejected +
                " total=" + result.totalNonTtdInvokes);

        // The method has a while(true) loop with invokedynamic at the end.
        // Key question: what exactly causes the 4 refusals?
    }

    /**
     * Print a B.7-specific analysis of the lambda-closure issue:
     * does the INVOKEDYNAMIC at the end of getDocComparator have
     * a non-empty stack (argBase > 0)?
     */
    @Test
    void diagnose_intsorter_lambda_closure_stack_depth() throws Exception {
        String jarPath = System.getProperty(LUCENE_JAR_PROP, DEFAULT_LUCENE_JAR);
        byte[] bytes = loadClass(jarPath, "org/apache/lucene/index/IndexSorter$IntSorter.class");
        if (bytes == null) {
            System.out.println("[b7-diag] Lucene jar not found, skipping: " + jarPath);
            return;
        }

        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.EXPAND_FRAMES);

        MethodNode mn = findMethod(cn, "getDocComparator",
                "(Lorg/apache/lucene/index/LeafReader;I)Lorg/apache/lucene/index/IndexSorter$DocComparator;");
        assertNotNull(mn, "getDocComparator not found");

        // Run SourceInterpreter to see stack state at each INVOKE
        Analyzer<SourceValue> srcAnalyzer = new Analyzer<>(new SourceInterpreter());
        Frame<SourceValue>[] frames = srcAnalyzer.analyze(
                "org/apache/lucene/index/IndexSorter$IntSorter", mn);

        System.out.println("\n=== getDocComparator: INVOKE stack-depth analysis ===");
        int bci = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            int op = insn.getOpcode();
            if (isInvoke(op)) {
                String desc = getDesc(insn);
                boolean isStatic = (op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEDYNAMIC);
                Type[] argTypes = Type.getArgumentTypes(desc);
                int totalSlots = isStatic ? 0 : 1;
                for (Type t : argTypes) totalSlots += t.getSize();

                Frame<SourceValue> frame = (bci < frames.length) ? frames[bci] : null;
                int stackSize = frame != null ? frame.getStackSize() : -1;
                int argBase = stackSize - totalSlots;

                String invokeName = (insn instanceof MethodInsnNode)
                        ? ((MethodInsnNode) insn).owner + "." + ((MethodInsnNode) insn).name
                        : "INDY:" + ((InvokeDynamicInsnNode) insn).name;

                System.out.printf("  bci=%3d %-15s %-60s stackSize=%d totalSlots=%d argBase=%d%n",
                        bci, opName(op), invokeName.substring(0, Math.min(60, invokeName.length())),
                        stackSize, totalSlots, argBase);

                if (op == Opcodes.INVOKEDYNAMIC) {
                    System.out.println("    ^^ INVOKEDYNAMIC: captures values[] (local 4) and 'this' as closure");
                    System.out.println("       Capture slots in INDY desc: " + desc);
                    // Analyze what's on the stack at this INVOKEDYNAMIC
                    if (frame != null) {
                        for (int s = 0; s < stackSize; s++) {
                            SourceValue sv = frame.getStack(s);
                            String producers = sv != null && sv.insns != null
                                    ? sv.insns.toString() : "null";
                            System.out.println("       stack[" + s + "]: " + producers);
                        }
                    }
                }
            }
            bci++;
        }

        // Key assertion: the INVOKEDYNAMIC should be at bci=67 (from javap),
        // with argBase = stackSize - 0 (INVOKEDYNAMIC is "static-like" for arg count)
        // But the closure capture (this, values[]) is passed AS arguments, so
        // the desc tells us how many closure vars are captured.
        // We need to verify whether any stack-values exist BELOW the arg frame.
        System.out.println("\n  Summary: looking for argBase > 0 at INVOKEDYNAMIC...");
        int indyBci = -1;
        int indyArgBase = -1;
        bci = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKEDYNAMIC) {
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                Type[] argTypes = Type.getArgumentTypes(indy.desc);
                int totalSlots = 0;
                for (Type t : argTypes) totalSlots += t.getSize();
                Frame<SourceValue> frame = (bci < frames.length) ? frames[bci] : null;
                int stackSize = frame != null ? frame.getStackSize() : -1;
                int ab = stackSize - totalSlots;
                System.out.println("  INVOKEDYNAMIC at bci=" + bci +
                        " name=" + indy.name + " desc=" + indy.desc +
                        " stackSize=" + stackSize + " totalSlots=" + totalSlots +
                        " argBase=" + ab);
                indyBci = bci;
                indyArgBase = ab;
            }
            bci++;
        }

        // Document the finding
        System.out.println("\n  FINDING: argBase at INVOKEDYNAMIC = " + indyArgBase);
        if (indyArgBase > 0) {
            System.out.println("  ROOT CAUSE (confirmed): argBase > 0 at the lambda-capture INVOKEDYNAMIC.");
            System.out.println("  Stack has " + indyArgBase + " extra value(s) below the captured args.");
            System.out.println("  B.3 correctly refuses this callsite per its argBase > 0 guard.");
            System.out.println("  The AIOOBE reported in H.3 was likely from a DIFFERENT version of Lucene");
            System.out.println("  where the method structure differed, or from the VerifyError propagating.");
        } else if (indyArgBase == 0) {
            System.out.println("  argBase = 0: INVOKEDYNAMIC stack is clean.");
            System.out.println("  The refusal may be from a different criterion (multi-producer, etc.).");
        }
    }

    /**
     * Full callsite-by-callsite diagnosis of Sorter.sort(LeafReader).
     * Prints detailed reason for each refusal to help understand the VerifyError.
     */
    @Test
    void diagnose_sorter_sort_full_call_trace() throws Exception {
        String jarPath = System.getProperty(LUCENE_JAR_PROP, DEFAULT_LUCENE_JAR);
        byte[] bytes = loadClass(jarPath, "org/apache/lucene/index/Sorter.class");
        if (bytes == null) {
            System.out.println("[b7-diag] Lucene jar not found, skipping: " + jarPath);
            return;
        }

        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.EXPAND_FRAMES);

        MethodNode mn = findMethod(cn, "sort",
                "(Lorg/apache/lucene/index/LeafReader;)Lorg/apache/lucene/index/Sorter$DocMap;");
        assertNotNull(mn, "Sorter.sort(LeafReader) not found");

        String owner = "org/apache/lucene/index/Sorter";

        // Inject @TimeTravelBody for analysis
        if (mn.visibleAnnotations == null) mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new AnnotationNode(LineMarkerTransformer.ANNOTATION_DESC));

        // Run source analysis
        Analyzer<SourceValue> srcAnalyzer = new Analyzer<>(new SourceInterpreter());
        Frame<SourceValue>[] frames = srcAnalyzer.analyze(owner, mn);

        System.out.println("\n=== Sorter.sort(LeafReader): detailed INVOKE trace ===");
        System.out.println("  maxLocals=" + mn.maxLocals + " maxStack=" + mn.maxStack);
        System.out.println("  tryCatchBlocks=" + mn.tryCatchBlocks.size());
        System.out.println("  stackMapFrames=" + countStackMapFrames(mn));

        int bci = 0;
        int totalInvokes = 0;
        int accepted = 0;
        int rejected = 0;

        for (AbstractInsnNode insn : mn.instructions) {
            int op = insn.getOpcode();
            if (!isInvoke(op)) { bci++; continue; }
            if (isTtdCall(insn)) { bci++; continue; }

            totalInvokes++;
            String desc = getDesc(insn);
            boolean isStatic = (op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEDYNAMIC);
            Type[] argTypes = Type.getArgumentTypes(desc);
            int totalSlots = isStatic ? 0 : 1;
            for (Type t : argTypes) totalSlots += t.getSize();

            Frame<SourceValue> frame = (bci < frames.length) ? frames[bci] : null;
            String invokeName;
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                invokeName = mi.owner + "." + mi.name;
            } else if (insn instanceof InvokeDynamicInsnNode) {
                invokeName = "INDY:" + ((InvokeDynamicInsnNode) insn).name;
            } else {
                invokeName = "INVOKE?";
            }

            String refusal;
            if (frame == null) {
                refusal = "UNREACHABLE";
                rejected++;
            } else {
                int stackSize = frame.getStackSize();
                int argBase = stackSize - totalSlots;
                if (argBase > 0) {
                    refusal = "REJECTED: argBase=" + argBase +
                            " (stack has " + argBase + " extra value(s) below args)";
                    rejected++;
                    // Show what's below the arg frame
                    StringBuilder below = new StringBuilder();
                    for (int s = 0; s < argBase; s++) {
                        SourceValue sv = frame.getStack(s);
                        below.append(" below[").append(s).append("]=");
                        below.append(sv != null && sv.insns != null ? sv.insns.size() + "prod" : "null");
                    }
                    refusal += below;
                } else if (argBase < 0) {
                    refusal = "ERROR: argBase<0=" + argBase;
                    rejected++;
                } else {
                    // Check per-slot reconstructibility
                    boolean ok = true;
                    StringBuilder reason = new StringBuilder("REJECTED: ");
                    for (int slot = 0; slot < totalSlots; slot++) {
                        SourceValue sv = frame.getStack(slot);
                        if (sv == null || sv.insns == null || sv.insns.size() != 1) {
                            ok = false;
                            reason.append("slot").append(slot).append(":multi-producer(")
                                  .append(sv != null && sv.insns != null ? sv.insns.size() : "null")
                                  .append(") ");
                        } else {
                            AbstractInsnNode prod = sv.insns.iterator().next();
                            if (!isReconstructible(prod)) {
                                ok = false;
                                reason.append("slot").append(slot)
                                      .append(":non-reconstructible(op=")
                                      .append(prod.getOpcode()).append(",")
                                      .append(opName(prod.getOpcode())).append(") ");
                            }
                        }
                    }
                    if (ok) {
                        refusal = "ACCEPTED";
                        accepted++;
                    } else {
                        refusal = reason.toString().trim();
                        rejected++;
                    }
                }
            }

            System.out.printf("  bci=%3d %-14s %-55s -> %s%n",
                    bci, opName(op),
                    invokeName.substring(0, Math.min(55, invokeName.length())),
                    refusal);
            bci++;
        }

        System.out.println("\n  SUMMARY: total=" + totalInvokes +
                " accepted=" + accepted + " rejected=" + rejected);

        // Run B.3 analysis to get official results
        MethodAnalysis analysis = LineMarkerTransformer.analyzeMethod(owner, mn, true);
        int officialCallsites = 0;
        if (analysis != null) {
            for (SavePoint sp : analysis.savePoints) {
                if (sp.isCallsite) officialCallsites++;
            }
        }
        System.out.println("  B.3 official callsite save points: " + officialCallsites);
        System.out.println("  B.3 analysis savepoints total: " +
                (analysis != null ? analysis.savePoints.size() : 0));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    static final class RefusalRecord {
        final int bci;
        final String invokeName;
        final String reason;

        RefusalRecord(int bci, String invokeName, String reason) {
            this.bci = bci;
            this.invokeName = invokeName;
            this.reason = reason;
        }

        @Override public String toString() {
            return "bci=" + bci + " " + invokeName + " => " + reason;
        }
    }

    static final class DiagResult {
        int totalNonTtdInvokes;
        int accepted;
        int rejected;
        List<RefusalRecord> refusalReasons = new ArrayList<>();
        int officialCallsiteCount;
        int officialSavePointCount;
    }

    /** Run full diagnosis on a specific method in a class. */
    private static DiagResult diagnoseClass(byte[] classBytes, String owner,
                                             String methodName, String methodDesc)
            throws Exception {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.EXPAND_FRAMES);

        MethodNode mn = findMethod(cn, methodName, methodDesc);
        if (mn == null) return null;

        // Inject @TimeTravelBody so analyzeMethod processes it
        if (mn.visibleAnnotations == null) mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(new AnnotationNode(LineMarkerTransformer.ANNOTATION_DESC));

        DiagResult result = new DiagResult();

        // Manual scan with SourceInterpreter
        Analyzer<SourceValue> srcAnalyzer = new Analyzer<>(new SourceInterpreter());
        Frame<SourceValue>[] frames = srcAnalyzer.analyze(owner, mn);

        int bci = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            int op = insn.getOpcode();
            if (isInvoke(op) && !isTtdCall(insn)) {
                result.totalNonTtdInvokes++;
                String desc = getDesc(insn);
                boolean isStatic = (op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEDYNAMIC);
                Type[] argTypes = Type.getArgumentTypes(desc);
                int totalSlots = isStatic ? 0 : 1;
                for (Type t : argTypes) totalSlots += t.getSize();

                Frame<SourceValue> frame = (bci < frames.length) ? frames[bci] : null;
                String invokeName = getInvokeName(insn);

                if (frame == null) {
                    result.rejected++;
                    result.refusalReasons.add(new RefusalRecord(bci, invokeName, "UNREACHABLE"));
                } else {
                    int stackSize = frame.getStackSize();
                    int argBase = stackSize - totalSlots;
                    if (argBase > 0) {
                        result.rejected++;
                        result.refusalReasons.add(new RefusalRecord(bci, invokeName,
                                "argBase=" + argBase + " (non-empty stack below args)"));
                    } else if (argBase < 0) {
                        result.rejected++;
                        result.refusalReasons.add(new RefusalRecord(bci, invokeName,
                                "argBase<0=" + argBase));
                    } else {
                        boolean ok = true;
                        StringBuilder reason = new StringBuilder();
                        for (int slot = 0; slot < totalSlots; slot++) {
                            SourceValue sv = frame.getStack(slot);
                            if (sv == null || sv.insns == null || sv.insns.size() != 1) {
                                ok = false;
                                reason.append("slot").append(slot).append(":multi-producer ");
                            } else {
                                AbstractInsnNode prod = sv.insns.iterator().next();
                                if (!isReconstructible(prod)) {
                                    ok = false;
                                    reason.append("slot").append(slot)
                                          .append(":non-reconstructible(op=")
                                          .append(opName(prod.getOpcode())).append(") ");
                                }
                            }
                        }
                        if (ok) {
                            result.accepted++;
                        } else {
                            result.rejected++;
                            result.refusalReasons.add(new RefusalRecord(bci, invokeName,
                                    reason.toString().trim()));
                        }
                    }
                }
            }
            bci++;
        }

        // Get official B.3 analysis
        MethodAnalysis analysis = LineMarkerTransformer.analyzeMethod(owner, mn, true);
        if (analysis != null) {
            result.officialSavePointCount = analysis.savePoints.size();
            for (SavePoint sp : analysis.savePoints) {
                if (sp.isCallsite) result.officialCallsiteCount++;
            }
        }

        return result;
    }

    private static void printDiagResult(DiagResult r) {
        if (r == null) { System.out.println("  [null result — method not found]"); return; }
        System.out.println("  totalNonTtdInvokes=" + r.totalNonTtdInvokes +
                " accepted=" + r.accepted + " rejected=" + r.rejected);
        System.out.println("  B.3 official: savePoints=" + r.officialSavePointCount +
                " callsites=" + r.officialCallsiteCount);
        System.out.println("  Refusals:");
        for (RefusalRecord rec : r.refusalReasons) {
            System.out.println("    " + rec);
        }
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) {
            if (name.equals(mn.name) && desc.equals(mn.desc)) return mn;
        }
        return null;
    }

    private static int countStackMapFrames(MethodNode mn) {
        // Count via instructions (FrameNode pseudo-instructions)
        int count = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn.getType() == AbstractInsnNode.FRAME) count++;
        }
        return count;
    }

    private static boolean isInvoke(int op) {
        return op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKESPECIAL ||
               op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEINTERFACE ||
               op == Opcodes.INVOKEDYNAMIC;
    }

    private static boolean isTtdCall(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode) {
            return LineMarkerTransformer.TTD_OWNER.equals(((MethodInsnNode) insn).owner);
        }
        return false;
    }

    private static String getDesc(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode) return ((MethodInsnNode) insn).desc;
        if (insn instanceof InvokeDynamicInsnNode) return ((InvokeDynamicInsnNode) insn).desc;
        return "()V";
    }

    private static String getInvokeName(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode mi = (MethodInsnNode) insn;
            return mi.owner + "." + mi.name;
        }
        if (insn instanceof InvokeDynamicInsnNode) {
            return "INDY:" + ((InvokeDynamicInsnNode) insn).name;
        }
        return "INVOKE?";
    }

    private static boolean isReconstructible(AbstractInsnNode producer) {
        int op = producer.getOpcode();
        if (op == Opcodes.ACONST_NULL) return true;
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return true;
        if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) return true;
        if (op == Opcodes.FCONST_0 || op == Opcodes.FCONST_1 || op == Opcodes.FCONST_2) return true;
        if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) return true;
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return true;
        if (op == Opcodes.LDC) return true;
        if (op == Opcodes.ILOAD || op == Opcodes.LLOAD || op == Opcodes.FLOAD
                || op == Opcodes.DLOAD || op == Opcodes.ALOAD) return true;
        return false;
    }

    private static String opName(int op) {
        switch (op) {
            case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL: return "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC: return "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
            case Opcodes.INVOKEDYNAMIC: return "INVOKEDYNAMIC";
            case Opcodes.AALOAD: return "AALOAD";
            case Opcodes.IALOAD: return "IALOAD";
            case Opcodes.GETFIELD: return "GETFIELD";
            case Opcodes.GETSTATIC: return "GETSTATIC";
            case Opcodes.ARRAYLENGTH: return "ARRAYLENGTH";
            case Opcodes.CHECKCAST: return "CHECKCAST";
            case Opcodes.IMUL: return "IMUL";
            case Opcodes.IADD: return "IADD";
            case Opcodes.ISUB: return "ISUB";
            default: return "OP" + op;
        }
    }

    /** Load a class from a jar file by its internal path, or return null if not found. */
    static byte[] loadClass(String jarPath, String classPath) {
        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry entry = jar.getJarEntry(classPath);
            if (entry == null) return null;
            try (InputStream is = jar.getInputStream(entry)) {
                return is.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
