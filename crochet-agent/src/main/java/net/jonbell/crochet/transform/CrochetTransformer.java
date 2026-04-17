package net.jonbell.crochet.transform;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class CrochetTransformer {

    public static final String RUNTIME_PACKAGE_PREFIX = "net/jonbell/crochet/runtime/";

    public static final String TRANSFORM_PACKAGE_PREFIX = "net/jonbell/crochet/transform/";

    private static final String AGENT_PACKAGE_PREFIX = "net/jonbell/crochet/agent/";

    private static final String PATCH_PACKAGE_PREFIX = "net/jonbell/crochet/patch/";

    private static final String ANNOTATION_PACKAGE_PREFIX = "net/jonbell/crochet/annotation/";

    /** Descriptor of the marker annotation added to every transformed class. */
    public static final String CROCHET_INSTRUMENTED_DESC =
            "Lnet/jonbell/crochet/annotation/CrochetInstrumented;";

    public byte[] transform(byte[] classFileBuffer, boolean hostedAnonymous) {
        return transform(classFileBuffer, hostedAnonymous, null);
    }

    public byte[] transform(byte[] classFileBuffer, boolean hostedAnonymous, ClassLoader loader) {
        if (classFileBuffer == null) {
            return null;
        }
        ClassReader reader = new ClassReader(classFileBuffer);
        String name = reader.getClassName();
        if (shouldSkip(name)) {
            return null;
        }
        // Enum classes, interfaces, annotations, and modules reject the
        // instance fields/methods we want to inject.
        int access = reader.getAccess();
        if ((access & (Opcodes.ACC_ENUM | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION
                | Opcodes.ACC_MODULE)) != 0) {
            return null;
        }
        // Anonymous enum-constant bodies are not flagged ACC_ENUM but extend Enum.
        String superName = reader.getSuperName();
        if ("java/lang/Enum".equals(superName)) {
            return null;
        }
        if (alreadyInstrumented(reader)) {
            // Either the jlink pipeline already baked instrumentation into
            // this class, or a prior transformer pass did. Returning null
            // preserves the existing bytes.
            return null;
        }
        // JDK classes go through a minimal pipeline — FieldAccessWrapper /
        // StaticFieldRewriter / ArrayAccessWrapper emit bytecode that
        // references ArrayRegistry / CheckpointRollbackAgent before the
        // runtime is wired in during JVM boot, which crashes the instrumented
        // JDK's early startup. The Gap 7 proof-of-concept needs only the
        // $$crochet surface + CRIJInstrumented interface on JDK classes;
        // user classes keep the full Gap 2/3/4 treatment.
        boolean isJdkClass = name != null
                && (name.startsWith("java/") || name.startsWith("jdk/")
                    || name.startsWith("sun/") || name.startsWith("com/sun/"));
        int classFileVersion = readMajorVersion(classFileBuffer);
        // Pre-Java-6 class files (major < 50) may use JSR/RET subroutines for
        // try/finally. COMPUTE_FRAMES can't process those directly, but
        // JSRInlinerAdapter rewrites them into straight-line control flow.
        // Apply only where needed — on modern bytecode the adapter still
        // buffers into a MethodNode, which is pure overhead.
        boolean needsJsrInlining = classFileVersion < Opcodes.V1_6;

        ClassWriter writer = new SafeClassWriter(reader,
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES, loader);
        // Chain (bottom → top). A single SharedLocalsProvider owns the lone
        // LocalVariablesSorter for the whole chain; every wrapper above it
        // that needs scratch locals delegates via SharedLocalsProvider.newLocal
        // instead of instantiating its own LVS. Stacking multiple LVS
        // instances produced cumulative local-index rewrites that broke
        // COMPUTE_FRAMES on large methods.
        ClassVisitor chain = writer;
        chain = new AnnotationStamper(Opcodes.ASM9, chain);
        chain = new LookupInjector(Opcodes.ASM9, chain);
        chain = new FieldAdder(Opcodes.ASM9, chain);
        if (!isJdkClass) {
            SharedLocalsProvider locals = new SharedLocalsProvider(Opcodes.ASM9, chain);
            chain = locals;
            chain = new ArrayAccessWrapper(Opcodes.ASM9, chain, locals);
            chain = new StaticFieldRewriter(Opcodes.ASM9, chain);
            chain = new ArrayCopyInterceptor(Opcodes.ASM9, chain);
            chain = new FieldAccessWrapper(Opcodes.ASM9, chain, locals);
        }
        if (needsJsrInlining) {
            chain = new JsrInliner(Opcodes.ASM9, chain);
        }
        // EXPAND_FRAMES: required by LocalVariablesSorter, used by any
        // visitor that spills 2-slot values (long/double) via scratch locals.
        reader.accept(chain, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    /**
     * ClassWriter that never touches Class.forName during frame computation.
     *
     * <p>Stock ASM's {@code ClassWriter.getCommonSuperClass} resolves type
     * names to {@link Class} objects to compute the common superclass of two
     * types being merged. This requires the class loader that instantiated
     * ClassWriter to be able to resolve arbitrary types — which it can't when
     * instrumentation runs inside a ClassFileTransformer (the agent's
     * classloader has no visibility into the benchmark's classes). The failure
     * surfaces as {@code TypeNotPresentException} wrapping a CNFE on an
     * internal-name-form class name (seen on avrora, fop, pmd, sunflow).
     *
     * <p>The safe fallback is {@code java/lang/Object} — always a valid
     * common superclass. Frame computation stays correct but less precise;
     * the verifier accepts the looser (Object-typed) frame entries.
     */
    private static final class SafeClassWriter extends ClassWriter {
        private final ClassLoader loader;

        SafeClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = loader;
        }

        /**
         * Computes common superclass without invoking {@link Class#forName},
         * which can recursively trigger class loading while a transformer is
         * in flight (classloader deadlock / incorrect caching). We walk
         * superchains by reading the target class files directly via the
         * resource stream and an on-the-fly {@link ClassReader}.
         */
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) {
                return type1;
            }
            if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
                return "java/lang/Object";
            }
            java.util.Set<String> chain1 = superChain(type1);
            if (chain1.contains(type2)) {
                return type2;
            }
            java.util.Set<String> chain2 = superChain(type2);
            if (chain2.contains(type1)) {
                return type1;
            }
            for (String c : chain1) {
                if (chain2.contains(c)) {
                    return c;
                }
            }
            return "java/lang/Object";
        }

        private java.util.Set<String> superChain(String type) {
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            String c = type;
            while (c != null && out.add(c)) {
                c = superOf(c);
            }
            return out;
        }

        private String superOf(String type) {
            ClassLoader effective = loader != null ? loader
                    : SafeClassWriter.class.getClassLoader();
            // Walk the loader chain so user-jar classes and JDK classes both
            // resolve via their resource path.
            for (ClassLoader l = effective; l != null; l = l.getParent()) {
                try (java.io.InputStream in = l.getResourceAsStream(type + ".class")) {
                    if (in != null) {
                        return new ClassReader(in).getSuperName();
                    }
                } catch (java.io.IOException ignored) {
                }
            }
            try (java.io.InputStream in = ClassLoader.getSystemResourceAsStream(type + ".class")) {
                if (in != null) {
                    return new ClassReader(in).getSuperName();
                }
            } catch (java.io.IOException ignored) {
            }
            return null;
        }
    }

    /** Reads the major version field (bytes 6-7) from a class-file buffer. */
    private static int readMajorVersion(byte[] buf) {
        if (buf == null || buf.length < 8) {
            return 0;
        }
        return ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
    }

    static boolean shouldSkip(String internalName) {
        if (internalName == null) {
            return true;
        }
        // module-info cannot take injected members.
        if (internalName.equals("module-info") || internalName.endsWith("/module-info")) {
            return true;
        }
        // Object is intentionally out of V1 scope: injecting a field on
        // java/lang/Object changes every object's layout and blows up the
        // JIT's fast-path inlining. Galette rewrites Object via
        // OffsetCacheAdder; we'll address it in V2.
        if (internalName.equals("java/lang/Object")) {
            return true;
        }
        // Our own runtime/transform/agent/patch/annotation code must never
        // recurse — the instrumentation chain uses these classes directly.
        if (internalName.startsWith(RUNTIME_PACKAGE_PREFIX)
                || internalName.startsWith(TRANSFORM_PACKAGE_PREFIX)
                || internalName.startsWith(AGENT_PACKAGE_PREFIX)
                || internalName.startsWith(PATCH_PACKAGE_PREFIX)
                || internalName.startsWith(ANNOTATION_PACKAGE_PREFIX)) {
            return true;
        }
        // Shaded ASM under the agent's own relocated package.
        if (internalName.startsWith("net/jonbell/crochet/agent/shaded/")) {
            return true;
        }
        // crochet-instrument's own classes (jlink plugins, runtime support
        // for the instrument process itself) and its shaded ASM+JaCoCo.
        if (internalName.startsWith("net/jonbell/crochet/instrument/")) {
            return true;
        }
        // JVM-fabricated classes: lambdas, proxies, reflection-generated
        // accessors. These have no ProtectionDomain; they're built after the
        // jlink pass, so they can never carry the @CrochetInstrumented marker
        // and can't be safely transformed at runtime (no stable class name).
        if (internalName.contains("$$Lambda") || internalName.contains("/$Proxy")) {
            return true;
        }
        // Agent-synthesized hidden proxy classes (Counter$$crochetFast,
        // Counter$$crochetSFHelper, ...). The Specializer defines these via
        // Lookup#defineHiddenClass; the JVM appends "/0x..." before passing
        // them through ClassFileTransformers.
        if (internalName.contains("$$crochet")) {
            return true;
        }
        // Jython compiles .py files to JVM classes named "<module>$py" at
        // runtime. Their methods return PyObject-family types whose super
        // chain SafeClassWriter can't resolve via resource lookup (the types
        // live in the Python script's classloader), so frame computation
        // widens ARETURN targets to java/lang/Object and the verifier rejects
        // them with VerifyError.
        if (internalName.endsWith("$py")) {
            return true;
        }
        // ByteBuddy auxiliary classes (name contains "$ByteBuddy$") are
        // generated at runtime and frequently inherit from already-instrumented
        // user classes. We'd re-emit the $$crochet* methods they inherited,
        // producing ClassFormatError: Duplicate method.
        if (internalName.contains("$ByteBuddy$")) {
            return true;
        }
        // Hibernate runtime proxies (e.g. Pet$HibernateProxy$FyMglsPZ) extend
        // instrumented entity classes and inherit our $$crochetCopyFieldsTo;
        // instrumenting the subclass re-emits the method and the class loader
        // rejects the duplicate.
        if (internalName.contains("$HibernateProxy$")) {
            return true;
        }
        // JFR validates that every event class has a native mirror matching
        // the declared instance-field list exactly. Adding our $$crochet*
        // fields to any class the JFR runtime touches — jdk.jfr.Event itself,
        // its jdk.jfr.events.* subclasses, the jdk.internal.event.* family
        // (ThreadSleepEvent etc.), or the jdk.jfr.consumer.* readers — makes
        // JFR abort at VM startup with "Found additional fields in mirror
        // class". We broadly skip both JFR trees; these are event-recording
        // classes with no interesting mutable state for our purposes anyway.
        if (internalName.startsWith("jdk/internal/event/")
                || internalName.startsWith("jdk/jfr/")) {
            return true;
        }
        return false;
    }

    /**
     * Cheap pre-scan that returns {@code true} iff the class file already
     * carries {@code @CrochetInstrumented}. Skips code, debug, and frame data;
     * only the header and attribute table are read.
     */
    private static boolean alreadyInstrumented(ClassReader reader) {
        AnnotationPresenceVisitor v = new AnnotationPresenceVisitor();
        reader.accept(v, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return v.found;
    }

    private static final class AnnotationPresenceVisitor extends ClassVisitor {
        boolean found;

        AnnotationPresenceVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (CROCHET_INSTRUMENTED_DESC.equals(descriptor)) {
                found = true;
            }
            return null;
        }
    }

}
