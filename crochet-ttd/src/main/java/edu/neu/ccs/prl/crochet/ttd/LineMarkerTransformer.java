package edu.neu.ccs.prl.crochet.ttd;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import edu.neu.ccs.prl.crochet.ttd.cps.LivenessAnalyzer;
import edu.neu.ccs.prl.crochet.ttd.cps.LivenessAnalyzer.LiveLocal;

/**
 * ASM transformer for {@link TimeTravelBody}-annotated methods.
 *
 * <p><b>Phase 1 (line-marker-only)</b>: For each annotated method that has NO
 * save-point BCIs (either it has no line numbers, or the liveness analysis was
 * not requested), every entry in the method's {@code LineNumberTable} becomes
 * the insertion point for an implicit pause: a static call to
 * {@code Ttd.lineHit(ownerInternal, methodSignature, line)} is emitted
 * immediately after the line label.
 *
 * <p><b>Phase B (CPS save-frame)</b>: For each annotated method that has save
 * points (line-number BCIs with live-locals data), the transformer emits:
 * <ol>
 *   <li>A dispatch prelude at method entry: calls {@code Ttd.popResumeFrame(methodId)},
 *       checks for resume mode, restores locals, and jumps to the correct save
 *       point via {@code LOOKUPSWITCH}.</li>
 *   <li>A save-frame snippet at each save point: captures live locals into
 *       {@code long[]} and {@code Object[]} arrays, calls {@code Ttd.saveFrame}.</li>
 *   <li>At callsite save points: a "resumption shim" label placed after the
 *       save-frame and before the argument-loading instructions. The shim label
 *       is the LOOKUPSWITCH target; on resume the JVM jumps here and replays the
 *       arg loads + INVOKE from an empty operand stack.</li>
 *   <li>A synthetic {@code $ttd$registerAll()} method called from {@code <clinit>}
 *       to register method IDs and save-point debug metadata at class-load time.</li>
 * </ol>
 *
 * <p><b>Callsite save-point argument reconstructibility.</b> A callsite save
 * point is emitted only when every argument to the INVOKE can be reconstructed
 * at resume time from live locals or inline constants. Specifically, for each
 * stack slot consumed by the INVOKE, the set of instructions that produced that
 * value (per {@code Analyzer<SourceValue>}) must be a singleton whose sole
 * instruction is one of:
 * <ul>
 *   <li>A {@code *LOAD n} instruction where local {@code n} is live at the
 *       callsite BCI per B.1's liveness analysis.</li>
 *   <li>An {@code LDC} / {@code ACONST_NULL} / {@code *CONST_*} instruction
 *       (an inline constant).</li>
 * </ul>
 * If any argument fails this test, the callsite is <b>silently excluded</b> from
 * the save-point set; it is NOT an error. A method with one non-reconstructible
 * callsite still keeps all its other save points. A one-time {@code WARN}
 * message is emitted to {@code System.err} per method when at least one callsite
 * is skipped, <em>regardless</em> of the {@code -Dcrochet.ttd.debug} setting:
 * <pre>
 * WARN [Crochet TTD]: @TimeTravelBody method &lt;Owner&gt;.&lt;name&gt;&lt;desc&gt; has &lt;N&gt;
 *     callsite(s) skipped from save-point set (args not reconstructible from
 *     locals); back-step from those callsites is not supported
 * </pre>
 *
 * <p>MONITORENTER refusal is different: if a {@link TimeTravelBody} method
 * contains a {@code MONITORENTER} inside a save-point region, an
 * {@link IllegalStateException} is thrown at instrumentation time. That
 * situation means the method cannot be safely instrumented at all — it is not
 * a per-callsite issue.
 *
 * <p>Methods without the annotation are passed through unchanged. Constructors,
 * static initializers, synthetic methods (lambdas, accessor bridges), abstract
 * and native methods are also skipped.
 */
final class LineMarkerTransformer implements ClassFileTransformer {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    static final String TTD_OWNER = "edu/neu/ccs/prl/crochet/ttd/Ttd";
    static final String RESUME_FRAME_OWNER = "edu/neu/ccs/prl/crochet/ttd/ResumeFrame";
    static final String LINEHIT_DESC = "(Ljava/lang/String;Ljava/lang/String;I)V";
    static final String SAVEFRAME_DESC = "(II[J[Ljava/lang/Object;)V";
    static final String POPRESUME_DESC = "(I)Ledu/neu/ccs/prl/crochet/ttd/ResumeFrame;";
    static final String INTERNMETHODID_DESC = "(Ljava/lang/String;)I";
    static final String REGISTERMETHODLINE_DESC = "(IILjava/lang/String;)V";
    static final String ANNOTATION_DESC = "Ledu/neu/ccs/prl/crochet/ttd/TimeTravelBody;";

    /**
     * Prefix for synthetic per-method-id static int fields emitted by C.2.
     * Each annotated method gets one field: {@code $$ttd$mid$0},
     * {@code $$ttd$mid$1}, etc. Slot indices are assigned in the order
     * analyses are visited (ClassNode.methods order — stable per class file).
     */
    static final String TTD_MID_FIELD_PREFIX = "$$ttd$mid$";
    /** JVM descriptor for the per-method-id static int fields. */
    static final String TTD_MID_FIELD_DESC = "I";

    /** Name of the synthetic class-init helper emitted at {@code visitEnd()}. */
    static final String REGISTER_ALL_METHOD = "$ttd$registerAll";
    static final String REGISTER_ALL_DESC = "()V";

