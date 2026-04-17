// SKETCH — design-only. Not compiled. See DESIGN.md §5.
//
// Unlike ProxyTemplate (a single byte[] rewritten per user class by
// Specializer), the SF helper has a field layout that *depends* on the user
// class's statics. So we emit bytes procedurally per user class and cache in
// ClassMeta.
//
// The Fast-state proxy for the helper (generated for klass-swap during
// checkpoint) is a completely separate class: the existing ProxyTemplate +
// Specializer pair works unchanged when given the helper as the "user class",
// because the helper implements CRIJInstrumented and has the two $$crochet
// fields in the right places.

package net.jonbell.crochet.transform;

import java.util.List;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Emits the bytes for {@code <userClass>$$crochetSFHelper} — one instance
 * field per user-class static, plus the CRIJ surface.
 */
public final class StaticFieldHelperTemplate {

    static final String CRIJ_INSTRUMENTED = "net/jonbell/crochet/runtime/CRIJInstrumented";

    private StaticFieldHelperTemplate() {}

    /** @param userInternal e.g. "com/example/A"
     *  @param statics      the non-final (and final-reference; see DESIGN §3)
     *                      mutable static fields of the user class. */
    public static byte[] emit(String userInternal, List<FieldRecord> statics) {
        String helperInternal = userInternal + "$$crochetSFHelper";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V11,
                 Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                 helperInternal, null,
                 "java/lang/Object",
                 new String[] { CRIJ_INSTRUMENTED });

        // mirror fields
        for (FieldRecord f : statics) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                          f.name, f.descriptor, null, null).visitEnd();
        }

        // injected CRIJ slots (same names/types as FieldAdder's)
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                      "$$crochetVersion", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                      "$$crochetSnap", "Ljava/lang/Object;", null, null).visitEnd();

        emitCtor(cw);

        // Delegate the ten CRIJ method bodies to a shared emitter (design Q:
        // factor FieldAdder.emit* into CrijSurface.emitAll(cw, helperInternal, statics);
        // the only method that differs from the user-class case is
        // $$crochetInitialMirror, which reads static slots from the user class.
        CrijSurface.emitAll(cw, helperInternal, statics);
        emitInitialMirror(cw, userInternal, helperInternal, statics);

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

    /**
     * $$crochetInitialMirror(): read every static slot from the user class
     * and write it into this helper's mirror instance field. Called exactly
     * once per helper by the first {@code sfHelper(C.class)} invocation.
     * <p>Note this method is NOT part of the CRIJInstrumented contract — it
     * is a helper-class-only method invoked via reflection or a tagged
     * interface from the runtime (choose one before coding). See DESIGN §5.
     */
    private static void emitInitialMirror(ClassWriter cw,
                                          String userInternal,
                                          String helperInternal,
                                          List<FieldRecord> statics) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                                          "$$crochetInitialMirror", "()V", null, null);
        mv.visitCode();
        for (FieldRecord f : statics) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);                                   // this
            mv.visitFieldInsn(Opcodes.GETSTATIC, userInternal, f.name, f.descriptor);
            mv.visitFieldInsn(Opcodes.PUTFIELD, helperInternal, f.name, f.descriptor);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Plain-old tuple. In the real code this lives in a shared package-private type. */
    public static final class FieldRecord {
        public final String name, descriptor;
        public FieldRecord(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    /** STUB — the extracted bodies from FieldAdder's emit* methods. */
    static final class CrijSurface {
        static void emitAll(ClassWriter cw, String ownerInternal, List<FieldRecord> fields) {
            // emit $$crochetCopyFieldsTo/From, $$crochetCheckpoint, $$crochetRollback,
            // $$crochetGetVersion/$$crochetSetVersion, $$crochetGetSnap/$$crochetSetSnap,
            // $$crochetPropagateCheckpoint/$$crochetPropagateRollback, $$crochetAccess,
            // $$crochetIsRollbackState — identical to FieldAdder.emit* but parameterized
            // on ownerInternal + the field list.
            throw new UnsupportedOperationException("sketch");
        }
    }
}
</content>
</invoke>