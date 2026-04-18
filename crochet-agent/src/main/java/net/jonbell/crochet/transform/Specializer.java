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

        // NESTMATE grants access to the nest host's private members; dropped
        // ClassOption.STRONG so the proxy is only reachable via live instance
        // klass pointers. The VM retains hidden classes while any live
        // instance references them, so weak-retention is the correct
        // semantic — it lets the proxy class be reclaimed after all
        // instances have been swapped back to the user class and garbage-
        // collected. A lingering STRONG binding would keep generated proxies
        // alive for the life of the parent class, bloating class-loader data
        // proportionally to the number of user classes exercised.
        return userLookup.defineHiddenClass(rewritten, true,
                        ClassOption.NESTMATE)
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
        java.lang.reflect.Method m = userClass.getDeclaredMethod("$$crochetLookup");
        // Package-private classes (e.g. java.util.HashMap$Node,
        // java.util.concurrent.ConcurrentHashMap$Node) reject reflective
        // invocation of even public static members from outside the package.
        // setAccessible bypasses the language-level access check so the
        // packed agent runtime can reach the injected lookup factory
        // regardless of the user class's declared visibility.
        m.setAccessible(true);
        return (Lookup) m.invoke(null);
    }
}
