package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Wraps GETFIELD/PUTFIELD instructions whose owner is an instrumented user
 * class with a preceding call to {@code ownerRef.$$crochetAccess()}. When the
 * ownerRef is of a user-class type this runs the no-op base implementation;
 * when the ownerRef's klass has been swapped to the Fast proxy, this triggers
 * the lazy snapshot/restore in
 * {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#fastAccess}.
 *
 * <p>1-slot (Z, B, C, S, I, F, L, [) descriptors wrap via pure SWAP/DUP/SWAP.
 * 2-slot (J, D) descriptors stash the value in a scratch local allocated from
 * the chain-wide {@link SharedLocalsProvider} — the visitor never extends a
 * {@link org.objectweb.asm.commons.LocalVariablesSorter} of its own; multiple
 * stacked LVS instances produced cumulative index rewrites that confused
 * {@code COMPUTE_FRAMES} on large methods (fop's FObj, h2's Parser).
 *
 * <p>See {@link WrapAccessesMV#emitPreHook} for the emit-shape design
 * rationale (INVOKEVIRTUAL vs INVOKESTATIC-with-instanceof trade-off).
 *
 * <p>{@code <clinit>} takes the full skip — static initialisers are special
 * because our own {@code $$crochet*} field initialisers would recurse into
 * the static rewriter. {@code <init>} is handled via
 * {@link CtorAwareMv}: pre-super instructions forward unchanged (we cannot
 * read fields of {@code this} before the super-call), post-super instructions
 * are wrapped the same way as any ordinary method.
 */
public final class FieldAccessWrapper extends ClassVisitor {

    private final SharedLocalsProvider locals;
    private String className;
    private String superName;

    public FieldAccessWrapper(int api, ClassVisitor delegate, SharedLocalsProvider locals) {
        super(api, delegate);
        this.locals = locals;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name;
        this.superName = superName;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) {
            return null;
        }
        if (name.startsWith("$$crochet") || "<clinit>".equals(name)) {
            return base;
        }
        boolean isCtor = "<init>".equals(name);
        return new WrapAccessesMV(api, base, locals, className, superName, isCtor);
    }

    private static final class WrapAccessesMV extends CtorAwareMv {
        private final SharedLocalsProvider locals;

        WrapAccessesMV(int api, MethodVisitor delegate, SharedLocalsProvider locals,
                       String owner, String superName, boolean isCtor) {
            super(api, delegate, owner, superName, isCtor);
            this.locals = locals;
        }

        /**
         * Emit the pre-hook for a GETFIELD/PUTFIELD receiver currently on
         * top of stack: {@code INVOKEVIRTUAL owner.$$crochetAccess()V}.
         *
         * <p>The caller is responsible for emitting the site-level gate
         * check around this call (see {@code emitGateThen*} below).
         * This keeps the INVOKEVIRTUAL dispatch which JIT profile-guided
         * devirtualization collapses to a single-RETURN NOOP on all
         * not-yet-checkpointed instances (the dominant case), while the
         * outer {@code GETSTATIC VERSION_GATE + IFEQ} gate skips the
         * dispatch entirely in interpreter + C1 tiers.
         */
        private static void emitPreHook(MethodVisitor mv, String fOwner) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, fOwner,
                    "$$crochetAccess", "()V", false);
        }

        /**
         * Emit the {@code GETSTATIC VERSION_GATE; IFEQ skip} prefix. Caller
         * supplies the {@code skip} label and emits the pre-hook body between
         * it and the label.
         */
        private static void emitGatePrefix(MethodVisitor mv, Label skip) {
            mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "net/jonbell/crochet/runtime/RuntimeReady",
                    "VERSION_GATE", "I");
            mv.visitJumpInsn(Opcodes.IFEQ, skip);
        }

        @Override
        protected void visitFieldInsnPostSuper(int opcode, String fOwner, String name, String descriptor) {
            if (!shouldWrap(opcode, fOwner, descriptor)) {
                super.visitFieldInsnPostSuper(opcode, fOwner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.GETFIELD) {
                // stack: [..., objref]
                Label skip = new Label();
                emitGatePrefix(mv, skip);
                // stack: [..., objref]   (gate was popped by IFEQ)
                mv.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                emitPreHook(mv, fOwner);
                // stack: [..., objref]
                mv.visitLabel(skip);
                // stack: [..., objref] on both paths — fall through to GETFIELD
                mv.visitFieldInsn(opcode, fOwner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.PUTFIELD) {
                boolean twoSlot = "J".equals(descriptor) || "D".equals(descriptor);
                Label skip = new Label();
                if (!twoSlot) {
                    // stack: [..., objref, value]
                    emitGatePrefix(mv, skip);
                    // stack: [..., objref, value]
                    mv.visitInsn(Opcodes.SWAP);
                    // stack: [..., value, objref]
                    mv.visitInsn(Opcodes.DUP);
                    // stack: [..., value, objref, objref]
                    emitPreHook(mv, fOwner);
                    // stack: [..., value, objref]
                    mv.visitInsn(Opcodes.SWAP);
                    // stack: [..., objref, value]
                    mv.visitLabel(skip);
                    // Both paths converge with stack [..., objref, value].
                    mv.visitFieldInsn(opcode, fOwner, name, descriptor);
                    return;
                }
                // 2-slot PUTFIELD: stash the wide value in a scratch local
                // pulled from the chain-wide LVS on the hook path.
                Type vt = "J".equals(descriptor) ? Type.LONG_TYPE : Type.DOUBLE_TYPE;
                int slot = locals.sharedScratch(vt);
                int storeOp = vt.getOpcode(Opcodes.ISTORE);
                int loadOp = vt.getOpcode(Opcodes.ILOAD);
                // stack: [..., objref, v_hi, v_lo]
                emitGatePrefix(mv, skip);
                // stack: [..., objref, v_hi, v_lo]
                locals.emitVarInsn(storeOp, slot);
                // stack: [..., objref]
                mv.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                emitPreHook(mv, fOwner);
                // stack: [..., objref]
                locals.emitVarInsn(loadOp, slot);
                // stack: [..., objref, v_hi, v_lo]
                mv.visitLabel(skip);
                // Both paths converge with stack [..., objref, v_hi, v_lo].
                mv.visitFieldInsn(opcode, fOwner, name, descriptor);
                return;
            }
            // GETSTATIC/PUTSTATIC filtered out by shouldWrap; defensive pass-through.
            super.visitFieldInsnPostSuper(opcode, fOwner, name, descriptor);
        }

        private static boolean shouldWrap(int opcode, String owner, String descriptor) {
            if (opcode != Opcodes.GETFIELD && opcode != Opcodes.PUTFIELD) {
                return false;
            }
            if (owner == null) {
                return false;
            }
            // Gap 7 / paper-level correctness: JDK classes participate in
            // the field-wrap chain so HashMap's internal {@code this.size++},
            // {@code this.table = newTable}, etc. fire the
            // {@code $$crochetAccess} pre-hook, which lets {@code fastAccess}
            // snapshot the receiver before the write. Without this wrap,
            // checkpoint/rollback on JDK collections could not recover the
            // pre-mutation state — demo scenario 15-hashmap-instrumented
            // and paper §5.1 rely on it.
            //
            // Bootstrap safety: the emitted pre-hook is
            // {@code INVOKEVIRTUAL owner.$$crochetAccess()V}. Until a class's
            // first checkpoint ever fires, every instance's klass is the
            // stock user class whose {@code $$crochetAccess} body is a
            // single RETURN (emitted by InstrumentedSurfaceEmitter.emitAccessNoop),
            // so this is a no-op at JDK-bootstrap time.
            if (owner.startsWith("net/jonbell/crochet/")) {
                return false;
            }
            return true;
        }
    }
}