    /**
     * Set of fully-qualified method FQNs ({@code "owner.name+desc"}) for which a
     * callsite-skipped WARN has already been emitted.  Prevents duplicate warnings
     * when the same class is retransformed or the transformer is applied multiple times.
     * Uses {@code Boolean.TRUE} as the sentinel value (ConcurrentHashMap does not
     * support Set semantics directly).
     */
    private static final ConcurrentHashMap<String, Boolean> WARNED_METHODS =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // ClassFileTransformer entry point
    // -------------------------------------------------------------------------

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null) return null;
        // Skip bootstrap-loader classes and our own runtime to avoid loops.
        if (className.startsWith("java/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("net/jonbell/crochet/")
                || className.startsWith("edu/neu/ccs/prl/crochet/ttd/shaded/")) {
            return null;
        }
        // Quick pre-filter: does the class file mention our annotation?
        if (!classMentionsAnnotation(classfileBuffer)) {
            return null;
        }
        if (Boolean.getBoolean("crochet.ttd.debug")) {
            System.err.println("[ttd] transforming " + className);
        }
        try {
            // Pass 1: build ClassNode for analysis.
            ClassNode cn = new ClassNode();
            new ClassReader(classfileBuffer).accept(cn, ClassReader.EXPAND_FRAMES);

            // Pass 2: run liveness analysis and collect save-point data for
            // each annotated method.
            List<MethodAnalysis> analyses = analyzeClass(cn);

            // Pass 3: emit transformed bytecode.
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new TtdSafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES, loader);
            cr.accept(new TtdClassVisitor(cw, cn.name, analyses), ClassReader.EXPAND_FRAMES);
            return cw.toByteArray();
        } catch (Throwable t) {
            if (Boolean.getBoolean("crochet.ttd.debug")) {
                System.err.println("[ttd] FAILED to transform " + className + ": " + t);
                t.printStackTrace(System.err);
            }
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1 pre-filter
    // -------------------------------------------------------------------------

    private static boolean classMentionsAnnotation(byte[] classfile) {
        byte[] needle = ANNOTATION_DESC.getBytes();
        outer:
        for (int i = 0; i + needle.length <= classfile.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classfile[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Analysis structures
    // -------------------------------------------------------------------------

    /**
     * One save point: either a line-marker BCI or a callsite BCI.
     *
     * <p>For a <em>line-marker save point</em>: {@code argStartBci == bci} and
     * {@code shimArgs} is empty. The save-frame and the body label are both
     * placed at {@code bci}.
     *
     * <p>For a <em>callsite save point</em>: {@code bci} is the instruction index
     * of the INVOKE instruction (used as the LOOKUPSWITCH key and the
     * {@code ResumeFrame.bci} value). {@code argStartBci} is the instruction
     * index of the earliest arg-producing instruction (the LOOKUPSWITCH target
     * label = the "shim label" = the resume entry point). {@code shimArgs} is
     * the list of instructions to re-emit in the shim (in original order).
     * The save-frame is emitted at {@code argStartBci} (stack empty there).
     */
    static final class SavePoint {
        /** BCI of the LOOKUPSWITCH key and ResumeFrame.bci. For line markers, this is
         *  the LineNumberNode's BCI. For callsites, this is the INVOKE's BCI. */
        final int bci;
        /** Source line number for registration label. */
        final int lineNumber;
        /** All live locals at this BCI, sorted ascending by slot. */
        final List<LiveLocal> liveLocals;
        /** Subset of liveLocals that are primitive types, sorted. */
        final List<LiveLocal> livePrems;
        /** Subset of liveLocals that are reference types, sorted. */
        final List<LiveLocal> liveRefs;

        /**
         * Instruction index where arg loading begins (the "shim label" target).
         * For line-marker save points: equal to {@code bci}.
         * For callsite save points: the earliest arg-producing instruction index.
         */
        final int argStartBci;

        /**
         * True if this is a callsite save point; false if a line-marker save point.
         */
        final boolean isCallsite;

        /**
         * For callsite save points: the instructions to re-emit in the shim
         * (the producing instruction for each stack argument, in stack order).
         * Empty for line-marker save points.
         */
        final List<AbstractInsnNode> shimArgs;

        /** Constructor for line-marker save points. */
        SavePoint(int bci, int lineNumber, List<LiveLocal> liveLocals) {
            this(bci, lineNumber, liveLocals, bci, false, Collections.emptyList());
        }

        /** Constructor for callsite save points. */
        SavePoint(int bci, int lineNumber, List<LiveLocal> liveLocals,
                  int argStartBci, boolean isCallsite,
                  List<AbstractInsnNode> shimArgs) {
            this.bci = bci;
            this.lineNumber = lineNumber;
            this.liveLocals = liveLocals;
            this.argStartBci = argStartBci;
            this.isCallsite = isCallsite;
            this.shimArgs = Collections.unmodifiableList(new ArrayList<>(shimArgs));
            List<LiveLocal> prems = new ArrayList<>();
            List<LiveLocal> refs = new ArrayList<>();
            for (LiveLocal ll : liveLocals) {
                int sort = ll.type().getSort();
                if (sort == Type.OBJECT || sort == Type.ARRAY) {
                    refs.add(ll);
                } else {
                    prems.add(ll);
                }
            }
            this.livePrems = Collections.unmodifiableList(prems);
            this.liveRefs = Collections.unmodifiableList(refs);
        }
    }

    /** Per-method analysis result. */
    static final class MethodAnalysis {
        /** Internal method key: {@code "className.methodName+descriptor"}. */
        final String methodIdKey;
        /** The method node this analysis applies to. */
        final MethodNode mn;
        /** Save points sorted ascending by BCI. */
        final List<SavePoint> savePoints;
        /** Fast lookup from BCI to SavePoint. */
        final Map<Integer, SavePoint> byBci;
        /**
         * Fast lookup from argStartBci to SavePoint (for callsite save points).
         * For line-marker save points, argStartBci == bci so they appear in both
         * byBci and byArgStartBci.
         */
        final Map<Integer, SavePoint> byArgStartBci;

        MethodAnalysis(String methodIdKey, MethodNode mn, List<SavePoint> savePoints) {
            this.methodIdKey = methodIdKey;
            this.mn = mn;
            this.savePoints = Collections.unmodifiableList(savePoints);
            Map<Integer, SavePoint> map = new TreeMap<>();
            Map<Integer, SavePoint> argMap = new TreeMap<>();
            for (SavePoint sp : savePoints) {
                map.put(sp.bci, sp);
                argMap.put(sp.argStartBci, sp);
            }
            this.byBci = Collections.unmodifiableMap(map);
            this.byArgStartBci = Collections.unmodifiableMap(argMap);
        }
    }

    // -------------------------------------------------------------------------
    // Analysis pass
    // -------------------------------------------------------------------------

    /**
     * For each annotated method in the ClassNode, run liveness analysis and
     * build a {@link MethodAnalysis}. Methods that are skipped (constructors,
     * synthetic, etc.) produce no entry.
     */
    private static List<MethodAnalysis> analyzeClass(ClassNode cn) {
        List<MethodAnalysis> result = new ArrayList<>();
        for (MethodNode mn : cn.methods) {
            if (!isEligible(mn)) continue;
            if (!hasAnnotation(mn)) continue;
            MethodAnalysis analysis = analyzeMethod(cn.name, mn);
            if (analysis != null) {
                result.add(analysis);
            }
        }
        return result;
    }

    static boolean isEligible(MethodNode mn) {
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) return false;
        int syntheticFlags = Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE;
        return (mn.access & syntheticFlags) == 0;
    }

    private static boolean hasAnnotation(MethodNode mn) {
        if (mn.visibleAnnotations == null) return false;
        for (Object a : mn.visibleAnnotations) {
            if (a instanceof org.objectweb.asm.tree.AnnotationNode) {
                org.objectweb.asm.tree.AnnotationNode an =
                        (org.objectweb.asm.tree.AnnotationNode) a;
                if (ANNOTATION_DESC.equals(an.desc)) return true;
            }
        }
        return false;
    }

    /**
     * Run liveness analysis on one annotated method and produce a
     * {@link MethodAnalysis}. Returns {@code null} if the method has no line
     * number information (falls back to Phase 1 line-hit-only mode).
     */
    static MethodAnalysis analyzeMethod(String ownerInternalName, MethodNode mn) {
        return analyzeMethod(ownerInternalName, mn, true);
    }

    /**
     * Run liveness analysis on one annotated method and produce a
     * {@link MethodAnalysis}.
     *
     * @param includeCallsites if true, also emit callsite save points for
     *                         INVOKE instructions with reconstructible arguments
     */
    static MethodAnalysis analyzeMethod(String ownerInternalName, MethodNode mn,
                                        boolean includeCallsites) {
        // Collect line-number BCIs and callsite candidate BCIs.
        // Also check for MONITORENTER violations.
        Set<Integer> lineBcis = new LinkedHashSet<>();
        Set<Integer> allCandidateBcis = new LinkedHashSet<>();
        int monitorDepth = 0;
        int bci = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            int op = insn.getOpcode();
            if (op == Opcodes.MONITORENTER) {
                monitorDepth++;
            } else if (op == Opcodes.MONITOREXIT) {
                if (monitorDepth > 0) monitorDepth--;
            }
            if (insn instanceof LineNumberNode) {
                if (monitorDepth > 0) {
                    throwMonitorenterRefusal(ownerInternalName, mn);
                }
                lineBcis.add(bci);
                allCandidateBcis.add(bci);
            }
            // Callsite candidates: non-TTD INVOKE instructions outside monitors.
            if (includeCallsites && isNonTtdInvokeInsn(insn)) {
                if (monitorDepth > 0) {
                    throwMonitorenterRefusal(ownerInternalName, mn);
                }
                allCandidateBcis.add(bci);
            }
            bci++;
        }

        if (allCandidateBcis.isEmpty()) {
            return null;
        }

        // Run liveness analysis over all candidate BCIs.
        LivenessAnalyzer analyzer = new LivenessAnalyzer();
        Map<Integer, List<LiveLocal>> liveness;
        try {
            liveness = analyzer.analyze(ownerInternalName, mn, allCandidateBcis);
        } catch (AnalyzerException e) {
            if (Boolean.getBoolean("crochet.ttd.debug")) {
                System.err.println("[ttd] liveness analysis failed for "
                        + ownerInternalName + "." + mn.name + mn.desc + ": " + e);
            }
            return null;
        }

        // Run SourceValue analysis for callsite argument reconstructibility.
        // We run this even if includeCallsites is false for simplicity; it's
        // lightweight on methods without INVOKE instructions.
        Analyzer<SourceValue> srcAnalyzer = new Analyzer<>(new SourceInterpreter());
        SourceValue[][] sourceFrames = null;
        if (includeCallsites) {
            try {
                org.objectweb.asm.tree.analysis.Frame<SourceValue>[] frames =
                        srcAnalyzer.analyze(ownerInternalName, mn);
                // Extract just the stack portion at each instruction.
                // frames[i] is the frame BEFORE instruction i executes.
                sourceFrames = new SourceValue[frames.length][];
                for (int i = 0; i < frames.length; i++) {
                    if (frames[i] == null) {
                        sourceFrames[i] = new SourceValue[0];
                        continue;
                    }
                    int sz = frames[i].getStackSize();
                    sourceFrames[i] = new SourceValue[sz];
                    for (int j = 0; j < sz; j++) {
                        sourceFrames[i][j] = frames[i].getStack(j);
                    }
                }
            } catch (AnalyzerException e) {
                if (Boolean.getBoolean("crochet.ttd.debug")) {
                    System.err.println("[ttd] source analysis failed for "
                            + ownerInternalName + "." + mn.name + mn.desc + ": " + e);
                }
                // Fall back to line-only save points.
                sourceFrames = null;
            }
        }

        // Build save points; collect line numbers from instruction list.
        Map<Integer, Integer> bciToLine = new HashMap<>();
        bci = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof LineNumberNode) {
                bciToLine.put(bci, ((LineNumberNode) insn).line);
            }
            bci++;
        }

        // Index instructions by BCI for fast lookup.
        AbstractInsnNode[] insnArray = new AbstractInsnNode[mn.instructions.size()];
        bci = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            insnArray[bci++] = insn;
        }

        // Track which BCIs are taken (to avoid duplicate save points
        // if a line marker and callsite share the same BCI).
        Set<Integer> usedBcis = new LinkedHashSet<>();
        // Track which argStartBcis are taken (to avoid two callsites
        // whose arg-load sequences start at the same instruction).
        Set<Integer> usedArgStartBcis = new LinkedHashSet<>();

        List<SavePoint> savePoints = new ArrayList<>();

        // First: line-marker save points.
        for (int lineBci : lineBcis) {
            List<LiveLocal> live = liveness.get(lineBci);
            if (live == null) live = Collections.emptyList();
            int lineNumber = bciToLine.getOrDefault(lineBci, 0);
            SavePoint sp = new SavePoint(lineBci, lineNumber, live);
            savePoints.add(sp);
            usedBcis.add(lineBci);
            usedArgStartBcis.add(lineBci);
        }

        // Second: callsite save points (only when sourceFrames is available).
        int callsiteCandidateCount = 0; // total non-TTD INVOKE insns considered
        int callsiteAcceptedCount = 0;  // those that became save points
        if (includeCallsites && sourceFrames != null) {
            bci = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (isNonTtdInvokeInsn(insn) && !usedBcis.contains(bci)) {
                    callsiteCandidateCount++;
                    // This is a callsite BCI not already used as a line-marker save point.
                    int invokeBci = bci;
                    List<LiveLocal> live = liveness.get(invokeBci);
                    if (live == null) live = Collections.emptyList();
                    int lineNumber = bciToLine.getOrDefault(invokeBci, 0);

                    // Determine the argument types and count for this INVOKE.
                    String invokeDesc = getInvokeDescriptor(insn);
                    boolean isStatic = (insn.getOpcode() == Opcodes.INVOKESTATIC
                            || insn.getOpcode() == Opcodes.INVOKEDYNAMIC);
                    Type[] argTypes = Type.getArgumentTypes(invokeDesc);
                    // Total arg slots = sum of arg sizes (+ 1 for receiver if non-static).
                    int totalSlots = 0;
                    if (!isStatic) totalSlots++; // receiver
                    for (Type t : argTypes) totalSlots += t.getSize();

                    // At the INVOKE instruction (bci = invokeBci), the frame BEFORE
                    // execution has totalSlots values on the stack (the args + receiver).
                    // sourceFrames[invokeBci] is the frame state before the INVOKE executes.
                    if (invokeBci >= sourceFrames.length || sourceFrames[invokeBci] == null) {
                        bci++;
                        continue; // unreachable or dead code
                    }
                    SourceValue[] stackAtInvoke = sourceFrames[invokeBci];
                    if (stackAtInvoke.length < totalSlots) {
                        // Not enough stack slots — shouldn't happen with valid bytecode.
                        bci++;
                        continue;
                    }

                    // Examine each arg-stack slot for reconstructibility.
                    // Stack layout: bottommost slot is the deepest (oldest pushed) value.
                    // The top |totalSlots| entries are the args for this INVOKE.
                    int argBase = stackAtInvoke.length - totalSlots;

                    // If argBase > 0, there are stack values BELOW the argument frame at
                    // the INVOKE bci. The save-frame snippet is inserted at argStartBci
                    // (the first arg-loading instruction), but if the stack is non-empty
                    // there, the emitted bytecode fails the verifier (save-frame requires
                    // an empty operand stack). Silently refuse this callsite.
                    //
                    // Example: `ICONST_1; ALOAD_0; INVOKEVIRTUAL hashCode; IADD`
                    // At the INVOKEVIRTUAL, argBase = 1 (ICONST_1 sits below ALOAD_0).
                    // The argStartBci is ALOAD_0's bci, but the stack already has [1].
                    if (argBase > 0) {
                        bci++;
                        continue; // silently refuse — consistent with other refusal policies
                    }

                    boolean reconstructible = true;
                    int argStartBciCandidate = invokeBci; // will be min of all arg-producing bcis
                    List<AbstractInsnNode> shimArgInsns = new ArrayList<>();

                    for (int slot = 0; slot < totalSlots && reconstructible; slot++) {
                        SourceValue sv = stackAtInvoke[argBase + slot];
                        if (sv == null || sv.insns == null || sv.insns.size() != 1) {
                            // Multiple producing instructions (join point) or unknown.
                            reconstructible = false;
                            if (Boolean.getBoolean("crochet.ttd.debug")) {
                                System.err.println("[ttd] callsite at bci=" + invokeBci
                                        + " in " + ownerInternalName + "." + mn.name + mn.desc
                                        + ": arg slot " + slot + " has multiple/unknown producers"
                                        + " — refusing callsite save point");
                            }
                            break;
                        }
                        AbstractInsnNode producer = sv.insns.iterator().next();
                        if (!isReconstructibleProducer(producer, live)) {
                            reconstructible = false;
                            if (Boolean.getBoolean("crochet.ttd.debug")) {
                                System.err.println("[ttd] callsite at bci=" + invokeBci
                                        + " in " + ownerInternalName + "." + mn.name + mn.desc
                                        + ": arg slot " + slot + " produced by non-reconstructible insn "
                                        + producer.getOpcode() + " — refusing callsite save point");
                            }
                            break;
                        }
                        int producerBci = mn.instructions.indexOf(producer);
                        if (producerBci < argStartBciCandidate) {
                            argStartBciCandidate = producerBci;
                        }
                        shimArgInsns.add(producer);
                    }

                    if (!reconstructible) {
                        bci++;
                        continue; // silently skip non-reconstructible callsites
                    }

                    // Verify we won't conflict with an existing save point's argStartBci.
                    if (usedArgStartBcis.contains(argStartBciCandidate)) {
                        // Two save points would share the same argStartBci label — skip.
                        bci++;
                        continue;
                    }

                    SavePoint sp = new SavePoint(invokeBci, lineNumber, live,
                            argStartBciCandidate, true, shimArgInsns);
                    savePoints.add(sp);
                    usedBcis.add(invokeBci);
                    usedArgStartBcis.add(argStartBciCandidate);
                    callsiteAcceptedCount++;
                }
                bci++;
            }
        }

        // Emit a one-time WARN per method when callsites were skipped.
        // This fires regardless of -Dcrochet.ttd.debug (users on default logging
        // still see the heads-up that some back-step targets are not available).
        int callsiteSkippedCount = callsiteCandidateCount - callsiteAcceptedCount;
        if (callsiteSkippedCount > 0) {
            String methodFqn = ownerInternalName + "." + mn.name + mn.desc;
            if (WARNED_METHODS.putIfAbsent(methodFqn, Boolean.TRUE) == null) {
                System.err.println("WARN [Crochet TTD]: @TimeTravelBody method "
                        + methodFqn + " has " + callsiteSkippedCount
                        + " callsite(s) skipped from save-point set"
                        + " (args not reconstructible from locals);"
                        + " back-step from those callsites is not supported");
            }
        }

        if (savePoints.isEmpty()) {
            return null;
        }

        // Sort by BCI for determinism (gate 18).
        savePoints.sort((a, b) -> Integer.compare(a.bci, b.bci));

        String methodIdKey = ownerInternalName + "." + mn.name + mn.desc;
        return new MethodAnalysis(methodIdKey, mn, savePoints);
    }

    /**
     * Returns true if {@code producer} is a reconstructible source of an
     * operand stack value at a callsite resume shim:
     * <ul>
     *   <li>A {@code *LOAD n} instruction (ILOAD, LLOAD, FLOAD, DLOAD, ALOAD),
     *       where local n is live at the callsite.</li>
     *   <li>An {@code LDC}, {@code ACONST_NULL}, or any {@code *CONST_*}
     *       instruction (inline constant).</li>
     * </ul>
     */
    private static boolean isReconstructibleProducer(AbstractInsnNode producer,
                                                     List<LiveLocal> liveAtCallsite) {
        int op = producer.getOpcode();
        // ACONST_NULL and *CONST_* family
        if (op == Opcodes.ACONST_NULL) return true;
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return true;
        if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) return true;
        if (op == Opcodes.FCONST_0 || op == Opcodes.FCONST_1 || op == Opcodes.FCONST_2) return true;
        if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) return true;
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return true;
        if (op == Opcodes.LDC) return true;
        // *LOAD instructions
        if (op == Opcodes.ILOAD || op == Opcodes.LLOAD || op == Opcodes.FLOAD
                || op == Opcodes.DLOAD || op == Opcodes.ALOAD) {
            int slot = ((VarInsnNode) producer).var;
            // Verify the slot is live at the callsite.
            for (LiveLocal ll : liveAtCallsite) {
                if (ll.slotIndex() == slot) return true;
                // For category-2 types, the second slot references the first.
                if (ll.type().getSize() == 2 && ll.slotIndex() + 1 == slot) return true;
            }
            return false;
        }
        return false;
    }

    /**
     * Extract the method descriptor from an INVOKE instruction node.
     */
    private static String getInvokeDescriptor(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode) {
            return ((MethodInsnNode) insn).desc;
        }
        if (insn instanceof InvokeDynamicInsnNode) {
            return ((InvokeDynamicInsnNode) insn).desc;
        }
        throw new IllegalArgumentException("Not an invoke instruction: " + insn.getClass());
    }

    private static boolean isNonTtdInvokeInsn(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op != Opcodes.INVOKEVIRTUAL && op != Opcodes.INVOKESPECIAL
                && op != Opcodes.INVOKESTATIC && op != Opcodes.INVOKEINTERFACE
                && op != Opcodes.INVOKEDYNAMIC) {
            return false;
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode mi = (MethodInsnNode) insn;
            if (TTD_OWNER.equals(mi.owner)) return false;
        }
        return true;
    }

    private static void throwMonitorenterRefusal(String owner, MethodNode mn) {
        throw new IllegalStateException(
                "@TimeTravelBody method " + owner + "." + mn.name + mn.desc
                        + " contains MONITORENTER inside a save-point region"
                        + " — synchronized blocks are not currently supported"
                        + " in resumable code.");
    }

    // -------------------------------------------------------------------------
    // Emission pass: ClassVisitor
    // -------------------------------------------------------------------------

    private static final class TtdClassVisitor extends ClassVisitor {
        private final String ownerInternal;
        /** Keyed by {@code "methodName+descriptor"}. */
        private final Map<String, MethodAnalysis> analysisByKey;
        /**
         * Registration calls to emit in {@code $ttd$registerAll()}.
         * Each entry: [methodIdKey, bci, label].
         */
        private final List<String[]> registrations = new ArrayList<>();
        private boolean hasClinitAlready = false;

        /**
         * C.2: Map from methodIdKey → per-class slot index (0, 1, …).
         * Slot index N corresponds to the synthetic field {@code $$ttd$mid$N}.
         * Populated in {@code visitMethod} order so that assignment is stable
         * across rebuilds (universal gate 18).
         */
        private final Map<String, Integer> methodIdSlots = new HashMap<>();

        TtdClassVisitor(ClassVisitor delegate, String ownerInternal,
                        List<MethodAnalysis> analyses) {
            super(Opcodes.ASM9, delegate);
            this.ownerInternal = ownerInternal;
            this.analysisByKey = new HashMap<>();
            for (MethodAnalysis a : analyses) {
                analysisByKey.put(a.mn.name + a.mn.desc, a);
            }
        }

        /**
         * Assign a per-class slot index for {@code methodIdKey} if not already
         * present. Returns the (possibly newly-assigned) slot index.
         */
        private int slotFor(String methodIdKey) {
            return methodIdSlots.computeIfAbsent(methodIdKey,
                    k -> methodIdSlots.size());
        }

        /** Return the synthetic field name for slot {@code slotIdx}. */
        static String midFieldName(int slotIdx) {
            return TTD_MID_FIELD_PREFIX + slotIdx;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("<clinit>".equals(name)) {
                hasClinitAlready = true;
                // Inject a call to $ttd$registerAll() just before every RETURN.
                return new ClinitInjector(mv, ownerInternal);
            }
            // Skip non-eligible methods early.
            if ("<init>".equals(name)) return mv;
            if ((access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return mv;
            }
            MethodAnalysis analysis = analysisByKey.get(name + descriptor);
            if (analysis == null) {
                // Not an annotated method with save points — check for Phase 1.
                return new Phase1MethodVisitor(mv, ownerInternal, name + descriptor);
            }
            // C.2: assign slot index for this method's id before emitting,
            // then pre-compute the field name as a plain String so that
            // SuppressingMethodVisitor and CpsMethodEmitter hold no reference
            // to TtdClassVisitor — avoiding Crochet's $$crochetAccess()
            // injection on TtdClassVisitor when those inner classes are
            // retransformed by the Crochet agent.
            int slot = slotFor(analysis.methodIdKey);
            String fieldName = midFieldName(slot);
            // CPS emitter: suppress original bytecode, replay from MethodNode.
            return new SuppressingMethodVisitor(mv, ownerInternal, analysis,
                    registrations, fieldName);
        }

        @Override
        public void visitEnd() {
            if (!analysisByKey.isEmpty()) {
                // C.2: emit one synthetic static int field per annotated method.
                emitMethodIdFields();
                // Emit the $ttd$registerAll() synthetic helper.
                emitRegisterAll();
                // If there was no <clinit>, emit one that calls $ttd$registerAll().
                if (!hasClinitAlready) {
                    emitSyntheticClinit();
                }
            }
            super.visitEnd();
        }

        /**
         * C.2: Emit one {@code private static synthetic int $$ttd$mid$N} field
         * for each unique methodIdKey collected during visitMethod.
         */
        private void emitMethodIdFields() {
            for (Map.Entry<String, Integer> entry : methodIdSlots.entrySet()) {
                String fieldName = midFieldName(entry.getValue());
                super.visitField(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        fieldName, TTD_MID_FIELD_DESC, null, null).visitEnd();
            }
        }

        private void emitRegisterAll() {
            MethodVisitor mv = super.visitMethod(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    REGISTER_ALL_METHOD, REGISTER_ALL_DESC, null, null);
            mv.visitCode();

            // C.2: First, initialise the per-method id fields.
            // For each unique methodIdKey, call internMethodId once and PUTSTATIC.
            // Use a sorted iteration over slot indices for deterministic emission.
            String[] keysBySlot = new String[methodIdSlots.size()];
            for (Map.Entry<String, Integer> entry : methodIdSlots.entrySet()) {
                keysBySlot[entry.getValue()] = entry.getKey();
            }
            for (int slot = 0; slot < keysBySlot.length; slot++) {
                String methodIdKey = keysBySlot[slot];
                mv.visitLdcInsn(methodIdKey);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER,
                        "internMethodId", INTERNMETHODID_DESC, false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, ownerInternal,
                        midFieldName(slot), TTD_MID_FIELD_DESC);
            }

            // Then register all save-point debug entries.
            for (String[] reg : registrations) {
                String methodIdKey = reg[0];
                int bci = Integer.parseInt(reg[1]);
                String label = reg[2];
                // int methodId = $$ttd$mid$N (already initialised above)
                int slot = methodIdSlots.get(methodIdKey);
                mv.visitFieldInsn(Opcodes.GETSTATIC, ownerInternal,
                        midFieldName(slot), TTD_MID_FIELD_DESC);
                // Ttd.registerMethodLine(methodId, bci, label);
                mv.visitLdcInsn(bci);
                mv.visitLdcInsn(label);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER,
                        "registerMethodLine", REGISTERMETHODLINE_DESC, false);
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(3, 0);
            mv.visitEnd();
        }

        private void emitSyntheticClinit() {
            MethodVisitor mv = super.visitMethod(
                    Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, ownerInternal,
                    REGISTER_ALL_METHOD, REGISTER_ALL_DESC, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    // -------------------------------------------------------------------------
    // <clinit> injector: injects call to $ttd$registerAll() before each RETURN
    // -------------------------------------------------------------------------

    private static final class ClinitInjector extends MethodVisitor {
        private final String ownerInternal;

        ClinitInjector(MethodVisitor delegate, String ownerInternal) {
            super(Opcodes.ASM9, delegate);
            this.ownerInternal = ownerInternal;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ownerInternal,
                        REGISTER_ALL_METHOD, REGISTER_ALL_DESC, false);
            }
            super.visitInsn(opcode);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1 fallback: emit lineHit calls only (no CPS)
    // -------------------------------------------------------------------------

    private static final class Phase1MethodVisitor extends MethodVisitor {
        private final String ownerInternal;
        private final String methodSignature;
        private boolean annotated;

        Phase1MethodVisitor(MethodVisitor delegate, String ownerInternal,
                            String methodSignature) {
            super(Opcodes.ASM9, delegate);
            this.ownerInternal = ownerInternal;
            this.methodSignature = methodSignature;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (ANNOTATION_DESC.equals(descriptor)) annotated = true;
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            if (!annotated) return;
            mv.visitLdcInsn(ownerInternal);
            mv.visitLdcInsn(methodSignature);
            mv.visitLdcInsn(line);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER, "lineHit",
                    LINEHIT_DESC, false);
        }
    }

    // -------------------------------------------------------------------------
    // CPS method emitter: suppress ClassReader events, replay from MethodNode
    // -------------------------------------------------------------------------

    /**
     * Swallows all ClassReader events for the method. At {@link #visitEnd()},
     * delegates to {@link CpsMethodEmitter} to replay from the MethodNode.
     */
    private static final class SuppressingMethodVisitor extends MethodVisitor {
        private final MethodVisitor realWriter;
        private final String ownerInternal;
        private final MethodAnalysis analysis;
        private final List<String[]> registrations;
        /**
         * C.2: pre-computed field name for this method's interned id.
         * Stored as a plain String (not a reference to {@code TtdClassVisitor})
         * to avoid Crochet's {@code $$crochetAccess()} injection on
         * {@code TtdClassVisitor} when this visitor itself is retransformed
         * by the Crochet agent.
         */
        private final String midFieldName;

        SuppressingMethodVisitor(MethodVisitor realWriter, String ownerInternal,
                                  MethodAnalysis analysis, List<String[]> registrations,
                                  String midFieldName) {
            // Pass null as delegate — we suppress all events.
            super(Opcodes.ASM9, null);
            this.realWriter = realWriter;
            this.ownerInternal = ownerInternal;
            this.analysis = analysis;
            this.registrations = registrations;
            this.midFieldName = midFieldName;
        }

        @Override
        public void visitEnd() {
            // Add registrations for all save points.
            for (SavePoint sp : analysis.savePoints) {
                String label;
                if (sp.isCallsite) {
                    label = ownerInternal + "." + analysis.mn.name
                            + analysis.mn.desc + ":callsite@" + sp.bci;
                } else {
                    label = ownerInternal + "." + analysis.mn.name
                            + analysis.mn.desc + ":" + sp.lineNumber;
                }
                registrations.add(new String[]{
                        analysis.methodIdKey,
                        Integer.toString(sp.bci),
                        label
                });
            }
            // Emit the CPS-transformed method body.
            CpsMethodEmitter emitter = new CpsMethodEmitter(
                    realWriter, ownerInternal, analysis, midFieldName);
            emitter.emit();
        }

        // All other MethodVisitor events: suppress (do nothing).
        @Override public AnnotationVisitor visitAnnotation(String d, boolean v) { return null; }
        @Override public AnnotationVisitor visitAnnotationDefault() { return null; }
        @Override public void visitAttribute(org.objectweb.asm.Attribute a) {}
        @Override public void visitParameter(String n, int a) {}
        @Override public AnnotationVisitor visitParameterAnnotation(int p, String d, boolean v) { return null; }
        @Override public void visitCode() {}
        @Override public void visitFrame(int t, int nl, Object[] lo, int ns, Object[] so) {}
        @Override public void visitInsn(int op) {}
        @Override public void visitIntInsn(int op, int op2) {}
        @Override public void visitVarInsn(int op, int v) {}
        @Override public void visitTypeInsn(int op, String t) {}
        @Override public void visitFieldInsn(int op, String o, String n, String d) {}
        @Override public void visitMethodInsn(int op, String o, String n, String d, boolean i) {}
        @Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle h, Object... a) {}
        @Override public void visitJumpInsn(int op, Label l) {}
        @Override public void visitLabel(Label l) {}
        @Override public void visitLdcInsn(Object c) {}
        @Override public void visitIincInsn(int v, int i) {}
        @Override public void visitTableSwitchInsn(int mn, int mx, Label d, Label... l) {}
        @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) {}
        @Override public void visitMultiANewArrayInsn(String d, int dims) {}
        @Override public void visitTryCatchBlock(Label s, Label e, Label h, String t) {}
        @Override public void visitLocalVariable(String n, String d, String s, Label st, Label en, int i) {}
        @Override public void visitLineNumber(int l, Label s) {}
        @Override public void visitMaxs(int ms, int ml) {}
    }

    // -------------------------------------------------------------------------
    // Core CPS emitter
    // -------------------------------------------------------------------------

    /**
     * Emits the CPS-transformed method: dispatch prelude + body with save-frame
     * snippets at each save point.
     *
     * <p><b>Save point layout in the emitted bytecode:</b>
     *
     * <p><em>Line-marker save point</em> at BCI N:
     * <pre>
     *   bodyLabel_N:          // LOOKUPSWITCH target; stack empty here
     *   [save-frame snippet]  // operand stack must be empty
     *   [lineHit call]
     *   [original instruction at N]
     * </pre>
     *
     * <p><em>Callsite save point</em> with INVOKE at BCI N, arg-start at M ≤ N:
     * <pre>
     *   [original instructions M-1, M-2, ...] // normal body up to argStartBci
     *   [save-frame snippet]  // operand stack is empty at argStartBci
     *   bodyLabel_N:          // LOOKUPSWITCH target ("shim label"); stack empty
     *   [original arg-load instructions M, M+1, ...] // resume replays these
     *   [original INVOKE at N]
     * </pre>
     *
     * The restore block in the prelude for callsite save point N restores locals
     * from {@code frame.prims} / {@code frame.refs} and then jumps to
     * {@code bodyLabel_N} (the shim label), which is placed at {@code argStartBci}
     * — AFTER the save-frame, so the operand stack is empty when the
     * LOOKUPSWITCH jumps here.
     */
    private static final class CpsMethodEmitter {
        private final MethodVisitor mv;
        private final String ownerInternal;
        private final MethodAnalysis analysis;
        /** Slot of the {@code ResumeFrame} local beyond maxLocals. */
        private final int resumeSlot;
        /** Map from slot index → declared descriptor (from LVT + parameter types). */
        private final Map<Integer, String> declaredRefTypes;
        /**
         * C.2: name of the synthetic {@code $$ttd$mid$N} field for this method's
         * interned id.  Pre-computed from the class visitor's slot map at
         * construction time, so {@code CpsMethodEmitter} holds no reference to
         * {@code TtdClassVisitor} — avoiding a cross-reference that triggers
         * Crochet's {@code $$crochetAccess()} injection when the emitter itself
         * gets retransformed by the Crochet agent.
         */
        private final String midFieldName;

        CpsMethodEmitter(MethodVisitor mv, String ownerInternal, MethodAnalysis analysis,
                         String midFieldName) {
            this.mv = mv;
            this.ownerInternal = ownerInternal;
            this.analysis = analysis;
            this.resumeSlot = analysis.mn.maxLocals;
            this.declaredRefTypes = buildDeclaredRefTypes(analysis.mn);
            this.midFieldName = midFieldName;
        }

        /**
         * C.2: emit {@code GETSTATIC ownerInternal.$$ttd$mid$N I} where
         * {@code $$ttd$mid$N} was pre-computed at construction time from the
         * class visitor's slot map.  Replaces the old
         * {@code LDC methodIdKey; INVOKESTATIC internMethodId} pattern, saving
         * one String CP entry and eliminating the ConcurrentHashMap lookup from
         * the runtime hot path.
         */
        private void emitGetMethodId() {
            mv.visitFieldInsn(Opcodes.GETSTATIC, ownerInternal,
                    midFieldName, TTD_MID_FIELD_DESC);
        }

        void emit() {
            MethodNode mn = analysis.mn;
            mv.visitCode();

            // ------------------------------------------------------------------
            // Emit try-catch blocks (must come before instructions in the
            // MethodVisitor streaming protocol; ASM collects them and writes
            // them to the class file's exception_table at toByteArray() time).
            //
            // The SuppressingMethodVisitor swallows visitTryCatchBlock events
            // from the ClassReader pass, so we must replay them here from the
            // MethodNode. The Label objects in TryCatchBlockNode are the same
            // Label objects that will appear in the instruction stream when
            // insn.accept(mv) is called below — ASM resolves them at toByteArray()
            // time, so the relative coverage is preserved correctly.
            // ------------------------------------------------------------------
            if (mn.tryCatchBlocks != null) {
                for (org.objectweb.asm.tree.TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                    tcb.accept(mv);
                }
            }

            // ------------------------------------------------------------------
            // Dispatch prelude
            // ------------------------------------------------------------------
            // Labels for the LOOKUPSWITCH targets.
            // For line-marker save points: bodyLabel is placed at argStartBci (== bci).
            // For callsite save points: bodyLabel is the shim label placed at argStartBci.
            // The LOOKUPSWITCH key is always sp.bci.
            Map<Integer, Label> restoreLabels = new TreeMap<>();
            Map<Integer, Label> bodyLabels = new TreeMap<>();
            for (SavePoint sp : analysis.savePoints) {
                restoreLabels.put(sp.bci, new Label());
                bodyLabels.put(sp.bci, new Label());
            }
            Label fallthroughLabel = new Label();

            // C.2: GETSTATIC $$ttd$mid$N (field initialised in $ttd$registerAll)
            // replaces the old LDC + INVOKESTATIC internMethodId pattern.
            emitGetMethodId();
            // Stack: [int methodId]
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER,
                    "popResumeFrame", POPRESUME_DESC, false);
            // Stack: [ResumeFrame or null]
            mv.visitVarInsn(Opcodes.ASTORE, resumeSlot);
            mv.visitVarInsn(Opcodes.ALOAD, resumeSlot);
            mv.visitJumpInsn(Opcodes.IFNULL, fallthroughLabel);

            // Resume mode: read bci field, LOOKUPSWITCH.
            mv.visitVarInsn(Opcodes.ALOAD, resumeSlot);
            mv.visitFieldInsn(Opcodes.GETFIELD, RESUME_FRAME_OWNER, "bci", "I");

            // Build LOOKUPSWITCH: keys = sorted BCIs, labels = restoreLabels.
            List<SavePoint> sortedSps = new ArrayList<>(analysis.savePoints);
            // Already sorted by BCI in MethodAnalysis.
            int[] keys = new int[sortedSps.size()];
            Label[] switchLabels = new Label[sortedSps.size()];
            for (int i = 0; i < sortedSps.size(); i++) {
                keys[i] = sortedSps.get(i).bci;
                switchLabels[i] = restoreLabels.get(sortedSps.get(i).bci);
            }
            mv.visitLookupSwitchInsn(fallthroughLabel, keys, switchLabels);

            // Per-save-point restore blocks.
            for (SavePoint sp : sortedSps) {
                mv.visitLabel(restoreLabels.get(sp.bci));
                emitRestoreBlock(sp);
                // Jump to the save-point's bodyLabel.
                // For callsite SPs: this is the shim label (placed at argStartBci,
                // after the save-frame, before the arg loads).
                // For line-marker SPs: this is the body label at the LineNumberNode bci.
                mv.visitJumpInsn(Opcodes.GOTO, bodyLabels.get(sp.bci));
            }

            // Fallthrough: normal forward execution.
            mv.visitLabel(fallthroughLabel);

            // ------------------------------------------------------------------
            // Body replay with save-frame snippets
            // ------------------------------------------------------------------
            // We replay mn's instruction list manually so we can intercept
            // save-point BCIs to emit save-frame snippets and insert bodyLabels.
            //
            // For line-marker save points (sp.argStartBci == sp.bci):
            //   At insnIdx == sp.bci: emit bodyLabel + saveFrame + lineHit + original insn.
            //
            // For callsite save points (sp.argStartBci < sp.bci):
            //   At insnIdx == sp.argStartBci: emit saveFrame + bodyLabel (shim label).
            //   At insnIdx == sp.bci (the INVOKE): just replay normally — args were
            //   already loaded by prior instructions in the stream.
            //
            // The byArgStartBci map allows O(1) lookup at each instruction for
            // whether a callsite save-frame+shim should be emitted here.

            // Build a reverse map: for each callsite save point, map argStartBci -> SavePoint.
            // Note: line-marker SPs also have argStartBci == bci, so they appear in byBci.
            // We check byBci first (line markers), then byArgStartBci for callsite pre-emit.

            int insnIdx = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                // Check if this is a callsite argStartBci (pre-save-frame injection point).
                SavePoint callsiteSp = null;
                if (analysis.byArgStartBci.containsKey(insnIdx)) {
                    SavePoint candidate = analysis.byArgStartBci.get(insnIdx);
                    if (candidate.isCallsite) {
                        callsiteSp = candidate;
                    }
                }

                if (callsiteSp != null) {
                    // Emit the save-frame BEFORE the arg-loading sequence.
                    emitSaveFrameSnippet(callsiteSp);
                    // Emit the shim label (body label) — this is the LOOKUPSWITCH target.
                    // On resume, the prelude jumps here. Stack is empty at this point.
                    mv.visitLabel(bodyLabels.get(callsiteSp.bci));
                }

                // Check if this is a line-marker save point BCI.
                SavePoint lineSp = analysis.byBci.get(insnIdx);
                if (lineSp != null && !lineSp.isCallsite) {
                    // Place the body label BEFORE the instruction (jump target for restore blocks).
                    mv.visitLabel(bodyLabels.get(lineSp.bci));
                    // Emit save-frame snippet BEFORE the original instruction.
                    emitSaveFrameSnippet(lineSp);
                    // Also emit lineHit for REPL display.
                    mv.visitLdcInsn(ownerInternal);
                    mv.visitLdcInsn(analysis.mn.name + analysis.mn.desc);
                    mv.visitLdcInsn(lineSp.lineNumber);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER, "lineHit",
                            LINEHIT_DESC, false);
                }

                // Replay the original instruction.
                insn.accept(mv);
                insnIdx++;
            }

            mv.visitMaxs(0, 0); // COMPUTE_FRAMES handles this
            mv.visitEnd();
        }

        /**
         * Emit the save-frame snippet for one save point:
         * <pre>
         * GETSTATIC $$ttd$mid$N  (C.2: replaces LDC methodIdKey + internMethodId call)
         * LDC bci
         * NEWARRAY T_LONG (primCount)
         * for each live prim: DUP, LDC i, load+encode, LASTORE
         * LDC refCount
         * ANEWARRAY Object
         * for each live ref: DUP, LDC i, ALOAD slot, AASTORE
         * INVOKESTATIC Ttd.saveFrame(int, int, long[], Object[]) : void
         * </pre>
         */
        private void emitSaveFrameSnippet(SavePoint sp) {
            // C.2: GETSTATIC $$ttd$mid$N replaces LDC + INVOKESTATIC internMethodId.
            emitGetMethodId();
            // LDC bci
            mv.visitLdcInsn(sp.bci);
            // NEWARRAY T_LONG for primitives
            mv.visitLdcInsn(sp.livePrems.size());
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
            for (int i = 0; i < sp.livePrems.size(); i++) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(i);
                LiveLocal ll = sp.livePrems.get(i);
                emitLoadPrimAsLong(ll);
                mv.visitInsn(Opcodes.LASTORE);
            }
            // ANEWARRAY Object for references
            mv.visitLdcInsn(sp.liveRefs.size());
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < sp.liveRefs.size(); i++) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(i);
                mv.visitVarInsn(Opcodes.ALOAD, sp.liveRefs.get(i).slotIndex());
                mv.visitInsn(Opcodes.AASTORE);
            }
            // INVOKESTATIC Ttd.saveFrame(int, int, long[], Object[])
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER,
                    "saveFrame", SAVEFRAME_DESC, false);
        }

        /**
         * Load a primitive local and encode it to {@code long}:
         * <ul>
         *   <li>long: LLOAD (identity)</li>
         *   <li>double: DLOAD + INVOKESTATIC Double.doubleToRawLongBits</li>
         *   <li>float: FLOAD + INVOKESTATIC Float.floatToRawIntBits + I2L</li>
         *   <li>int/short/char/byte/boolean: ILOAD + I2L</li>
         * </ul>
         */
        private void emitLoadPrimAsLong(LiveLocal ll) {
            int slot = ll.slotIndex();
            int sort = ll.type().getSort();
            switch (sort) {
                case Type.LONG:
                    mv.visitVarInsn(Opcodes.LLOAD, slot);
                    break;
                case Type.DOUBLE:
                    mv.visitVarInsn(Opcodes.DLOAD, slot);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double",
                            "doubleToRawLongBits", "(D)J", false);
                    break;
                case Type.FLOAT:
                    mv.visitVarInsn(Opcodes.FLOAD, slot);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float",
                            "floatToRawIntBits", "(F)I", false);
                    mv.visitInsn(Opcodes.I2L);
                    break;
                default:
                    // int, short, char, byte, boolean — all use ILOAD + I2L.
                    mv.visitVarInsn(Opcodes.ILOAD, slot);
                    mv.visitInsn(Opcodes.I2L);
                    break;
            }
        }

        /**
         * Emit the restore block for one save point:
         * for each live prim: load from prims array at index i, decode, STORE slot
         * for each live ref: load from refs array at index i, CHECKCAST, ASTORE slot
         */
        private void emitRestoreBlock(SavePoint sp) {
            // Restore primitives.
            for (int i = 0; i < sp.livePrems.size(); i++) {
                LiveLocal ll = sp.livePrems.get(i);
                mv.visitVarInsn(Opcodes.ALOAD, resumeSlot);
                mv.visitFieldInsn(Opcodes.GETFIELD, RESUME_FRAME_OWNER, "prims", "[J");
                mv.visitLdcInsn(i);
                mv.visitInsn(Opcodes.LALOAD);
                emitDecodeLongToPrim(ll);
            }
            // Restore references.
            for (int i = 0; i < sp.liveRefs.size(); i++) {
                LiveLocal ll = sp.liveRefs.get(i);
                mv.visitVarInsn(Opcodes.ALOAD, resumeSlot);
                mv.visitFieldInsn(Opcodes.GETFIELD, RESUME_FRAME_OWNER, "refs", "[Ljava/lang/Object;");
                mv.visitLdcInsn(i);
                mv.visitInsn(Opcodes.AALOAD);
                emitCheckcastForSlot(ll.slotIndex());
                mv.visitVarInsn(Opcodes.ASTORE, ll.slotIndex());
            }
        }

        /**
         * Decode a {@code long} on the stack back to the local's type and store it.
         */
        private void emitDecodeLongToPrim(LiveLocal ll) {
            int slot = ll.slotIndex();
            int sort = ll.type().getSort();
            switch (sort) {
                case Type.LONG:
                    // already long; just store
                    mv.visitVarInsn(Opcodes.LSTORE, slot);
                    break;
                case Type.DOUBLE:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double",
                            "longBitsToDouble", "(J)D", false);
                    mv.visitVarInsn(Opcodes.DSTORE, slot);
                    break;
                case Type.FLOAT:
                    mv.visitInsn(Opcodes.L2I);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float",
                            "intBitsToFloat", "(I)F", false);
                    mv.visitVarInsn(Opcodes.FSTORE, slot);
                    break;
                default:
                    // int, short, char, byte, boolean — L2I then ISTORE
                    mv.visitInsn(Opcodes.L2I);
                    mv.visitVarInsn(Opcodes.ISTORE, slot);
                    break;
            }
        }

        /**
         * Emit a {@code CHECKCAST} for the declared type of the reference local at
         * {@code slotIndex}. Uses the LVT/descriptor map; falls back to no cast
         * if the slot is unknown or declared as {@code java/lang/Object}.
         */
        private void emitCheckcastForSlot(int slotIndex) {
            String declaredDesc = declaredRefTypes.get(slotIndex);
            if (declaredDesc == null) return;
            Type declaredType = Type.getType(declaredDesc);
            int sort = declaredType.getSort();
            if (sort == Type.OBJECT) {
                String internalName = declaredType.getInternalName();
                if (!"java/lang/Object".equals(internalName)) {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, internalName);
                }
            } else if (sort == Type.ARRAY) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, declaredType.getDescriptor());
            }
        }

        /**
         * Build a map from local-variable slot index → JVM descriptor, combining
         * information from the method descriptor (for parameters) and the LVT.
         * Only reference types (OBJECT, ARRAY) are stored.
         */
        private static Map<Integer, String> buildDeclaredRefTypes(MethodNode mn) {
            Map<Integer, String> map = new HashMap<>();
            boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
            // Parameters.
            Type[] argTypes = Type.getArgumentTypes(mn.desc);
            int slot = isStatic ? 0 : 1;
            for (Type arg : argTypes) {
                int sort = arg.getSort();
                if (sort == Type.OBJECT || sort == Type.ARRAY) {
                    map.put(slot, arg.getDescriptor());
                }
                slot += arg.getSize();
            }
            // LVT (may be absent with -g:none).
            if (mn.localVariables != null) {
                for (LocalVariableNode lv : mn.localVariables) {
                    if (lv.desc == null) continue;
                    Type t = Type.getType(lv.desc);
                    int sort = t.getSort();
                    if (sort == Type.OBJECT || sort == Type.ARRAY) {
                        map.put(lv.index, lv.desc);
                    }
                }
            }
            return map;
        }
    }

    // -------------------------------------------------------------------------
    // Safe ClassWriter (resource-stream super-class resolution)
    // -------------------------------------------------------------------------

    /**
     * {@link ClassWriter} subclass that avoids {@link Class#forName} during
     * {@code COMPUTE_FRAMES} by resolving super-class names via resource streams.
     * Falls back to {@code java/lang/Object} when a class file cannot be located.
     * Mirrors {@code SafeClassWriter} in {@code crochet-agent}.
     */
    static final class TtdSafeClassWriter extends ClassWriter {
        private static final ConcurrentHashMap<String, String> SUPER_CACHE =
                new ConcurrentHashMap<>();
        private static final String SUPER_NONE = "";

        private final ClassLoader loader;

        TtdSafeClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = loader;
        }

        /** Exposed as package-private for unit testing. */
        String commonSuperClassOf(String type1, String type2) {
            return getCommonSuperClass(type1, type2);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) return type1;
            if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
                return "java/lang/Object";
            }
            Set<String> chain1 = superChain(type1);
            if (chain1.contains(type2)) return type2;
            Set<String> chain2 = superChain(type2);
            if (chain2.contains(type1)) return type1;
            for (String c : chain1) {
                if (chain2.contains(c)) return c;
            }
            return "java/lang/Object";
        }

        private Set<String> superChain(String type) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            String c = type;
            while (c != null && out.add(c)) {
                c = superOf(c);
            }
            return out;
        }

        private String superOf(String type) {
            String cached = SUPER_CACHE.get(type);
            if (cached != null) {
                return cached == SUPER_NONE ? null : cached;
            }
            String result = superOfUncached(type);
            SUPER_CACHE.putIfAbsent(type, result != null ? result : SUPER_NONE);
            return result;
        }

        private String superOfUncached(String type) {
            ClassLoader effective = loader != null ? loader
                    : TtdSafeClassWriter.class.getClassLoader();
            for (ClassLoader l = effective; l != null; l = l.getParent()) {
                try (InputStream in = l.getResourceAsStream(type + ".class")) {
                    if (in != null) return new ClassReader(in).getSuperName();
                } catch (IOException ignored) {}
            }
            try (InputStream in = ClassLoader.getSystemResourceAsStream(type + ".class")) {
                if (in != null) return new ClassReader(in).getSuperName();
            } catch (IOException ignored) {}
            return null;
        }
    }
}
