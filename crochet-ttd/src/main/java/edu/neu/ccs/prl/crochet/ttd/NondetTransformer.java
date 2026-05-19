package edu.neu.ccs.prl.crochet.ttd;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Bytecode transformer that rewrites calls to nondeterministic JDK methods
 * so that, when a TTD session is active, return values are recorded on the
 * first run and replayed on subsequent runs.
 *
 * <h2>Intercepted methods</h2>
 * <ul>
 *   <li>{@code System.currentTimeMillis()} → {@code NondetRecorder.fetchOrCallCurrentTimeMillis(I)J}</li>
 *   <li>{@code System.nanoTime()} → {@code NondetRecorder.fetchOrCallNanoTime(I)J}</li>
 *   <li>{@code System.identityHashCode(Object)} → {@code NondetRecorder.fetchOrCallIdentityHashCode(Ljava/lang/Object;I)I}</li>
 *   <li>{@code Object.hashCode()} (static type = Object) → {@code NondetRecorder.fetchOrCallObjectHashCode(Ljava/lang/Object;I)I}</li>
 *   <li>{@code Random.next(int)} → {@code NondetRecorder.fetchOrCallRandomNext(Ljava/util/Random;I I)I}</li>
 *   <li>{@code Random.nextInt()} → {@code NondetRecorder.fetchOrCallNextInt(Ljava/util/Random;I)I}</li>
 *   <li>{@code Random.nextInt(int)} → {@code NondetRecorder.fetchOrCallNextIntBound(Ljava/util/Random;II)I}</li>
 *   <li>{@code Random.nextLong()} → {@code NondetRecorder.fetchOrCallNextLong(Ljava/util/Random;I)J}</li>
 *   <li>{@code Random.nextDouble()} → {@code NondetRecorder.fetchOrCallNextDouble(Ljava/util/Random;I)D}</li>
 *   <li>{@code Random.nextFloat()} → {@code NondetRecorder.fetchOrCallNextFloat(Ljava/util/Random;I)F}</li>
 *   <li>{@code Random.nextBoolean()} → {@code NondetRecorder.fetchOrCallNextBoolean(Ljava/util/Random;I)Z}</li>
 *   <li>{@code Random.nextGaussian()} → {@code NondetRecorder.fetchOrCallNextGaussian(Ljava/util/Random;I)D}</li>
 *   <li>{@code Math.random()} → {@code NondetRecorder.fetchOrCallMathRandom(I)D}</li>
 * </ul>
 *
 * <h2>Rewrite shape</h2>
 * For no-argument INVOKESTATIC methods (currentTimeMillis, nanoTime, Math.random):
 * <pre>
 *   // before:
 *   INVOKESTATIC java/lang/System currentTimeMillis ()J
 *   // after:
 *   LDC &lt;siteId&gt;
 *   INVOKESTATIC NondetRecorder fetchOrCallCurrentTimeMillis (I)J
 * </pre>
 * For INVOKESTATIC with an argument (identityHashCode):
 * <pre>
 *   // before:  ..., objref
 *   INVOKESTATIC java/lang/System identityHashCode (Ljava/lang/Object;)I
 *   // after:   ..., objref
 *   LDC &lt;siteId&gt;
 *   INVOKESTATIC NondetRecorder fetchOrCallIdentityHashCode (Ljava/lang/Object;I)I
 * </pre>
 * For INVOKEVIRTUAL on Random (receiver already on stack):
 * <pre>
 *   // before:  ..., rngref
 *   INVOKEVIRTUAL java/util/Random nextInt ()I
 *   // after:   ..., rngref
 *   LDC &lt;siteId&gt;
 *   INVOKESTATIC NondetRecorder fetchOrCallNextInt (Ljava/util/Random;I)I
 * </pre>
 * For INVOKEVIRTUAL Object.hashCode() (static type Object):
 * <pre>
 *   // before:  ..., objref
 *   INVOKEVIRTUAL java/lang/Object hashCode ()I
 *   // after:   ..., objref
 *   LDC &lt;siteId&gt;
 *   INVOKESTATIC NondetRecorder fetchOrCallObjectHashCode (Ljava/lang/Object;I)I
 * </pre>
 *
 * <h2>JDK-class minimal pipeline</h2>
 * The transformer skips JDK classes (java/*, jdk/*, sun/*, com/sun/*).
 * We instrument CALL SITES in user code, not the definitions in JDK classes.
 * This is consistent with the existing Crochet minimal-pipeline policy.
 *
 * <h2>@CrochetSkip interaction</h2>
 * {@code @CrochetSkip} opts out of Crochet checkpoint instrumentation
 * (field-access wrappers, static-field hooks). It does NOT opt out of
 * TTD nondet instrumentation: the two transformers run in separate agents
 * and the TTD agent has no knowledge of {@code @CrochetSkip}. A class
 * annotated {@code @CrochetSkip} still has its nondet calls intercepted
 * when the TTD agent is loaded. This is intentional — the annotations are
 * orthogonal by design.
 *
 * <h2>Site IDs</h2>
 * Each unique call site gets a globally-unique integer ID assigned by
 * {@link #SITE_COUNTER}. The ID is stable within a JVM session (same
 * class bytes → same transform pass → same BCI offsets → same IDs
 * assigned in the same order). Site-descriptor strings are registered
 * lazily in {@link edu.neu.ccs.prl.crochet.ttd.nondet.NondetRecorder}.
 */
final class NondetTransformer implements ClassFileTransformer {

    /** Global counter for site IDs across all transformed classes. */
    private static final AtomicInteger SITE_COUNTER = new AtomicInteger(0);

    private static final String NONDET_OWNER =
            "edu/neu/ccs/prl/crochet/ttd/nondet/NondetRecorder";

    // Target methods — owner / name / descriptor triples
    private static final String SYS = "java/lang/System";
    private static final String RNG = "java/util/Random";
    private static final String OBJ = "java/lang/Object";
    private static final String MATH = "java/lang/Math";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null) return null;
        // Skip JDK classes — we instrument call sites in user code only.
        if (className.startsWith("java/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("net/jonbell/crochet/")
                || className.startsWith("edu/neu/ccs/prl/crochet/ttd/")) {
            return null;
        }
        // Quick scan: does the constant pool mention any of our target method names?
        if (!mentionsAnyTarget(classfileBuffer)) {
            return null;
        }
        ClassReader cr;
        try {
            cr = new ClassReader(classfileBuffer);
        } catch (Throwable t) {
            return null;
        }
        try {
            // COMPUTE_MAXS so that adding LDC instructions before INVOKESTATIC
            // calls does not produce operand-stack-overflow VerifyErrors.
            // We do NOT use COMPUTE_FRAMES — that would require resolving type
            // hierarchies and is unnecessary since we only add an LDC + INVOKESTATIC.
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            cr.accept(new NondetClassVisitor(cw, className), 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            if (Boolean.getBoolean("crochet.ttd.debug")) {
                System.err.println("[ttd-nondet] FAILED to transform " + className + ": " + t);
            }
            return null;
        }
    }

    /**
     * Cheap pre-filter: look for any of the target method name bytes in the
     * raw class file. Avoids full ClassVisitor pass on irrelevant classes.
     */
    private static boolean mentionsAnyTarget(byte[] classfile) {
        return containsBytes(classfile, "currentTimeMillis")
                || containsBytes(classfile, "nanoTime")
                || containsBytes(classfile, "identityHashCode")
                || containsBytes(classfile, "nextInt")
                || containsBytes(classfile, "nextLong")
                || containsBytes(classfile, "nextDouble")
                || containsBytes(classfile, "nextFloat")
                || containsBytes(classfile, "nextBoolean")
                || containsBytes(classfile, "nextGaussian")
                || (containsBytes(classfile, "hashCode") && containsBytes(classfile, "java/lang/Object"))
                || (containsBytes(classfile, "random") && containsBytes(classfile, "java/lang/Math"));
    }

    private static boolean containsBytes(byte[] data, String s) {
        byte[] needle = s.getBytes();
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // ASM visitors
    // -------------------------------------------------------------------------

    private static final class NondetClassVisitor extends ClassVisitor {
        private final String ownerInternal;

        NondetClassVisitor(ClassVisitor delegate, String ownerInternal) {
            super(Opcodes.ASM9, delegate);
            this.ownerInternal = ownerInternal;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // Skip abstract and native methods.
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return mv;
            }
            return new NondetMethodVisitor(mv, ownerInternal, name, descriptor);
        }
    }

    private static final class NondetMethodVisitor extends MethodVisitor {
        private final String ownerInternal;
        private final String methodName;
        private final String methodDesc;
        /** BCI counter — incremented per instruction to give stable site IDs. */
        private int bciCounter = 0;

        NondetMethodVisitor(MethodVisitor delegate, String ownerInternal,
                             String methodName, String methodDesc) {
            super(Opcodes.ASM9, delegate);
            this.ownerInternal = ownerInternal;
            this.methodName    = methodName;
            this.methodDesc    = methodDesc;
        }

        /** Assign and register a new site ID for the current BCI. */
        private int newSiteId() {
            int id = SITE_COUNTER.incrementAndGet();
            String desc = ownerInternal + "/" + methodName + methodDesc + "/" + bciCounter;
            // Register lazily — NondetRecorder.registerSiteDesc is idempotent.
            edu.neu.ccs.prl.crochet.ttd.nondet.NondetRecorder.registerSiteDesc(id, desc);
            return id;
        }

        // Track BCI via counting instructions. We don't need exact bytecode
        // BCI — we need a unique counter per call site within a method.
        // Using a simple per-method incrementing counter is sufficient for
        // site-ID uniqueness.

        @Override
        public void visitInsn(int opcode) {
            bciCounter++;
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            bciCounter++;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            bciCounter++;
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            bciCounter++;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            bciCounter++;
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            bciCounter++;
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            bciCounter++;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            bciCounter++;
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt,
                                          org.objectweb.asm.Label... labels) {
            bciCounter++;
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                                           org.objectweb.asm.Label[] labels) {
            bciCounter++;
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            bciCounter++;
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                     String descriptor, boolean isInterface) {
            bciCounter++;
            // Try to rewrite; if not a target, fall through to super.
            if (tryRewrite(opcode, owner, name, descriptor)) {
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        /**
         * Attempt to rewrite the instruction. Returns true if rewritten.
         *
         * <p>Stack discipline:
         * <ul>
         *   <li>INVOKESTATIC no-arg: stack unchanged. Push LDC siteId, call helper(I)→result.</li>
         *   <li>INVOKESTATIC identityHashCode(Object)I: stack has ..., obj.
         *       Push LDC siteId, call helper(Obj,I)I — consumes obj+siteId, pushes int.</li>
         *   <li>INVOKEVIRTUAL Random.nextXxx(): stack has ..., rngref.
         *       Push LDC siteId, call helper(Random,I)→result — consumes rngref+siteId.</li>
         *   <li>INVOKEVIRTUAL Object.hashCode(): stack has ..., objref.
         *       Push LDC siteId, call helper(Object,I)I.</li>
         * </ul>
         */
        private boolean tryRewrite(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.INVOKESTATIC && SYS.equals(owner)) {
                if ("currentTimeMillis".equals(name) && "()J".equals(descriptor)) {
                    int sid = newSiteId();
                    mv.visitLdcInsn(sid);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                            "fetchOrCallCurrentTimeMillis", "(I)J", false);
                    return true;
                }
                if ("nanoTime".equals(name) && "()J".equals(descriptor)) {
                    int sid = newSiteId();
                    mv.visitLdcInsn(sid);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                            "fetchOrCallNanoTime", "(I)J", false);
                    return true;
                }
                if ("identityHashCode".equals(name) && "(Ljava/lang/Object;)I".equals(descriptor)) {
                    // Stack: ..., objref  →  push siteId  →  ..., objref, siteId
                    int sid = newSiteId();
                    mv.visitLdcInsn(sid);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                            "fetchOrCallIdentityHashCode", "(Ljava/lang/Object;I)I", false);
                    return true;
                }
            }
            if (opcode == Opcodes.INVOKESTATIC && MATH.equals(owner)) {
                if ("random".equals(name) && "()D".equals(descriptor)) {
                    int sid = newSiteId();
                    mv.visitLdcInsn(sid);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                            "fetchOrCallMathRandom", "(I)D", false);
                    return true;
                }
            }
            // Object.hashCode() — only when static type is java/lang/Object.
            if (opcode == Opcodes.INVOKEVIRTUAL && OBJ.equals(owner)
                    && "hashCode".equals(name) && "()I".equals(descriptor)) {
                // Stack: ..., objref  →  push siteId  →  ..., objref, siteId
                int sid = newSiteId();
                mv.visitLdcInsn(sid);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                        "fetchOrCallObjectHashCode", "(Ljava/lang/Object;I)I", false);
                return true;
            }
            // Random methods — match INVOKEVIRTUAL on java/util/Random.
            if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL)
                    && RNG.equals(owner)) {
                int sid = newSiteId();
                switch (name) {
                    case "next":
                        if ("(I)I".equals(descriptor)) {
                            // Stack: ..., rngref, bits  →  push siteId
                            mv.visitLdcInsn(sid);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                                    "fetchOrCallRandomNext", "(Ljava/util/Random;II)I", false);
                            return true;
                        }
                        break;
                    case "nextInt":
                        if ("()I".equals(descriptor)) {
                            mv.visitLdcInsn(sid);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                                    "fetchOrCallNextInt", "(Ljava/util/Random;I)I", false);
                            return true;
                        }
                        if ("(I)I".equals(descriptor)) {
                            // Stack: ..., rngref, bound  →  push siteId
                            mv.visitLdcInsn(sid);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                                    "fetchOrCallNextIntBound", "(Ljava/util/Random;II)I", false);
                            return true;
                        }
                        break;
                    case "nextLong":
                        if ("()J".equals(descriptor)) {
                            mv.visitLdcInsn(sid);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                                    "fetchOrCallNextLong", "(Ljava/util/Random;I)J", false);
                            return true;
                        }
                        break;
                    case "nextDouble":
                        if ("()D".equals(descriptor)) {
                            mv.visitLdcInsn(sid);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                                    "fetchOrCallNextDouble", "(Ljava/util/Random;I)D", false);
                            return true;
                        }
                        break;
                    case "nextFloat":
                        if ("()F".equals(descriptor)) {
                            mv.visitLdcInsn(sid);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                                    "fetchOrCallNextFloat", "(Ljava/util/Random;I)F", false);
                            return true;
                        }
                        break;
                    case "nextBoolean":
                        if ("()Z".equals(descriptor)) {
                            mv.visitLdcInsn(sid);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                                    "fetchOrCallNextBoolean", "(Ljava/util/Random;I)Z", false);
                            return true;
                        }
                        break;
                    case "nextGaussian":
                        if ("()D".equals(descriptor)) {
                            mv.visitLdcInsn(sid);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, NONDET_OWNER,
                                    "fetchOrCallNextGaussian", "(Ljava/util/Random;I)D", false);
                            return true;
                        }
                        break;
                }
            }
            return false;
        }
    }
}
