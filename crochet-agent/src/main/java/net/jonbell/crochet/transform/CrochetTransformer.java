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
        // Gap 7: JDK classes go through a minimal pipeline — FieldAccessWrapper
        // / StaticFieldRewriter / ArrayAccessWrapper emit bytecode that
        // references ArrayRegistry / CheckpointRollbackAgent before the
        // runtime is wired in during JVM boot, which crashes the instrumented
        // JDK's early startup (SystemModules$default.moduleDescriptors,
        // ModuleDescriptor$Exports.hashCode, etc.). The Gap 7 proof-of-concept
        // needs only the $$crochet surface + CRIJInstrumented interface on
        // JDK classes; user classes keep the full Gap 2/3/4 treatment.
        boolean isJdkClass = name != null
                && (name.startsWith("java/") || name.startsWith("jdk/")
                    || name.startsWith("sun/") || name.startsWith("com/sun/"));

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        ClassVisitor chain = writer;
        chain = new AnnotationStamper(Opcodes.ASM9, chain);
        chain = new LookupInjector(Opcodes.ASM9, chain);
        if (!isJdkClass) {
            chain = new FieldAccessWrapper(Opcodes.ASM9, chain);
            chain = maybeWrap(chain, "net.jonbell.crochet.transform.StaticFieldRewriter");
            chain = maybeWrap(chain, "net.jonbell.crochet.transform.ArrayAccessWrapper");
        }
        chain = new FieldAdder(Opcodes.ASM9, chain);
        // EXPAND_FRAMES: required by LocalVariablesSorter, used by visitors
        // that spill 2-slot values (long/double) via scratch locals.
        reader.accept(chain, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    /**
     * Reflectively construct a ClassVisitor subclass by name if it is on the
     * classpath. Returns the given {@code chain} unchanged if the class is
     * absent, so the baseline transform pipeline keeps working even when the
     * Gap 3/4 visitors aren't in this build.
     */
    private static ClassVisitor maybeWrap(ClassVisitor chain, String className) {
        try {
            Class<?> c = Class.forName(className, true, CrochetTransformer.class.getClassLoader());
            return (ClassVisitor) c.getDeclaredConstructor(int.class, ClassVisitor.class)
                    .newInstance(Opcodes.ASM9, chain);
        } catch (Throwable t) {
            return chain;
        }
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
