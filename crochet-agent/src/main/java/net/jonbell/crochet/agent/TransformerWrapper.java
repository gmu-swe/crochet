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
        // Boot-loaded classes can't resolve CRIJInstrumented from the agent
        // classloader, so instrumenting them produces NoClassDefFoundError on
        // first access. javax.xml.bind, org.w3c.dom, etc. live here via
        // platform modules.
        if (!JDK_INSTRUMENTED && (loader == null || isVanillaJdkClass(className)
                || isPlatformLoader(loader))) {
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

    /**
     * Platform loader owns JDK modules that aren't in the base image — e.g.
     * {@code java.xml} (which holds javax.xml.parsers.DocumentBuilderFactory)
     * and {@code jdk.crypto.ec}. These classes are subject to the same agent-
     * classloader isolation as boot-loaded classes, so instrumenting them
     * emits bytecode references to {@code net.jonbell.crochet.runtime.*} that
     * their loader can't resolve at link time.
     */
    private static boolean isPlatformLoader(ClassLoader loader) {
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        for (ClassLoader l = loader; l != null; l = l.getParent()) {
            if (l == platform) {
                // Only treat as platform-owned if loader itself IS the platform
                // loader or one of its parents — app classloader has platform
                // as parent but owns user code that can link to the agent.
                return loader == platform;
            }
        }
        return false;
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
