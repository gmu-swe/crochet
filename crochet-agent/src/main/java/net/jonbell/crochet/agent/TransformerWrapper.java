package net.jonbell.crochet.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import net.jonbell.crochet.transform.CrochetTransformer;

final class TransformerWrapper implements ClassFileTransformer {

    private final CrochetTransformer delegate = new CrochetTransformer();

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        try {
            byte[] out = delegate.transform(classfileBuffer, false);
            if (out != null && Boolean.getBoolean("crochet.dumpClasses")) {
                dumpClass(className, out);
            }
            return out;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private static void dumpClass(String internalName, byte[] bytes) {
        try {
            java.io.File dir = new java.io.File("/tmp/crochet-dump");
            dir.mkdirs();
            String file = (internalName == null ? "unknown" : internalName.replace('/', '.')) + ".class";
            java.io.File out = new java.io.File(dir, file);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(bytes);
            }
        } catch (Exception ignored) {}
    }
}
