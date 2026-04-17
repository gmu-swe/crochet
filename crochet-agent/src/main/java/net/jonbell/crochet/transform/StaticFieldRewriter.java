package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

/**
 * Gap 3 (bytecode): inserts a runtime registration pre-hook before every
 * GETSTATIC / PUTSTATIC targeting an instrumented user class.
 */
public final class StaticFieldRewriter extends ClassVisitor {

    private static final String AGENT_INTERNAL = "net/jonbell/crochet/runtime/CheckpointRollbackAgent";
    private static final String SF_HELPER_FOR_DESC =
            "(Ljava/lang/Class;)Lnet/jonbell/crochet/runtime/CRIJInstrumented;";

    public StaticFieldRewriter(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) {
            return null;
        }
        if (name.startsWith("$$crochet")
                || "<init>".equals(name)
                || "<clinit>".equals(name)) {
            return base;
        }
        LocalVariablesSorter lvs = new LocalVariablesSorter(access, descriptor, base);
        return new WrapStaticsMV(api, lvs, lvs);
    }

    private static final class WrapStaticsMV extends MethodVisitor {
        private final LocalVariablesSorter lvs;

        WrapStaticsMV(int api, MethodVisitor delegate, LocalVariablesSorter lvs) {
            super(api, delegate);
            this.lvs = lvs;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!shouldWrap(opcode, owner, name)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.GETSTATIC) {
                emitPreHook(owner);
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.PUTSTATIC) {
                Type t = Type.getType(descriptor);
                int slot = lvs.newLocal(t);
                int storeOp = t.getOpcode(Opcodes.ISTORE);
                int loadOp = t.getOpcode(Opcodes.ILOAD);
                super.visitVarInsn(storeOp, slot);
                emitPreHook(owner);
                super.visitVarInsn(loadOp, slot);
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        private void emitPreHook(String owner) {
            super.visitLdcInsn(Type.getObjectType(owner));
            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                    "sfHelperFor", SF_HELPER_FOR_DESC, false);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    "net/jonbell/crochet/runtime/CRIJInstrumented",
                    "$$crochetAccess", "()V", true);
        }

        private static boolean shouldWrap(int opcode, String owner, String name) {
            if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.PUTSTATIC) {
                return false;
            }
            if (owner == null || name == null) {
                return false;
            }
            if (owner.startsWith("java/") || owner.startsWith("jdk/")
                    || owner.startsWith("sun/") || owner.startsWith("com/sun/")) {
                return false;
            }
            if (owner.startsWith("net/jonbell/crochet/")) {
                return false;
            }
            if (name.startsWith("$$crochet")) {
                return false;
            }
            return true;
        }
    }
}
