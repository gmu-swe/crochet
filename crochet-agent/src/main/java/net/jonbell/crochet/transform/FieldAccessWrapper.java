package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
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
 * <p>Skips synthetic CROCHET methods ({@code $$crochet*}) and initializers
 * ({@code <init>}, {@code <clinit>}) to avoid recursion and pre-super access
 * issues respectively.
 */
public final class FieldAccessWrapper extends ClassVisitor {

    private final SharedLocalsProvider locals;

    public FieldAccessWrapper(int api, ClassVisitor delegate, SharedLocalsProvider locals) {
        super(api, delegate);
        this.locals = locals;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) {
            return null;
        }
        if (name.startsWith("$$crochet") || "<init>".equals(name) || "<clinit>".equals(name)) {
            return base;
        }
        return new WrapAccessesMV(api, base, locals);
    }

    private static final class WrapAccessesMV extends MethodVisitor {
        private final SharedLocalsProvider locals;

        WrapAccessesMV(int api, MethodVisitor delegate, SharedLocalsProvider locals) {
            super(api, delegate);
            this.locals = locals;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!shouldWrap(opcode, owner, descriptor)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.GETFIELD) {
                // stack: [..., objref]
                super.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner,
                        "$$crochetAccess", "()V", false);
                // stack: [..., objref]
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.PUTFIELD) {
                boolean twoSlot = "J".equals(descriptor) || "D".equals(descriptor);
                if (!twoSlot) {
                    // stack: [..., objref, value]
                    super.visitInsn(Opcodes.SWAP);
                    // stack: [..., value, objref]
                    super.visitInsn(Opcodes.DUP);
                    // stack: [..., value, objref, objref]
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner,
                            "$$crochetAccess", "()V", false);
                    // stack: [..., value, objref]
                    super.visitInsn(Opcodes.SWAP);
                    // stack: [..., objref, value]
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                    return;
                }
                // 2-slot PUTFIELD: stash the wide value in a scratch local
                // pulled from the chain-wide LVS, leaving objref on top so we
                // can duplicate it for the hook. Scratch store/load are
                // emitted via locals.emitVarInsn so they bypass the LVS's
                // remap table — that table keys on (var, size) not type, and
                // would otherwise alias our scratch with an original slot
                // that happens to share the numeric index.
                Type vt = "J".equals(descriptor) ? Type.LONG_TYPE : Type.DOUBLE_TYPE;
                int slot = locals.sharedScratch(vt);
                int storeOp = vt.getOpcode(Opcodes.ISTORE);
                int loadOp = vt.getOpcode(Opcodes.ILOAD);
                // stack: [..., objref, v_hi, v_lo]
                locals.emitVarInsn(storeOp, slot);
                // stack: [..., objref]
                super.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner,
                        "$$crochetAccess", "()V", false);
                // stack: [..., objref]
                locals.emitVarInsn(loadOp, slot);
                // stack: [..., objref, v_hi, v_lo]
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            // GETSTATIC/PUTSTATIC filtered out by shouldWrap; defensive pass-through.
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        private static boolean shouldWrap(int opcode, String owner, String descriptor) {
            if (opcode != Opcodes.GETFIELD && opcode != Opcodes.PUTFIELD) {
                return false;
            }
            if (owner == null) {
                return false;
            }
            if (owner.startsWith("java/") || owner.startsWith("jdk/")
                    || owner.startsWith("sun/") || owner.startsWith("com/sun/")) {
                return false;
            }
            if (owner.startsWith("net/jonbell/crochet/")) {
                return false;
            }
            return true;
        }
    }
}
