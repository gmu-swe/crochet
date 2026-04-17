package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Wraps GETFIELD/PUTFIELD instructions whose owner is an instrumented user
 * class with a preceding call to {@code ownerRef.$$crochetAccess()}. When the
 * ownerRef is of a user-class type, this runs the no-op base implementation;
 * when the ownerRef's klass has been swapped to the Fast proxy, this triggers
 * the lazy snapshot/restore in {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#fastAccess}.
 *
 * <p>V1 scope: only wraps 1-slot fields (primitives except long/double, plus
 * references). 2-slot fields (J, D) fall through without wrapping — the demo
 * doesn't need them; they'll come with a local-variable-sorter pass later.
 *
 * <p>Skips synthetic CROCHET methods ({@code $$crochet*}) and initializers
 * ({@code <init>}, {@code <clinit>}) to avoid recursion and pre-super access
 * issues respectively.
 */
public final class FieldAccessWrapper extends ClassVisitor {

    public FieldAccessWrapper(int api, ClassVisitor delegate) {
        super(api, delegate);
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
        return new WrapAccessesMV(api, base);
    }

    private static final class WrapAccessesMV extends MethodVisitor {
        WrapAccessesMV(int api, MethodVisitor delegate) {
            super(api, delegate);
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
            } else if (opcode == Opcodes.PUTFIELD) {
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
            }
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
            // 2-slot field values need DUP2-based trickery we haven't written yet.
            if ("J".equals(descriptor) || "D".equals(descriptor)) {
                return false;
            }
            return true;
        }
    }
}
