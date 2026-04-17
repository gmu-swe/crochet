package net.jonbell.crochet.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import net.jonbell.crochet.transform.CrochetTransformer;

final class TransformerWrapper implements ClassFileTransformer {

    private final CrochetTransformer delegate = new CrochetTransformer();

    /**
     * True iff the JDK itself was pre-instrumented by the jlink pipeline.
     * Detected by probing whether {@code java.util.HashMap} implements
     * {@code net.jonbell.crochet.runtime.CRIJInstrumented} — the agent emits
     * that interface on every transformed class.
     *
     * <p>On a vanilla JDK, HashMap is not an instance of CRIJInstrumented, so
     * we skip all JCL prefixes at runtime or the JVM bootstrap breaks (we'd
     * try to rewrite LauncherHelper, Shutdown, and friends before the
     * transformer itself is available).
     */
    private static final boolean JDK_INSTRUMENTED = detectInstrumentedJdk();

    private static boolean detectInstrumentedJdk() {
        try {
            Class<?> marker = Class.forName("net.jonbell.crochet.runtime.CRIJInstrumented");
            return marker.isAssignableFrom(java.util.HashMap.class);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        // Runtime-only safeguard: on a vanilla JDK, refuse to transform JCL
        // classes. These arrive before the runtime is ready and rewriting
        // them breaks the JVM bootstrap. On an instrumented JDK, the pre-
        // baked classes carry @CrochetInstrumented so the transformer's
        // annotation pre-scan would return null anyway — this filter is
        // then redundant but cheap.
        if (!JDK_INSTRUMENTED && isVanillaJdkClass(className)) {
            return null;
        }
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

    private static boolean isVanillaJdkClass(String internalName) {
        if (internalName == null) {
            return false;
        }
        return internalName.startsWith("java/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/");
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
