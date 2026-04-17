package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class CrochetTransformer {

    public static final String RUNTIME_PACKAGE_PREFIX = "net/jonbell/crochet/runtime/";

    public static final String TRANSFORM_PACKAGE_PREFIX = "net/jonbell/crochet/transform/";

    private static final String AGENT_PACKAGE_PREFIX = "net/jonbell/crochet/agent/";

    private static final String PATCH_PACKAGE_PREFIX = "net/jonbell/crochet/patch/";

    public byte[] transform(byte[] classFileBuffer, boolean hostedAnonymous) {
        if (classFileBuffer == null) {
            return null;
        }
        ClassReader reader = new ClassReader(classFileBuffer);
        String name = reader.getClassName();
        if (shouldSkip(name)) {
            return null;
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        ClassVisitor chain = writer;
        chain = new LookupInjector(Opcodes.ASM9, chain);
        chain = new FieldAdder(Opcodes.ASM9, chain);
        reader.accept(chain, 0);
        return writer.toByteArray();
    }

    static boolean shouldSkip(String internalName) {
        if (internalName == null) {
            return true;
        }
        if (internalName.equals("module-info") || internalName.endsWith("/module-info")) {
            return true;
        }
        // JDK classes: don't instrument until the jlink pipeline (Phase 1.2)
        // wires our runtime into java.base. V0 demo targets user classes only.
        if (internalName.startsWith("java/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/")) {
            return true;
        }
        if (internalName.startsWith(RUNTIME_PACKAGE_PREFIX)
                || internalName.startsWith(TRANSFORM_PACKAGE_PREFIX)
                || internalName.startsWith(AGENT_PACKAGE_PREFIX)
                || internalName.startsWith(PATCH_PACKAGE_PREFIX)) {
            return true;
        }
        // Galette's relocated ASM sits under our shaded package
        if (internalName.startsWith("net/jonbell/crochet/agent/shaded/")) {
            return true;
        }
        return false;
    }
}
