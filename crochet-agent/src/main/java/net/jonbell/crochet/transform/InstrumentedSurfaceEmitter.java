package net.jonbell.crochet.transform;

import java.util.List;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Shared emitters for the {@code $$crochet*} method surface that is attached
 * both to every user class (via {@link FieldAdder}) and to every per-user
 * static-field helper class (via {@link StaticFieldHelperTemplate}).
 *
 * <p>Every method in this class emits bytecode that is byte-for-byte identical
 * to the hand-written emit routines that previously lived in those two files,
 * so dumping a transformed class with {@code -Dcrochet.dumpClasses=true} and
 * running {@code javap -v} produces the same output before and after this
 * extraction. Emit methods either take only the owner's internal name (for
 * bodies that just touch {@code $$crochetVersion} / {@code $$crochetSnap} on
 * {@code this}) or the owner plus a field list (for copy/propagate loops).
 *
 * <p>The two callers still own the bodies that genuinely differ:
 * <ul>
 *   <li>{@code FieldAdder} keeps {@code emitVersionGuardedEntry} (sentinel CAS
 *       + klass swap) — that is the user-class-specific checkpoint entry.
 *   <li>{@code StaticFieldHelperTemplate} keeps its eager
 *       {@code $$crochetCheckpoint} / {@code $$crochetRollback} bodies — they
 *       snapshot the user class's static slots directly on the helper, not
 *       via the proxy mechanism.
 *   <li>{@code $$crochetIsRollbackState} has two variants: the user class uses
 *       the sentinel-aware {@link #emitIsRollbackStateSentinel} (decodes
 *       {@code -v} via {@code Math.abs}, intrinsified to a branchless CMOV);
 *       the helper uses the simpler {@link #emitIsRollbackStateSimple} because
 *       its version never carries a sentinel (eager snapshot, no klass swap).
 *   <li>{@code $$crochetPropagate*} on the user class walks reference fields
 *       (see {@link #emitPropagateRefFields}); on the helper it is a no-op
 *       (see {@link #emitPropagateNoop}).
 * </ul>
 */
final class InstrumentedSurfaceEmitter {

    static final String VERSION_FIELD = "$$crochetVersion";
    static final String SNAP_FIELD = "$$crochetSnap";

    private static final String INSTRUMENTED = "net/jonbell/crochet/runtime/CRIJInstrumented";

    private InstrumentedSurfaceEmitter() {}

    /** Name + descriptor pair for fields touched by emit loops. */
    static final class FieldRef {
        final String name;
        final String descriptor;

        FieldRef(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    // ---------------------------------------------------------------------
    // $$crochetGetVersion / SetVersion / GetSnap / SetSnap — bodies are
    // identical on the user class and on the helper, parametrized only by
    // the owner's internal name.
    // ---------------------------------------------------------------------

    static void emitGetVersion(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetGetVersion", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, VERSION_FIELD, "I");
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    static void emitSetVersion(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetSetVersion", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, VERSION_FIELD, "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    static void emitGetSnap(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetGetSnap", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, SNAP_FIELD, "Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    static void emitSetSnap(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetSetSnap", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, SNAP_FIELD, "Ljava/lang/Object;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ---------------------------------------------------------------------
    // $$crochetAccess — no-op body on both the user class and the helper.
    // The Fast proxy overrides this on the user class (see ProxyTemplate).
    // ---------------------------------------------------------------------

    static void emitAccessNoop(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetAccess", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ---------------------------------------------------------------------
    // $$crochetCopyFieldsTo / $$crochetCopyFieldsFrom — same shape on both
    // sides, parametrized by the owner and by the owner's own field list
    // (user's declared instance fields for FieldAdder, helper's mirror
    // fields for StaticFieldHelperTemplate).
    // ---------------------------------------------------------------------

    /**
     * True iff {@code superInternal} refers to an instrumented super that
     * carries the $$crochet* surface. {@link java.lang.Object} and the
     * handful of classes in {@link CrochetTransformer#shouldSkip} are
     * bypassed: calling {@code super.$$crochetCopyFieldsTo} on them would
     * land on {@link java.lang.Object}'s non-existent method and fail
     * verification / linkage at runtime.
     */
    private static boolean superIsInstrumented(String superInternal) {
        if (superInternal == null || "java/lang/Object".equals(superInternal)) {
            return false;
        }
        if (CrochetTransformer.shouldSkip(superInternal)) {
            return false;
        }
        return true;
    }

    static void emitCopyFieldsTo(ClassVisitor cv, String owner, String superName,
                                 List<FieldRef> fields) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCopyFieldsTo", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        if (superIsInstrumented(superName)) {
            // super.$$crochetCopyFieldsTo(arg) — ensures inherited fields
            // are also copied to the shadow. Without this chain, a subclass
            // like {@code LinkedHashMap$Entry} would skip its own {@code
            // HashMap$Node}-inherited {@code value} / {@code next} fields,
            // and rollback would not restore them.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName,
                    "$$crochetCopyFieldsTo", "(Ljava/lang/Object;)V", false);
        }
        // ((Owner) arg).field = this.field    for each field declared here
        for (FieldRef f : fields) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, f.name, f.descriptor);
            mv.visitFieldInsn(Opcodes.PUTFIELD, owner, f.name, f.descriptor);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    static void emitCopyFieldsFrom(ClassVisitor cv, String owner, String superName,
                                   List<FieldRef> fields) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCopyFieldsFrom", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        if (superIsInstrumented(superName)) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName,
                    "$$crochetCopyFieldsFrom", "(Ljava/lang/Object;)V", false);
        }
        // this.field = ((Owner) arg).field    for each field declared here
        for (FieldRef f : fields) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, f.name, f.descriptor);
            mv.visitFieldInsn(Opcodes.PUTFIELD, owner, f.name, f.descriptor);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // Back-compat overloads for StaticFieldHelperTemplate callers that have
    // no real super chain to walk (helper class extends Object directly).
    static void emitCopyFieldsTo(ClassVisitor cv, String owner, List<FieldRef> fields) {
        emitCopyFieldsTo(cv, owner, "java/lang/Object", fields);
    }

    static void emitCopyFieldsFrom(ClassVisitor cv, String owner, List<FieldRef> fields) {
        emitCopyFieldsFrom(cv, owner, "java/lang/Object", fields);
    }

    // ---------------------------------------------------------------------
    // $$crochetPropagateCheckpoint / $$crochetPropagateRollback
    //
    // The user class walks each reference field f: if (f instanceof
    // CRIJInstrumented) ((CRIJInstrumented) f).$$crochet{Checkpoint,Rollback}(v).
    // The {@code instanceof} check handles null and non-instrumented
    // referents uniformly; cycle termination comes from the target's own
    // version guard.
    //
    // The helper class has no referents to walk and emits an empty body.
    // ---------------------------------------------------------------------

    static void emitPropagateRefFields(ClassVisitor cv, String owner, String superName,
                                       String methodName, String iMethodName,
                                       List<FieldRef> fields) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                methodName, "(I)V", null, null);
        mv.visitCode();
        if (superIsInstrumented(superName)) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName,
                    methodName, "(I)V", false);
        }
        for (FieldRef f : fields) {
            if (f.descriptor.startsWith("L")) {
                Label skip = new Label();
                Label after = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, f.name, f.descriptor);
                mv.visitInsn(Opcodes.DUP);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, INSTRUMENTED);
                mv.visitJumpInsn(Opcodes.IFEQ, skip);
                mv.visitTypeInsn(Opcodes.CHECKCAST, INSTRUMENTED);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, INSTRUMENTED, iMethodName, "(I)V", true);
                mv.visitJumpInsn(Opcodes.GOTO, after);
                mv.visitLabel(skip);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(after);
            } else if (isReferenceArrayDescriptor(f.descriptor)) {
                // Reference-element array field: delegate iteration to
                // the runtime helper so we don't inflate emitted bytecode
                // with a loop per array field. The helper handles null and
                // non-CRIJInstrumented elements uniformly.
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, f.name, f.descriptor);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                boolean checkpoint = "$$crochetCheckpoint".equals(iMethodName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "net/jonbell/crochet/runtime/ArrayRegistry",
                        checkpoint ? "propagateArrayCheckpoint" : "propagateArrayRollback",
                        "(Ljava/lang/Object;I)V", false);
            }
            // Primitive-element arrays ([I, [Z, ...): nothing to propagate.
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Back-compat overload: used by {@link StaticFieldHelperTemplate} which
     * doesn't have a super chain to walk.
     */
    static void emitPropagateRefFields(ClassVisitor cv, String owner,
                                       String methodName, String iMethodName,
                                       List<FieldRef> fields) {
        emitPropagateRefFields(cv, owner, "java/lang/Object", methodName, iMethodName, fields);
    }

    /** True iff the descriptor is an array of reference elements (e.g. {@code [Ljava/util/HashMap$Node;}). */
    private static boolean isReferenceArrayDescriptor(String descriptor) {
        if (descriptor == null) return false;
        int i = 0;
        while (i < descriptor.length() && descriptor.charAt(i) == '[') i++;
        if (i == 0 || i >= descriptor.length()) return false;
        char c = descriptor.charAt(i);
        return c == 'L';
    }

    static void emitPropagateNoop(ClassVisitor cv, String methodName) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                methodName, "(I)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ---------------------------------------------------------------------
    // $$crochetIsRollbackState — two variants.
    //
    // The user class (FieldAdder) has to decode the sentinel {@code -v} that
    // emitVersionGuardedEntry writes between bumping the counter and
    // completing the klass swap. It uses
    //     int rv = Math.abs(this.$$crochetVersion);
    //     return rv != 0 && (rv & 1) == 0;
    // Math.abs is intrinsified to a branchless CMOV on HotSpot, saving a
    // branch + GOTO vs. a hand-written conditional negate.
    //
    // The helper never carries a sentinel (eager snapshot, no klass swap),
    // so it uses the simpler
    //     int v = this.$$crochetVersion;
    //     return v != 0 && (v % 2) == 0;
    // ---------------------------------------------------------------------

    static void emitIsRollbackStateSentinel(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetIsRollbackState", "()Z", null, null);
        mv.visitCode();
        Label notRollback = new Label();
        Label done = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, VERSION_FIELD, "I");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs",
                "(I)I", false);
        // stack: [rv]
        mv.visitVarInsn(Opcodes.ISTORE, 1);

        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IFEQ, notRollback);

        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IAND);
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

    static void emitIsRollbackStateSimple(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetIsRollbackState", "()Z", null, null);
        mv.visitCode();
        Label notRollback = new Label();
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, VERSION_FIELD, "I");
        mv.visitJumpInsn(Opcodes.IFEQ, notRollback);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, VERSION_FIELD, "I");
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
