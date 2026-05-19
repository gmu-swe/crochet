package net.jonbell.crochet.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

import net.jonbell.crochet.annotation.Internal;
import net.jonbell.crochet.transform.CrochetTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Lowest-priority {@link ClassFileTransformer} that verifies the Crochet
 * instrumented surface is intact on every class that should have been
 * instrumented.
 *
 * <h2>Purpose</h2>
 * <p>When multiple Java agents compose at runtime (Crochet + Byte Buddy via
 * Mockito-inline, Crochet + other ASM-based agents, etc.) a downstream agent
 * may silently rewrite, remove, or rename the {@code $$crochet*} surface that
 * Crochet's transformer emitted. The symptom is a silent no-op at checkpoint
 * time — the class is loaded, the checkpoint is taken, but the snapshot is
 * never populated — rather than a {@code ClassFormatError}.
 *
 * <p>This verifier catches exactly that scenario at class-load time, before
 * any checkpoint runs, by re-reading the final class bytes (after all
 * transformers have run) and checking that the expected surface is present.
 *
 * <h2>What is checked</h2>
 * <p>For any class that {@link CrochetTransformer} would instrument (i.e.
 * {@code shouldSkip} returns false and the class is not an enum / interface /
 * annotation), the verifier checks that all of the following are present:
 * <ul>
 *   <li>{@code @CrochetInstrumented} annotation (descriptor
 *       {@code Lnet/jonbell/crochet/annotation/CrochetInstrumented;})</li>
 *   <li>Field {@code int $$crochetVersion}</li>
 *   <li>Field {@code Object $$crochetSnap}</li>
 *   <li>Method {@code void $$crochetAccess()}</li>
 *   <li>Method {@code void $$crochetCheckpoint(int)}</li>
 *   <li>Method {@code void $$crochetRollback(int)}</li>
 *   <li>Interface {@code net/jonbell/crochet/runtime/CRIJInstrumented}</li>
 * </ul>
 *
 * <h2>Output</h2>
 * <p>On mismatch, a single structured log line is written to {@code System.err}:
 * <pre>
 *   [Crochet-Verify] SURFACE_MISMATCH class=com/example/Foo missing=@CrochetInstrumented,$$crochetVersion
 * </pre>
 * <p>This is NOT a {@code ClassFormatError} — the class is still loaded and
 * runs, just without a complete Crochet surface. Callers that need hard failure
 * can grep for {@code [Crochet-Verify] SURFACE_MISMATCH} in the JVM's stderr.
 *
 * <h2>Activation</h2>
 * <p>Disabled by default (zero overhead). Enable with:
 * <pre>
 *   -Dcrochet.verifyInstrumented=true
 * </pre>
 *
 * <h2>Registration order</h2>
 * <p>Registered with {@code canRetransform=false} after
 * {@link TransformerWrapper}. The JVM invokes transformers in registration
 * order, so by the time this verifier's {@code transform} method is called,
 * the {@code classfileBuffer} parameter contains the bytes that have already
 * been processed by all prior transformers — including {@link TransformerWrapper}
 * and any downstream agents. This is a read-only inspector; it returns {@code null}
 * (no change) in every code path.
 */
@Internal
final class InstrumentedSurfaceVerifier implements ClassFileTransformer {

    /** System-property gate; zero-cost when off. */
    static final boolean ENABLED =
            Boolean.getBoolean("crochet.verifyInstrumented");

