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
 * <p>{@code <clinit>} takes the full skip. {@code <init>} forwards pre-super
 * arraycopies unchanged via {@link CtorAwareMv}: the destination array can't
 * be a field of {@code this} before super has initialised, so redirection
 * would be a no-op (no object is registered); forwarding is cheaper. Post-
 * super, we redirect as usual.
 */
public final class ArrayCopyInterceptor extends ClassVisitor {

    private static final String SYSTEM_INTERNAL = "java/lang/System";
    private static final String ARRAYCOPY_DESC = "(Ljava/lang/Object;ILjava/lang/Object;II)V";
    /**
     * Bootstrap-safe forwarder. See {@code RuntimeReady} javadoc — the
     * forwarder still performs the raw {@link System#arraycopy} before
     * the runtime is ready (bulk-copy semantics must be preserved
     * during JVM startup), it just skips the registry tracking.
     */
    private static final String AGENT_INTERNAL = "net/jonbell/crochet/runtime/RuntimeReady";
    private static final String INTERCEPT_NAME = "interceptedArraycopy";

    private String className;
    private String superName;

    public ArrayCopyInterceptor(int api, ClassVisitor delegate) {
        super(api, delegate);
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
        return new RedirectMV(api, base, className, superName, isCtor);
    }

    private static final class RedirectMV extends CtorAwareMv {
        RedirectMV(int api, MethodVisitor delegate, String owner, String superName, boolean isCtor) {
            super(api, delegate, owner, superName, isCtor);
        }

        @Override
        protected void visitMethodInsnPostSuper(int opcode, String mOwner, String name,
                                                String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC
                    && SYSTEM_INTERNAL.equals(mOwner)
                    && "arraycopy".equals(name)
                    && ARRAYCOPY_DESC.equals(descriptor)) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                        INTERCEPT_NAME, ARRAYCOPY_DESC, false);
                return;
            }
            super.visitMethodInsnPostSuper(opcode, mOwner, name, descriptor, isInterface);
        }
    }
}
