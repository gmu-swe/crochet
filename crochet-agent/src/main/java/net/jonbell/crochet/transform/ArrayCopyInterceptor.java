package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites {@code System.arraycopy(src, srcPos, dst, dstPos, length)} calls to
 * {@code CheckpointRollbackAgent.interceptedArraycopy(...)} so a pre-store
 * snapshot is taken on the destination array if it's registered for
 * checkpoint. Without this, native bulk copies bypass the per-slot
 * xASTORE pre-hook installed by {@link ArrayAccessWrapper} and the
 * destination's snapshot is stale after a System.arraycopy write.
 *
 * <p>Same method descriptor {@code (Object,I,Object,I,I)V} so no stack
 * reshape is needed — we just redirect the INVOKESTATIC target.
 *
 * <p>Skips {@code $$crochet*} methods and initializers, matching the
 * FieldAccessWrapper skip policy.
 */
public final class ArrayCopyInterceptor extends ClassVisitor {

    private static final String SYSTEM_INTERNAL = "java/lang/System";
    private static final String ARRAYCOPY_DESC = "(Ljava/lang/Object;ILjava/lang/Object;II)V";
    private static final String AGENT_INTERNAL = "net/jonbell/crochet/runtime/CheckpointRollbackAgent";
    private static final String INTERCEPT_NAME = "interceptedArraycopy";

    public ArrayCopyInterceptor(int api, ClassVisitor delegate) {
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
        return new RedirectMV(api, base);
    }

    private static final class RedirectMV extends MethodVisitor {
        RedirectMV(int api, MethodVisitor delegate) {
            super(api, delegate);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC
                    && SYSTEM_INTERNAL.equals(owner)
                    && "arraycopy".equals(name)
                    && ARRAYCOPY_DESC.equals(descriptor)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                        INTERCEPT_NAME, ARRAYCOPY_DESC, false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