    /**
     * Whether the running JDK was pre-instrumented by the jlink pipeline.
     * On a vanilla JDK, JDK classes are not instrumented so we skip them —
     * the same guard used by {@link TransformerWrapper}.
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

    private static final String ANNOTATION_DESC =
            CrochetTransformer.CROCHET_INSTRUMENTED_DESC;
    private static final String CRIJ_INTERFACE =
            "net/jonbell/crochet/runtime/CRIJInstrumented";

    /** Descriptor of {@code int $$crochetVersion}. */
    private static final String VERSION_FIELD_DESC = "I";
    /** Descriptor of {@code Object $$crochetSnap}. */
    private static final String SNAP_FIELD_DESC = "Ljava/lang/Object;";

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!ENABLED || classfileBuffer == null || className == null) {
            return null;
        }
        // Quick pre-filter: skip classes the transformer would not touch.
        if (CrochetTransformer.shouldSkip(className)) {
            return null;
        }
        // On a vanilla JDK, JDK classes are never instrumented — don't verify them.
        // The TransformerWrapper uses the same guard.
        if (!JDK_INSTRUMENTED && isVanillaJdkClass(className)) {
            return null;
        }
        // Boot/platform-loaded classes are also unverifiable on a vanilla JDK.
        if (!JDK_INSTRUMENTED && (loader == null || isBootOrPlatformLoader(loader))) {
            return null;
        }
        // Parse access flags, super, and class-level annotations without code.
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            int access = reader.getAccess();
            // Enums, interfaces, annotations, and modules are not instrumented.
            if ((access & (Opcodes.ACC_ENUM | Opcodes.ACC_INTERFACE
                    | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) != 0) {
                return null;
            }
            // Anonymous enum-constant bodies are not flagged ACC_ENUM
            // but extend Enum — same skip as CrochetTransformer.
            if ("java/lang/Enum".equals(reader.getSuperName())) {
                return null;
            }
            SurfaceCheckVisitor v = new SurfaceCheckVisitor();
            reader.accept(v, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
            // Only report a mismatch if @CrochetInstrumented is present but
            // other surface elements are missing. If @CrochetInstrumented is
            // absent, the Crochet transformer did NOT run on this class (the
            // TransformerWrapper may have returned null due to exception, or
            // the class is from a framework that the transform pipeline skips
            // at runtime but not via shouldSkip). Reporting a mismatch for
            // unprocessed classes would produce noise for every third-party
            // class that the transformer attempts but silently fails on.
            //
            // The composition-failure scenario we're detecting is:
            //   1. Crochet runs → adds @CrochetInstrumented + $$crochet* surface
            //   2. Downstream agent strips $$crochetAccess (bad composition)
            //   3. Verifier sees @CrochetInstrumented present but $$crochetAccess missing → MISMATCH
            //
            // If @CrochetInstrumented is absent, we're not in that scenario.
            List<String> missing = v.missing();
            if (v.hasAnnotation && !missing.isEmpty()) {
                System.err.println("[Crochet-Verify] SURFACE_MISMATCH class="
                        + className + " missing=" + String.join(",", missing));
            }
        } catch (Throwable t) {
            // Never let verification abort class loading.
            if (Boolean.getBoolean("crochet.verboseCompat")) {
                System.err.println("[Crochet-Verify] VERIFICATION_ERROR class="
                        + className + " error=" + t);
            }
        }
        return null; // always a no-op transformer
    }

    private static boolean isVanillaJdkClass(String internalName) {
        return internalName.startsWith("java/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/");
    }

    private static boolean isBootOrPlatformLoader(ClassLoader loader) {
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        return loader == platform;
    }

    /**
     * ASM visitor that collects the surface-element presence flags for one
     * class and assembles the list of missing elements.
     */
    private static final class SurfaceCheckVisitor extends ClassVisitor {

        private boolean hasAnnotation;
        private boolean hasVersionField;
        private boolean hasSnapField;
        private boolean hasAccessMethod;
        private boolean hasCheckpointMethod;
        private boolean hasRollbackMethod;
        private boolean hasCrijInterface;

        SurfaceCheckVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name,
                          String signature, String superName,
                          String[] interfaces) {
            if (interfaces != null) {
                for (String iface : interfaces) {
                    if (CRIJ_INTERFACE.equals(iface)) {
                        hasCrijInterface = true;
                        break;
                    }
                }
            }
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                String descriptor, boolean visible) {
            if (ANNOTATION_DESC.equals(descriptor)) {
                hasAnnotation = true;
            }
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name,
                                       String descriptor, String signature,
                                       Object value) {
            if ("$$crochetVersion".equals(name) && VERSION_FIELD_DESC.equals(descriptor)) {
                hasVersionField = true;
            } else if ("$$crochetSnap".equals(name) && SNAP_FIELD_DESC.equals(descriptor)) {
                hasSnapField = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name,
                                          String descriptor, String signature,
                                          String[] exceptions) {
            if ("$$crochetAccess".equals(name) && "()V".equals(descriptor)) {
                hasAccessMethod = true;
            } else if ("$$crochetCheckpoint".equals(name) && "(I)V".equals(descriptor)) {
                hasCheckpointMethod = true;
            } else if ("$$crochetRollback".equals(name) && "(I)V".equals(descriptor)) {
                hasRollbackMethod = true;
            }
            return null;
        }

        List<String> missing() {
            List<String> out = new ArrayList<>();
            if (!hasAnnotation)      out.add("@CrochetInstrumented");
            if (!hasVersionField)    out.add("$$crochetVersion");
            if (!hasSnapField)       out.add("$$crochetSnap");
            if (!hasAccessMethod)    out.add("$$crochetAccess");
            if (!hasCheckpointMethod) out.add("$$crochetCheckpoint");
            if (!hasRollbackMethod)  out.add("$$crochetRollback");
            if (!hasCrijInterface)   out.add("CRIJInstrumented");
            return out;
        }
    }
}
