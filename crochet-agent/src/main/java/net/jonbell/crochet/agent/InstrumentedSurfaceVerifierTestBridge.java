package net.jonbell.crochet.agent;

import java.util.List;

import net.jonbell.crochet.annotation.Internal;
import net.jonbell.crochet.transform.CrochetTransformer;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Test-only bridge that exposes {@link InstrumentedSurfaceVerifier}'s scanning
 * logic for unit testing without requiring a full agent-load cycle.
 *
 * <p>This class is in {@code main} (not {@code test}) because
 * {@code crochet-compose-kit} is a separate module and needs to reference it.
 * It is {@code @Internal} — downstream code must not use it.
 *
 * <p>The bridge also provides factory methods for synthetic class files used
 * in the negative-composition tests:
 * <ul>
 *   <li>{@link #buildClassWithFullSurface()} — a class with all required
 *       {@code $$crochet*} surface elements present.</li>
 *   <li>{@link #buildClassMissingAccessMethod()} — a class with all elements
 *       except {@code $$crochetAccess}, simulating a Byte Buddy rewrite that
 *       silently removes the method.</li>
 *   <li>{@link #buildInterfaceWithoutSurface()} — an interface (which should
 *       be silently skipped by the verifier).</li>
 * </ul>
 */
@Internal
public final class InstrumentedSurfaceVerifierTestBridge {

    private InstrumentedSurfaceVerifierTestBridge() {}

    private static final String ANNOTATION_DESC =
            CrochetTransformer.CROCHET_INSTRUMENTED_DESC;
    private static final String CRIJ_INTERFACE =
            "net/jonbell/crochet/runtime/CRIJInstrumented";

    /**
     * Invokes the verifier's surface-scan logic on the given class bytes and
     * returns the comma-separated list of missing surface elements, or an empty
     * string if the surface is complete or the class is skipped.
     *
     * <p>Mirrors the logic in {@link InstrumentedSurfaceVerifier#transform} but
     * without the {@link InstrumentedSurfaceVerifier#ENABLED} gate, so tests can
     * call it without setting the system property.
     */
    public static String scanBytes(String className, byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null) {
            return "";
        }
        if (CrochetTransformer.shouldSkip(className)) {
            return "";
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            int access = reader.getAccess();
            if ((access & (Opcodes.ACC_ENUM | Opcodes.ACC_INTERFACE
                    | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) != 0) {
                return "";
            }
            if ("java/lang/Enum".equals(reader.getSuperName())) {
                return "";
            }
            SurfaceCheckVisitor v = new SurfaceCheckVisitor();
            reader.accept(v, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
            List<String> missing = v.missing();
            return String.join(",", missing);
        } catch (Throwable t) {
            return "ERROR:" + t.getMessage();
        }
    }

    /**
     * Builds a synthetic class file that has all required {@code $$crochet*}
     * surface elements plus {@code @CrochetInstrumented} and
     * {@code CRIJInstrumented} interface.
     */
    public static byte[] buildClassWithFullSurface() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/GoodBean",
                null, "java/lang/Object", new String[]{CRIJ_INTERFACE});
        // @CrochetInstrumented annotation
        cw.visitAnnotation(ANNOTATION_DESC, false).visitEnd();
        // $$crochetVersion field
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
                "$$crochetVersion", "I", null, null).visitEnd();
        // $$crochetSnap field
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
                "$$crochetSnap", "Ljava/lang/Object;", null, null).visitEnd();
        // $$crochetAccess method
        emitNoOpMethod(cw, "$$crochetAccess", "()V");
        // $$crochetCheckpoint method
        emitNoOpMethod(cw, "$$crochetCheckpoint", "(I)V");
        // $$crochetRollback method
        emitNoOpMethod(cw, "$$crochetRollback", "(I)V");
        // Remaining CRIJInstrumented methods (not checked by verifier but needed
        // for valid interface implementation)
        emitNoOpMethod(cw, "$$crochetCopyFieldsTo", "(Ljava/lang/Object;)V");
        emitNoOpMethod(cw, "$$crochetCopyFieldsFrom", "(Ljava/lang/Object;)V");
        emitNoOpMethod(cw, "$$crochetPropagateCheckpoint", "(I)V");
        emitNoOpMethod(cw, "$$crochetPropagateRollback", "(I)V");
        emitIntReturnMethod(cw, "$$crochetGetVersion", "()I");
        emitNoOpMethod(cw, "$$crochetSetVersion", "(I)V");
        emitObjectReturnMethod(cw, "$$crochetGetSnap", "()Ljava/lang/Object;");
        emitNoOpMethod(cw, "$$crochetSetSnap", "(Ljava/lang/Object;)V");
        emitBooleanReturnMethod(cw, "$$crochetIsRollbackState", "()Z");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a synthetic class file that has all surface elements except
     * {@code $$crochetAccess}, simulating a downstream agent that silently
     * removes the method.
     */
    public static byte[] buildClassMissingAccessMethod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/BrokenBean",
                null, "java/lang/Object", new String[]{CRIJ_INTERFACE});
        cw.visitAnnotation(ANNOTATION_DESC, false).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
                "$$crochetVersion", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
                "$$crochetSnap", "Ljava/lang/Object;", null, null).visitEnd();
        // $$crochetAccess is intentionally omitted to simulate a broken composition
        emitNoOpMethod(cw, "$$crochetCheckpoint", "(I)V");
        emitNoOpMethod(cw, "$$crochetRollback", "(I)V");
        emitNoOpMethod(cw, "$$crochetCopyFieldsTo", "(Ljava/lang/Object;)V");
        emitNoOpMethod(cw, "$$crochetCopyFieldsFrom", "(Ljava/lang/Object;)V");
        emitNoOpMethod(cw, "$$crochetPropagateCheckpoint", "(I)V");
        emitNoOpMethod(cw, "$$crochetPropagateRollback", "(I)V");
        emitIntReturnMethod(cw, "$$crochetGetVersion", "()I");
        emitNoOpMethod(cw, "$$crochetSetVersion", "(I)V");
        emitObjectReturnMethod(cw, "$$crochetGetSnap", "()Ljava/lang/Object;");
        emitNoOpMethod(cw, "$$crochetSetSnap", "(Ljava/lang/Object;)V");
        emitBooleanReturnMethod(cw, "$$crochetIsRollbackState", "()Z");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a synthetic interface class file (ACC_INTERFACE), which should
     * always be silently skipped by the verifier.
     */
    public static byte[] buildInterfaceWithoutSurface() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "com/example/MyInterface", null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---------- helpers for emitting stub methods ----------

    private static void emitNoOpMethod(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitIntReturnMethod(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void emitObjectReturnMethod(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void emitBooleanReturnMethod(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    // ---------- inner surface checker (duplicated from InstrumentedSurfaceVerifier) ----------

    private static final class SurfaceCheckVisitor extends ClassVisitor {

        private boolean hasAnnotation;
        private boolean hasVersionField;
        private boolean hasSnapField;
        private boolean hasAccessMethod;
        private boolean hasCheckpointMethod;
        private boolean hasRollbackMethod;
        private boolean hasCrijInterface;

        SurfaceCheckVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name,
                          String signature, String superName,
                          String[] interfaces) {
            if (interfaces != null) {
                for (String iface : interfaces) {
                    if (CRIJ_INTERFACE.equals(iface)) {
                        hasCrijInterface = true;
                        break;
                    }
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (ANNOTATION_DESC.equals(descriptor)) {
                hasAnnotation = true;
            }
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name,
                                       String descriptor, String signature,
                                       Object value) {
            if ("$$crochetVersion".equals(name) && "I".equals(descriptor)) {
                hasVersionField = true;
            } else if ("$$crochetSnap".equals(name)
                    && "Ljava/lang/Object;".equals(descriptor)) {
                hasSnapField = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name,
                                          String descriptor, String signature,
                                          String[] exceptions) {
            if ("$$crochetAccess".equals(name) && "()V".equals(descriptor)) {
                hasAccessMethod = true;
            } else if ("$$crochetCheckpoint".equals(name) && "(I)V".equals(descriptor)) {
                hasCheckpointMethod = true;
            } else if ("$$crochetRollback".equals(name) && "(I)V".equals(descriptor)) {
                hasRollbackMethod = true;
            }
            return null;
        }

        List<String> missing() {
            List<String> out = new java.util.ArrayList<>();
            if (!hasAnnotation)       out.add("@CrochetInstrumented");
            if (!hasVersionField)     out.add("$$crochetVersion");
            if (!hasSnapField)        out.add("$$crochetSnap");
            if (!hasAccessMethod)     out.add("$$crochetAccess");
            if (!hasCheckpointMethod) out.add("$$crochetCheckpoint");
            if (!hasRollbackMethod)   out.add("$$crochetRollback");
            if (!hasCrijInterface)    out.add("CRIJInstrumented");
            return out;
        }
    }
}
