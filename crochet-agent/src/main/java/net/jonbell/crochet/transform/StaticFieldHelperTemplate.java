package net.jonbell.crochet.transform;

import java.util.ArrayList;
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
 *
 * <p>The CRIJ surface methods that are byte-for-byte identical with what
 * {@link FieldAdder} emits on the user class — get/set version + snap,
 * copy-fields, access no-op — are shared via {@link InstrumentedSurfaceEmitter}.
 * The eager checkpoint/rollback and simple-parity is-rollback-state stay here
 * because they differ from the user-class bodies.
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

        // Adapt our public FieldRecord list into the shared emitter's
        // internal FieldRef list for the copy-fields loops (same name +
        // descriptor; the twoSlot flag is irrelevant to those emitters).
        List<InstrumentedSurfaceEmitter.FieldRef> mirrors = new ArrayList<>(statics.size());
        for (FieldRecord f : statics) {
            mirrors.add(new InstrumentedSurfaceEmitter.FieldRef(f.name, f.descriptor));
        }

        emitCtor(cw);
        InstrumentedSurfaceEmitter.emitCopyFieldsTo(cw, helperInternal, mirrors);
        InstrumentedSurfaceEmitter.emitCopyFieldsFrom(cw, helperInternal, mirrors);
        emitCheckpoint(cw, userInternal, helperInternal, statics);
        emitRollback(cw, userInternal, helperInternal, statics);
        InstrumentedSurfaceEmitter.emitGetVersion(cw, helperInternal);
        InstrumentedSurfaceEmitter.emitSetVersion(cw, helperInternal);
        InstrumentedSurfaceEmitter.emitGetSnap(cw, helperInternal);
        InstrumentedSurfaceEmitter.emitSetSnap(cw, helperInternal);
        InstrumentedSurfaceEmitter.emitPropagateNoop(cw, "$$crochetPropagateCheckpoint");
        InstrumentedSurfaceEmitter.emitPropagateNoop(cw, "$$crochetPropagateRollback");
        InstrumentedSurfaceEmitter.emitAccessNoop(cw);
        InstrumentedSurfaceEmitter.emitIsRollbackStateSimple(cw, helperInternal);
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
}
