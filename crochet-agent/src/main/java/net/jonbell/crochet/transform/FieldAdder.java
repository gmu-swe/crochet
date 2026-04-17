package net.jonbell.crochet.transform;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Adds the CRIJInstrumented surface to every user class that passes the
 * CrochetTransformer filter.
 *
 * <p>For each class, this visitor:
 * <ul>
 *   <li>Declares {@code implements CRIJInstrumented} on the class.
 *   <li>Adds two injected fields:
 *     <ul>
 *       <li>{@code int $$crochetVersion} — current checkpoint/rollback version
 *       <li>{@code Object $$crochetSnap}  — a same-class shadow instance
 *           holding the checkpointed field values (null if no live checkpoint)
 *     </ul>
 *   <li>Emits the ten CRIJInstrumented methods with bodies that operate on
 *       the user class's declared instance fields:
 *     <ul>
 *       <li>{@code $$crochetCopyFieldsTo(Object)} / {@code $$crochetCopyFieldsFrom(Object)}
 *           — bulk copy of every declared instance field
 *       <li>{@code $$crochetCheckpoint(int v)} — if snap is null, allocate a
 *           shadow via {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#allocateShadow},
 *           copy fields into it, store it. Record v.
 *       <li>{@code $$crochetRollback(int v)} — if snap is non-null, copy
 *           fields back from it, clear snap. Record v.
 *       <li>getters/setters for the version field
 *       <li>propagate* — no-op placeholders (V0 scope; V1 walks reference fields)
 *       <li>{@code $$crochetAccess()} — no-op in V0
 *       <li>{@code $$crochetIsRollbackState()} — returns {@code (version % 2) == 0 && version != 0}
 *     </ul>
 * </ul>
 */
public final class FieldAdder extends ClassVisitor {

    public static final String VERSION_FIELD = "$$crochetVersion";
    public static final String SNAP_FIELD = "$$crochetSnap";

    private static final String INSTRUMENTED = "net/jonbell/crochet/runtime/CRIJInstrumented";
    private static final String AGENT = "net/jonbell/crochet/runtime/CheckpointRollbackAgent";

    /**
     * Emits the body of {@code $$crochetCheckpoint} / {@code $$crochetRollback}:
     * <pre>
     *   int priorVersion = this.$$crochetVersion;
     *   if (priorVersion >= v) return;
     *   this.$$crochetVersion = v;
     *   try {
     *       swapToFastProxy(this, ThisClass.class, priorVersion);
     *   } catch (Throwable t) {
     *       this.$$crochetVersion = priorVersion;
     *       throw t;
     *   }
     * </pre>
     * The guard enforces flat-nested-checkpoint semantics (paper §3.1) and
     * terminates cyclic propagation. The try/catch restores the pre-bump
     * version so a proxy-generation failure doesn't leave the object in a
     * half-checkpointed state.
     *
     * <p>Local layout: slot 0 is {@code this}, slot 1 is {@code v}, slot 2 is
     * {@code priorVersion}, slot 3 is the caught {@code Throwable}.
     */
    private void emitVersionGuardedEntry(MethodVisitor mv) {
        Label proceed = new Label();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label afterHandler = new Label();

        // priorVersion = this.$$crochetVersion
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, className, VERSION_FIELD, "I");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ISTORE, 2);

        // if (priorVersion >= v) return;
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, proceed);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(proceed);

        // this.$$crochetVersion = v
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, VERSION_FIELD, "I");

        // try { swapToFastProxy(this, ThisClass.class, priorVersion); }
        mv.visitLabel(tryStart);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "swapToFastProxy",
                "(Ljava/lang/Object;Ljava/lang/Class;I)V", false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, afterHandler);

        // catch (Throwable t): restore version; rethrow.
        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, VERSION_FIELD, "I");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ATHROW);

        mv.visitLabel(afterHandler);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
    }

    private String className;
    private final List<FieldRecord> instanceFields = new ArrayList<>();
    private boolean alreadyInstrumented;
    private boolean hasVersionField;
    private boolean hasSnapField;

    public FieldAdder(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name;
        String[] newIfaces = interfaces;
        boolean hasMarker = false;
        if (interfaces != null) {
            for (String i : interfaces) {
                if (INSTRUMENTED.equals(i)) {
                    hasMarker = true;
                    break;
                }
            }
        }
        if (!hasMarker) {
            int n = interfaces == null ? 0 : interfaces.length;
            newIfaces = new String[n + 1];
            if (n > 0) {
                System.arraycopy(interfaces, 0, newIfaces, 0, n);
            }
            newIfaces[n] = INSTRUMENTED;
        } else {
            alreadyInstrumented = true;
        }
        super.visit(version, access, name, signature, superName, newIfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor,
                                   String signature, Object value) {
        if (VERSION_FIELD.equals(name)) {
            hasVersionField = true;
        } else if (SNAP_FIELD.equals(name)) {
            hasSnapField = true;
        } else if ((access & Opcodes.ACC_STATIC) == 0
                && !name.startsWith("$$crochet")) {
            instanceFields.add(new FieldRecord(name, descriptor));
        }
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public void visitEnd() {
        if (!alreadyInstrumented) {
            if (!hasVersionField) {
                super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                        VERSION_FIELD, "I", null, null).visitEnd();
            }
            if (!hasSnapField) {
                super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                        SNAP_FIELD, "Ljava/lang/Object;", null, null).visitEnd();
            }
            emitCopyFieldsTo();
            emitCopyFieldsFrom();
            emitCheckpoint();
            emitRollback();
            emitGetVersion();
            emitSetVersion();
            emitGetSnap();
            emitSetSnap();
            emitPropagateCheckpoint();
            emitPropagateRollback();
            emitAccess();
            emitIsRollbackState();
        }
        super.visitEnd();
    }

    private void emitCopyFieldsTo() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCopyFieldsTo", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        // ((ThisClass) arg).field_i = this.field_i    for each instance field
        for (FieldRecord f : instanceFields) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, className);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, className, f.name, f.descriptor);
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, f.name, f.descriptor);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitCopyFieldsFrom() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCopyFieldsFrom", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        // this.field_i = ((ThisClass) arg).field_i    for each instance field
        for (FieldRecord f : instanceFields) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, className);
            mv.visitFieldInsn(Opcodes.GETFIELD, className, f.name, f.descriptor);
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, f.name, f.descriptor);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitCheckpoint() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCheckpoint", "(I)V", null, null);
        mv.visitCode();
        emitVersionGuardedEntry(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitRollback() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetRollback", "(I)V", null, null);
        mv.visitCode();
        emitVersionGuardedEntry(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitGetSnap() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetGetSnap", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, className, SNAP_FIELD, "Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitSetSnap() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetSetSnap", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, SNAP_FIELD, "Ljava/lang/Object;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitGetVersion() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetGetVersion", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, className, VERSION_FIELD, "I");
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitSetVersion() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetSetVersion", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, VERSION_FIELD, "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitPropagateCheckpoint() {
        emitPropagate("$$crochetPropagateCheckpoint", "$$crochetCheckpoint");
    }

    private void emitPropagateRollback() {
        emitPropagate("$$crochetPropagateRollback", "$$crochetRollback");
    }

    /**
     * Emits the one-step lazy heap traversal for reference fields: for every
     * non-static reference field of the declaring class, {@code if (f instanceof
     * CRIJInstrumented) ((CRIJInstrumented) f).$$crochet{Checkpoint,Rollback}(version)}.
     * The {@code instanceof} check handles {@code null} and non-instrumented
     * referents uniformly. Cycle termination comes from the version guard on
     * the target method, not from a traversed-set here.
     */
    private void emitPropagate(String methodName, String iMethodName) {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                methodName, "(I)V", null, null);
        mv.visitCode();
        for (FieldRecord f : instanceFields) {
            if (!f.descriptor.startsWith("L")) {
                continue;
            }
            Label skip = new Label();
            Label after = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, className, f.name, f.descriptor);
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
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitAccess() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetAccess", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitIsRollbackState() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetIsRollbackState", "()Z", null, null);
        mv.visitCode();
        // return (version != 0) && (version % 2 == 0)
        Label notRollback = new Label();
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, className, VERSION_FIELD, "I");
        mv.visitJumpInsn(Opcodes.IFEQ, notRollback);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, className, VERSION_FIELD, "I");
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

    private static final class FieldRecord {
        final String name;
        final String descriptor;

        FieldRecord(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }
    }
}
