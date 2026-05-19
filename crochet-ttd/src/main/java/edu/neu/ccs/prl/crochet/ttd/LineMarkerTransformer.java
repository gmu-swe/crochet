package edu.neu.ccs.prl.crochet.ttd;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM transformer for {@link TimeTravelBody}-annotated methods.
 *
 * <p>For each annotated method, every entry in the method's
 * {@code LineNumberTable} becomes the insertion point for an implicit
 * pause: a static call to
 * {@code Ttd.lineHit(ownerInternal, methodSignature, line)} is emitted
 * immediately after the line label. Method entry is treated as
 * "before line N" where N is the first source line of the body —
 * the first lineHit fires before any user statement runs.
 *
 * <p>Methods without the annotation are passed through unchanged.
 * Constructors, static initializers, and synthetic methods (lambdas,
 * accessor bridges) are also skipped — they typically don't carry
 * user-meaningful line numbers and instrumenting them would explode
 * the call graph for trivial code paths.
 */
final class LineMarkerTransformer implements ClassFileTransformer {

    private static final String TTD_OWNER = "edu/neu/ccs/prl/crochet/ttd/Ttd";
    private static final String LINEHIT_DESC = "(Ljava/lang/String;Ljava/lang/String;I)V";
    private static final String ANNOTATION_DESC = "Ledu/neu/ccs/prl/crochet/ttd/TimeTravelBody;";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null) return null;
        // Skip very-early bootstrap classes and the Crochet runtime to
        // avoid bootstrap loops. Our own crochet-ttd classes don't have
        // @TimeTravelBody on any method, so the constant-pool pre-filter
        // below filters them out cheaply. We could explicitly skip them
        // by exact name too, but that's redundant with the pre-filter
        // and was previously a source of a wildcard-prefix bug
        // (startsWith "Ttd" accidentally caught user test classes named
        // TtdSomething).
        if (className.startsWith("java/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("net/jonbell/crochet/")
                || className.startsWith("edu/neu/ccs/prl/crochet/ttd/shaded/")) {
            return null;
        }
        ClassReader cr;
        try {
            cr = new ClassReader(classfileBuffer);
        } catch (Throwable t) {
            return null;
        }
        // Quick scan: does this class contain any @TimeTravelBody method?
        // If not, skip the full transform pass.
        if (!classMentionsAnnotation(classfileBuffer)) {
            return null;
        }
        if (Boolean.getBoolean("crochet.ttd.debug")) {
            System.err.println("[ttd] transforming " + className);
        }
        try {
            ClassWriter cw = new ClassWriter(cr, 0);
            cr.accept(new TtdClassVisitor(cw), 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            if (Boolean.getBoolean("crochet.ttd.debug")) {
                System.err.println("[ttd] FAILED to transform " + className + ": " + t);
            }
            return null;
        }
    }

    /**
     * Cheap pre-filter: scan the constant pool for our annotation
     * descriptor. Avoids running the full ClassVisitor on classes that
     * obviously don't use the annotation. Same trick the JaCoCo agent
     * uses to filter probe-bearing classes.
     */
    private static boolean classMentionsAnnotation(byte[] classfile) {
        // Crude: look for the annotation descriptor bytes directly. False
        // positives are fine (we just do extra work); false negatives are
        // not (we'd skip a class that needed instrumentation).
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

    private static final class TtdClassVisitor extends ClassVisitor {
        String ownerInternal;

        TtdClassVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.ownerInternal = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // Skip constructors, static initializers, synthetic methods, abstract/native.
            if ("<init>".equals(name) || "<clinit>".equals(name)) return mv;
            if ((access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return mv;
            }
            return new TtdMethodVisitor(mv, ownerInternal, name + descriptor);
        }
    }

    private static final class TtdMethodVisitor extends MethodVisitor {
        private final String ownerInternal;
        private final String methodSignature;
        private boolean annotated;

        TtdMethodVisitor(MethodVisitor delegate, String ownerInternal, String methodSignature) {
            super(Opcodes.ASM9, delegate);
            this.ownerInternal = ownerInternal;
            this.methodSignature = methodSignature;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (ANNOTATION_DESC.equals(descriptor)) {
                annotated = true;
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            if (!annotated) return;
            if (Boolean.getBoolean("crochet.ttd.debug")) {
                System.err.println("[ttd] inserting line marker at " + ownerInternal
                        + "." + methodSignature + ":" + line);
            }
            // Emit: Ttd.lineHit(ownerInternal, methodSignature, line)
            mv.visitLdcInsn(ownerInternal);
            mv.visitLdcInsn(methodSignature);
            mv.visitLdcInsn(line);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TTD_OWNER, "lineHit",
                    LINEHIT_DESC, false);
        }
    }
}
