package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Gap 3 (bytecode): inserts a runtime registration pre-hook before every
 * GETSTATIC / PUTSTATIC targeting an instrumented user class.
 *
 * <p>Pre-hook emits {@code sfHelperFor(owner.class).$$crochetAccess()} — net
 * stack delta of zero (1-slot helper ref pushed then consumed). Works without
 * any scratch local, so this visitor doesn't need the
 * {@link SharedLocalsProvider}. PUTSTATIC's existing 1- or 2-slot value sits
 * below the transient helper-ref and stays intact for the wrapped PUTSTATIC.
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
        return new WrapStaticsMV(api, base);
    }

    private static final class WrapStaticsMV extends MethodVisitor {

        WrapStaticsMV(int api, MethodVisitor delegate) {
            super(api, delegate);
        }

        /**
         * GETSTATIC/PUTSTATIC both handled without any scratch local —
         * invokestatic pushes a 1-slot helper reference, invokevirtual
         * consumes it. The original stack shape underneath is preserved,
         * so any existing 2-slot value for PUTSTATIC stays intact.
         */
        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!shouldWrap(opcode, owner, name)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.GETSTATIC) {
                // stack: [...]
                emitPreHook(owner);
                // stack: [...]
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.PUTSTATIC) {
                // stack: [..., value]  (value is 1 or 2 slots, doesn't matter)
                // Each emitted call pushes then pops exactly one slot, so
                // value is untouched at the bottom when we hit PUTSTATIC.
                emitPreHook(owner);
                // stack: [..., value]
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
