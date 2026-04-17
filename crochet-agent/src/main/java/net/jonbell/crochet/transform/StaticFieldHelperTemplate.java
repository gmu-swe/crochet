package net.jonbell.crochet.transform;

import java.util.List;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Gap 3 (bytecode): emits a per-user-class hidden helper class that carries
 * the {@link net.jonbell.crochet.runtime.CRIJInstrumented} surface for a
 * user class's static fields.
 *
 * <p>Helper design (modernized vs. legacy): eager snapshot semantics. The
 * helper's {@code $$crochetCheckpoint} / {@code $$crochetRollback} do the
 * snapshot/restore directly on the user class's static slots (via
 * {@code GETSTATIC C.f ↔ PUTFIELD this.f}). No Fast proxy / klass-swap on
 * the helper — hidden classes can't be named as super in a class file, and
 * the helper's role is purely state-machine machinery (no user-level
 * GETFIELD on the helper).
 *
 * <h2>Helper layout</h2>
 * <ul>
 *   <li>One instance field per mirrored static field, same name/descriptor.
 *   <li>{@code $$crochetVersion} / {@code $$crochetSnap} — CRIJ state slots.
 *   <li>{@code $$crochetCheckpoint(v)}: copy user class's live statics into
 *       this helper's mirror fields; version-guard for idempotence.
 *   <li>{@code $$crochetRollback(v)}: copy this helper's mirror fields back
 *       to the user class's live statics; version-guard for idempotence.
 *   <li>{@code $$crochetAccess()}: no-op — the StaticFieldRewriter calls it
 *       to trigger lazy helper creation; snapshotting is eager at
 *       checkpoint time so no per-access work is needed.
 *   <li>Propagate methods are no-ops.
 * </ul>
 */
public final class StaticFieldHelperTemplate {

    /** Field descriptor slice — name + descriptor. */
    public static final class FieldRecord {
        public final String name;
        public final String descriptor;
        public final boolean twoSlot;

        public FieldRecord(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
            this.twoSlot = "J".equals(descriptor) || "D".equals(descriptor);
        }
    }

    private static final String INSTRUMENTED = "net/jonbell/crochet/runtime/CRIJInstrumented";

    private StaticFieldHelperTemplate() {}

    public static byte[] emit(String userInternal, String helperInternal, List<FieldRecord> statics) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V11,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                helperInternal, null,
                "java/lang/Object",
                new String[] { INSTRUMENTED });

        for (FieldRecord f : statics) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                    f.name, f.descriptor, null, null).visitEnd();
        }

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetVersion", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetSnap", "Ljava/lang/Object;", null, null).visitEnd();

        emitCtor(cw);
        emitCopyFieldsTo(cw, helperInternal, statics);
        emitCopyFieldsFrom(cw, helperInternal, statics);
        emitCheckpoint(cw, userInternal, helperInternal, statics);
        emitRollback(cw, userInternal, helperInternal, statics);
        emitGetVersion(cw, helperInternal);
        emitSetVersion(cw, helperInternal);
        emitGetSnap(cw, helperInternal);
        emitSetSnap(cw, helperInternal);
        emitPropagateNoop(cw, "$$crochetPropagateCheckpoint");
        emitPropagateNoop(cw, "$$crochetPropagateRollback");
        emitAccess(cw);
        emitIsRollbackState(cw, helperInternal);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitCtor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitCopyFieldsTo(ClassWriter cw, String helperInternal,
                                         List<FieldRecord> statics) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCopyFieldsTo", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        for (FieldRecord f : statics) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, helperInternal);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, helperInternal, f.name, f.descriptor);
            mv.visitFieldInsn(Opcodes.PUTFIELD, helperInternal, f.name, f.descriptor);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitCopyFieldsFrom(ClassWriter cw, String helperInternal,
                                           List<FieldRecord> statics) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCopyFieldsFrom", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        for (FieldRecord f : statics) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, helperInternal);
            mv.visitFieldInsn(Opcodes.GETFIELD, helperInternal, f.name, f.descriptor);
            mv.visitFieldInsn(Opcodes.PUTFIELD, helperInternal, f.name, f.descriptor);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitCheckpoint(ClassWriter cw, String userInternal,
                                       String helperInternal, List<FieldRecord> statics) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCheckpoint", "(I)V", null, null);
        mv.visitCode();
        emitVersionGuard(mv, helperInternal);
        for (FieldRecord f : statics) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETSTATIC, userInternal, f.name, f.descriptor);
            mv.visitFieldInsn(Opcodes.PUTFIELD, helperInternal, f.name, f.descriptor);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitRollback(ClassWriter cw, String userInternal,
                                     String helperInternal, List<FieldRecord> statics) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetRollback", "(I)V", null, null);
        mv.visitCode();
        emitVersionGuard(mv, helperInternal);
        for (FieldRecord f : statics) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, helperInternal, f.name, f.descriptor);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, userInternal, f.name, f.descriptor);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits: {@code if (this.version >= v) return; this.version = v;}
     */
    private static void emitVersionGuard(MethodVisitor mv, String helperInternal) {
        Label proceed = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, helperInternal, "$$crochetVersion", "I");
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, proceed);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(proceed);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, helperInternal, "$$crochetVersion", "I");
    }

    private static void emitGetVersion(ClassWriter cw, String helperInternal) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetGetVersion", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, helperInternal, "$$crochetVersion", "I");
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSetVersion(ClassWriter cw, String helperInternal) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetSetVersion", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, helperInternal, "$$crochetVersion", "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitGetSnap(ClassWriter cw, String helperInternal) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetGetSnap", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, helperInternal, "$$crochetSnap", "Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSetSnap(ClassWriter cw, String helperInternal) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetSetSnap", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, helperInternal, "$$crochetSnap", "Ljava/lang/Object;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitPropagateNoop(ClassWriter cw, String methodName) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                methodName, "(I)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitAccess(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetAccess", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitIsRollbackState(ClassWriter cw, String helperInternal) {
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetIsRollbackState", "()Z", null, null);
        mv.visitCode();
        Label notRollback = new Label();
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, helperInternal, "$$crochetVersion", "I");
        mv.visitJumpInsn(Opcodes.IFEQ, notRollback);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, helperInternal, "$$crochetVersion", "I");
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IREM);
        mv.visitJumpInsn(Opcodes.IFNE, notRollback);

        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(notRollback);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(done);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
