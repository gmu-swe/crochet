package net.jonbell.crochet.transform;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandles.Lookup.ClassOption;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Rewrites the {@link ProxyTemplate} byte[] for a specific user class and
 * defines it as a hidden class anchored in the user class's lookup. Port of
 * the Approach B pattern established in {@code spikes/hidden-class-cp-patching/}.
 */
public final class Specializer {

    private Specializer() {}

    public static Class<?> specializeFast(byte[] templateBytes, Class<?> userClass, Lookup userLookup)
            throws Throwable {
        String userInternal = Type.getInternalName(userClass);
        String specializedInternal = userInternal + "$$crochetFast";

        byte[] rewritten = rewrite(templateBytes, userInternal, specializedInternal);

        return userLookup.defineHiddenClass(rewritten, true,
                        ClassOption.NESTMATE, ClassOption.STRONG)
                .lookupClass();
    }

    public static byte[] rewrite(byte[] templateBytes, String userInternal, String specializedInternal) {
        ClassReader cr = new ClassReader(templateBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                String newSuper = ProxyTemplate.SUPER_SENTINEL.equals(superName)
                        ? userInternal : superName;
                super.visit(version, access, specializedInternal, signature, newSuper, interfaces);
            }
        }, 0);

        return cw.toByteArray();
    }

    public static Lookup lookupFromUserClass(Class<?> userClass) throws Throwable {
        return (Lookup) userClass.getDeclaredMethod("$$crochetLookup").invoke(null);
    }
}
