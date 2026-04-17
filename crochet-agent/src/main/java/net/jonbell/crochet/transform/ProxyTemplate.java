package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits the Fast-state proxy template byte[]. This template is later
 * {@link Specializer specialized} per user class: {@code super_class} is
 * rewritten from {@link #SUPER_SENTINEL} to the user's internal name, and
 * {@code this_class} is renamed to a unique specialized name.
 *
 * <p>The template is intentionally tiny — one overridden method,
 * {@code $$crochetAccess()}, that unconditionally delegates to
 * {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#fastAccess}.
 * All state-machine intelligence lives in that static method on the runtime
 * side, which keeps the bytecode generator simple and the state logic in
 * readable Java.
 */
public final class ProxyTemplate {

    public static final String SUPER_SENTINEL = "net/jonbell/crochet/transform/ProxyTemplate$$Sentinel";
    public static final String THIS_SENTINEL  = "net/jonbell/crochet/transform/ProxyTemplate$$Template";

    static final String CRIJ_FAST_INTERNAL = "net/jonbell/crochet/runtime/CRIJFast";
    static final String AGENT_INTERNAL     = "net/jonbell/crochet/runtime/CheckpointRollbackAgent";
    static final String INSTRUMENTED_INTERNAL = "net/jonbell/crochet/runtime/CRIJInstrumented";

    private ProxyTemplate() {}

    public static byte[] emit() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        // V55 = class file version 55 = Java 11; required for hidden classes
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                THIS_SENTINEL, null, SUPER_SENTINEL,
                new String[] { CRIJ_FAST_INTERNAL });

        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetAccess", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "fastAccess",
                "(L" + INSTRUMENTED_INTERNAL + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
