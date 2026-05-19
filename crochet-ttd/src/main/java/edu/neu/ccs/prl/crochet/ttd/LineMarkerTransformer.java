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
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

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
 *   <li>A synthetic {@code $ttd$registerAll()} method called from {@code <clinit>}
 *       to register method IDs and save-point debug metadata at class-load time.</li>
 * </ol>
 *
 * <p>Methods without the annotation are passed through unchanged. Constructors,
 * static initializers, synthetic methods (lambdas, accessor bridges), abstract
 * and native methods are also skipped.
 *
 * <p>Callsite save points are intentionally NOT emitted in Phase B because they
 * require operand-stack capture (arguments already pushed onto the stack). That
 * requires full CPS/Quasar-style stack manipulation and is deferred to Phase C.
 * Only line-marker BCIs (where the operand stack is guaranteed empty by the
 * Java compiler) are used as save points.
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

    /** Name of the synthetic class-init helper emitted at {@code visitEnd()}. */
    private static final String REGISTER_ALL_METHOD = "$ttd$registerAll";
    private static final String REGISTER_ALL_DESC = "()V";

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

    /** One save point: a line-number BCI plus the live locals at that BCI. */
    static final class SavePoint {
        /** Index in the method's instruction list (the position of the LineNumberNode). */
        final int bci;
        /** Source line number for registration label. */
        final int lineNumber;
        /** All live locals at this BCI, sorted ascending by slot. */
        final List<LiveLocal> liveLocals;
        /** Subset of liveLocals that are primitive types, sorted. */
        final List<LiveLocal> livePrems;
        /** Subset of liveLocals that are reference types, sorted. */
        final List<LiveLocal> liveRefs;

        SavePoint(int bci, int lineNumber, List<LiveLocal> liveLocals) {
            this.bci = bci;
            this.lineNumber = lineNumber;
            this.liveLocals = liveLocals;
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

        MethodAnalysis(String methodIdKey, MethodNode mn, List<SavePoint> savePoints) {
            this.methodIdKey = methodIdKey;
            this.mn = mn;
            this.savePoints = Collections.unmodifiableList(savePoints);
            Map<Integer, SavePoint> map = new TreeMap<>();
            for (SavePoint sp : savePoints) {
                map.put(sp.bci, sp);
            }
            this.byBci = Collections.unmodifiableMap(map);
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

    private static boolean isEligible(MethodNode mn) {
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
        // Collect line-number BCIs and check for MONITORENTER violations.
        Set<Integer> candidateBcis = new LinkedHashSet<>();
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
                candidateBcis.add(bci);
            }
            // Callsite BCIs are NOT added in Phase B — operand-stack capture
            // is required for correct resume at callsite save points, deferred
            // to Phase C.
            if (monitorDepth > 0 && isNonTtdInvokeInsn(insn)) {
                throwMonitorenterRefusal(ownerInternalName, mn);
            }
            bci++;
        }

        if (candidateBcis.isEmpty()) {
            return null;
        }

        // Run liveness analysis.
        LivenessAnalyzer analyzer = new LivenessAnalyzer();
        Map<Integer, List<LiveLocal>> liveness;
        try {
            liveness = analyzer.analyze(ownerInternalName, mn, candidateBcis);
        } catch (AnalyzerException e) {
            if (Boolean.getBoolean("crochet.ttd.debug")) {
                System.err.println("[ttd] liveness analysis failed for "
                        + ownerInternalName + "." + mn.name + mn.desc + ": " + e);
            }
            return null;
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

        List<SavePoint> savePoints = new ArrayList<>();
        for (int candidateBci : candidateBcis) {
            List<LiveLocal> live = liveness.get(candidateBci);
            if (live == null) live = Collections.emptyList();
            int lineNumber = bciToLine.getOrDefault(candidateBci, 0);
            savePoints.add(new SavePoint(candidateBci, lineNumber, live));
        }
        // Sort by BCI for determinism (gate 18).
        savePoints.sort((a, b) -> Integer.compare(a.bci, b.bci));

        String methodIdKey = ownerInternalName + "." + mn.name + mn.desc;
        return new MethodAnalysis(methodIdKey, mn, savePoints);
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

        TtdClassVisitor(ClassVisitor delegate, String ownerInternal,
                        List<MethodAnalysis> analyses) {
            super(Opcodes.ASM9, delegate);
            this.ownerInternal = ownerInternal;
            this.analysisByKey = new HashMap<>();
            for (MethodAnalysis a : analyses) {
                analysisByKey.put(a.mn.name + a.mn.desc, a);
            }
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
            // CPS emitter: suppress original bytecode, replay from MethodNode.
            return new SuppressingMethodVisitor(mv, ownerInternal, analysis, registrations);
        }

        @Override
        public void visitEnd() {
            if (!analysisByKey.isEmpty()) {
                // Emit the $ttd$registerAll() synthetic helper.
                emitRegisterAll();
                // If there was no <clinit>, emit one that calls $ttd$registerAll().
                if (!hasClinitAlready) {
                    emitSyntheticClinit();
                }
            }
            super.visitEnd();
        }

        private void emitRegisterAll() {
            MethodVisitor mv = super.visitMethod(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    REGISTER_ALL_METHOD, REGISTER_ALL_DESC, null, null);
            mv.visitCode();
            for (String[] reg : registrations) {
                String methodIdKey = reg[0];
                int bci = Integer.parseInt(reg[1]);
                String label = reg[2];
                // int methodId = Ttd.internMethodId(methodIdKey);
                mv.visitLdcInsn(methodIdKey);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER,
                        "internMethodId", INTERNMETHODID_DESC, false);
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

        SuppressingMethodVisitor(MethodVisitor realWriter, String ownerInternal,
                                  MethodAnalysis analysis, List<String[]> registrations) {
            // Pass null as delegate — we suppress all events.
            super(Opcodes.ASM9, null);
            this.realWriter = realWriter;
            this.ownerInternal = ownerInternal;
            this.analysis = analysis;
            this.registrations = registrations;
        }

        @Override
        public void visitEnd() {
            // Add registrations for all save points.
            for (SavePoint sp : analysis.savePoints) {
                String label = ownerInternal + "." + analysis.mn.name
                        + analysis.mn.desc + ":" + sp.lineNumber;
                registrations.add(new String[]{
                        analysis.methodIdKey,
                        Integer.toString(sp.bci),
                        label
                });
            }
            // Emit the CPS-transformed method body.
            CpsMethodEmitter emitter = new CpsMethodEmitter(
                    realWriter, ownerInternal, analysis);
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
     * <p>The method is emitted entirely from the {@link MethodNode}'s instruction
     * list, replaying via {@code MethodNode.accept(MethodVisitor)}, with
     * interception at each save-point BCI to inject the save-frame snippet.
     */
    private static final class CpsMethodEmitter {
        private final MethodVisitor mv;
        private final String ownerInternal;
        private final MethodAnalysis analysis;
        /** Slot of the {@code ResumeFrame} local beyond maxLocals. */
        private final int resumeSlot;
        /** Map from slot index → declared descriptor (from LVT + parameter types). */
        private final Map<Integer, String> declaredRefTypes;

        CpsMethodEmitter(MethodVisitor mv, String ownerInternal, MethodAnalysis analysis) {
            this.mv = mv;
            this.ownerInternal = ownerInternal;
            this.analysis = analysis;
            this.resumeSlot = analysis.mn.maxLocals;
            this.declaredRefTypes = buildDeclaredRefTypes(analysis.mn);
        }

        void emit() {
            MethodNode mn = analysis.mn;
            mv.visitCode();

            // ------------------------------------------------------------------
            // Dispatch prelude
            // ------------------------------------------------------------------
            // Labels for the LOOKUPSWITCH targets (one per save point).
            Map<Integer, Label> restoreLabels = new TreeMap<>();
            Map<Integer, Label> bodyLabels = new TreeMap<>();
            for (SavePoint sp : analysis.savePoints) {
                restoreLabels.put(sp.bci, new Label());
                bodyLabels.put(sp.bci, new Label());
            }
            Label fallthroughLabel = new Label();

            // int methodId = Ttd.internMethodId(methodIdKey);
            // (We call internMethodId here at dispatch time, not via a stored int,
            //  to avoid needing a static field. The JIT inlines this quickly.)
            // However, we need methodId as an int on stack for popResumeFrame.
            // Strategy: call internMethodId, store in a local, then call popResumeFrame.
            // Actually, the design calls popResumeFrame(int methodId) where methodId is
            // already known. But we don't have a static slot to cache it at emit time.
            // We inline internMethodId at the top of EACH method invocation.
            // This is O(1) after the first call (ConcurrentHashMap.get is near-constant).
            mv.visitLdcInsn(analysis.methodIdKey);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER,
                    "internMethodId", INTERNMETHODID_DESC, false);
            // Stack: [int methodId]
            // Duplicate for popResumeFrame call (methodId is consumed).
            // Actually internMethodId returns the id. We need it for popResumeFrame.
            // Call: Ttd.popResumeFrame(int) - consumes methodId from stack, returns ResumeFrame.
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
                // Jump to the save-point's bodyLabel (just before the original instruction).
                mv.visitJumpInsn(Opcodes.GOTO, bodyLabels.get(sp.bci));
            }

            // Fallthrough: normal forward execution.
            mv.visitLabel(fallthroughLabel);

            // ------------------------------------------------------------------
            // Body replay with save-frame snippets
            // ------------------------------------------------------------------
            // We replay mn's instruction list manually so we can intercept
            // save-point BCIs to emit save-frame snippets and insert bodyLabels.
            int insnIdx = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                SavePoint sp = analysis.byBci.get(insnIdx);
                if (sp != null) {
                    // Place the body label BEFORE the instruction (this is the
                    // jump target for restore blocks).
                    mv.visitLabel(bodyLabels.get(sp.bci));
                    // Emit save-frame snippet BEFORE the original instruction.
                    emitSaveFrameSnippet(sp);
                    // Also emit lineHit for REPL display.
                    mv.visitLdcInsn(ownerInternal);
                    mv.visitLdcInsn(analysis.mn.name + analysis.mn.desc);
                    mv.visitLdcInsn(sp.lineNumber);
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
         * LDC methodIdKey + internMethodId call
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
            // int methodId = Ttd.internMethodId(methodIdKey)
            mv.visitLdcInsn(analysis.methodIdKey);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER,
                    "internMethodId", INTERNMETHODID_DESC, false);
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
