package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Injects {@code public static MethodHandles.Lookup $$crochetLookup()} into every
 * instrumented class. The method returns a full-power Lookup anchored in the user
 * class's package so {@code defineHiddenClass} can materialize a specialized proxy
 * that extends the user class without needing --add-opens at the module layer.
 *
 * <p>Confirmed approach in the spike at spikes/hidden-class-cp-patching/.
 */
final class LookupInjector extends ClassVisitor {

    static final String LOOKUP_METHOD_NAME = "$$crochetLookup";

    static final String LOOKUP_METHOD_DESCRIPTOR = "()Ljava/lang/invoke/MethodHandles$Lookup;";

    private boolean isInterface;

    private boolean alreadyPresent;

    LookupInjector(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (LOOKUP_METHOD_NAME.equals(name) && LOOKUP_METHOD_DESCRIPTOR.equals(descriptor)) {
            alreadyPresent = true;
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    public void visitEnd() {
        if (!alreadyPresent && !isInterface) {
            emitLookupMethod();
        }
        super.visitEnd();
    }

    private void emitLookupMethod() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                LOOKUP_METHOD_NAME,
                LOOKUP_METHOD_DESCRIPTOR,
                null,
                null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;",
                false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
    }
}
