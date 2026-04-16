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
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor chain = writer;
        chain = new LookupInjector(Opcodes.ASM9, chain);
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
        if (internalName.equals("java/lang/Object")) {
            return true;
        }
        if (internalName.startsWith(RUNTIME_PACKAGE_PREFIX)
                || internalName.startsWith(TRANSFORM_PACKAGE_PREFIX)
                || internalName.startsWith(AGENT_PACKAGE_PREFIX)
                || internalName.startsWith(PATCH_PACKAGE_PREFIX)) {
            return true;
        }
        return false;
    }
}
