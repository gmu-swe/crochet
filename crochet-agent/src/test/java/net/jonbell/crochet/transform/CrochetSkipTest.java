package net.jonbell.crochet.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import net.jonbell.crochet.annotation.CrochetSkip;
import net.jonbell.crochet.tests.PlainBean;
import net.jonbell.crochet.tests.SkipBean;
import net.jonbell.crochet.tests.SkipBeanGrandchild;
import net.jonbell.crochet.tests.SkipBeanSubclass;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Tests for the {@code @CrochetSkip} user-class opt-out mechanism.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Direct annotation — class annotated with {@code @CrochetSkip} is not
 *       instrumented.</li>
 *   <li>Inheritance depth 1 — unannotated subclass of annotated parent is also
 *       skipped.</li>
 *   <li>Inheritance depth 2 — grandchild (no annotation anywhere in its own
 *       chain below the grandparent) is still skipped.</li>
 *   <li>Control — unannotated class is instrumented normally.</li>
 *   <li>Hardcoded-list interaction — a name on the hardcoded skip-list is
 *       already suppressed before the annotation check fires; adding
 *       {@code @CrochetSkip} to such a class causes no interference.</li>
 *   <li>{@link CrochetTransformer#hasSkipAnnotation} returns {@code false} for
 *       a class with no annotation and no annotated ancestors.</li>
 * </ol>
 */
class CrochetSkipTest {

    // -------------------------------------------------------------------------
    // Gate 1: direct annotation
    // -------------------------------------------------------------------------

    @Test
    void directAnnotationSuppressesInstrumentation() throws IOException {
        byte[] bytes = readClassBytes(SkipBean.class.getName());
        // The transformer should return null — no instrumentation.
        assertNull(new CrochetTransformer().transform(bytes, false),
                "@CrochetSkip annotated class must not be instrumented");
    }

    @Test
    void directAnnotationHasNoSkipSyntheticMethods() throws IOException {
        byte[] bytes = readClassBytes(SkipBean.class.getName());
        // Confirm the original class bytes contain no $$crochet* methods
        // (i.e., the raw .class file was never instrumented — expected).
        assertFalse(hasCrochetMethods(bytes),
                "SkipBean fixture must not already contain $$crochet* methods");
    }

    // -------------------------------------------------------------------------
    // Gate 2: inherited annotation — depth 1
    // -------------------------------------------------------------------------

    @Test
    void subclassOfAnnotatedClassIsSkipped() throws IOException {
        byte[] bytes = readClassBytes(SkipBeanSubclass.class.getName());
        assertNull(new CrochetTransformer().transform(bytes, false),
                "Subclass of @CrochetSkip class must not be instrumented (depth 1)");
    }

    // -------------------------------------------------------------------------
    // Gate 3: inherited annotation — depth 2
    // -------------------------------------------------------------------------

    @Test
    void grandchildOfAnnotatedClassIsSkipped() throws IOException {
        byte[] bytes = readClassBytes(SkipBeanGrandchild.class.getName());
        assertNull(new CrochetTransformer().transform(bytes, false),
                "Grandchild of @CrochetSkip class must not be instrumented (depth 2)");
    }

    // -------------------------------------------------------------------------
    // Gate 4: control — unannotated class is instrumented
    // -------------------------------------------------------------------------

    @Test
    void unannotatedClassIsInstrumented() throws IOException {
        byte[] bytes = readClassBytes(PlainBean.class.getName());
        byte[] instrumented = new CrochetTransformer().transform(bytes, false);
        assertNotNull(instrumented,
                "Unannotated class must be instrumented (control case)");
        assertTrue(hasCrochetMethods(instrumented),
                "Instrumented class must contain $$crochet* methods");
    }

    // -------------------------------------------------------------------------
    // Gate 5: hardcoded skip-list interaction
    // -------------------------------------------------------------------------

    @Test
    void hardcodedSkipListStillSuppressesBeforeAnnotationCheck() throws IOException {
        // java/lang/Object is on the hardcoded list. shouldSkip fires first and
        // returns null before hasSkipAnnotation is ever called. This test
        // confirms the ordering assumption and that no NPE or other surprise
        // occurs if someone were to add @CrochetSkip to a JDK class externally.
        byte[] objectBytes = readClassBytes("java.lang.Object");
        assertNull(new CrochetTransformer().transform(objectBytes, false),
                "java/lang/Object must still be suppressed by the hardcoded list");
    }

    @Test
    void hardcodedSkipClassIsSupressedByName() {
        // shouldSkip(String) alone — the hardcoded path — must remain unaffected.
        assertTrue(CrochetTransformer.shouldSkip("java/lang/Object"),
                "shouldSkip must still return true for java/lang/Object");
        assertTrue(CrochetTransformer.shouldSkip("net/jonbell/crochet/runtime/Tag"),
                "shouldSkip must still return true for runtime packages");
        assertTrue(CrochetTransformer.shouldSkip("org/pastalab/fray/RunContext"),
                "shouldSkip must still return true for Fray classes");
    }

    // -------------------------------------------------------------------------
    // Gate 6: hasSkipAnnotation returns false for unannotated class
    // -------------------------------------------------------------------------

    @Test
    void hasSkipAnnotationFalseForUnannotatedClass() throws IOException {
        byte[] bytes = readClassBytes(PlainBean.class.getName());
        assertFalse(CrochetTransformer.hasSkipAnnotation(bytes, null),
                "hasSkipAnnotation must return false for a class with no annotation and no annotated ancestors");
    }

    @Test
    void hasSkipAnnotationTrueForDirectlyAnnotatedClass() throws IOException {
        byte[] bytes = readClassBytes(SkipBean.class.getName());
        assertTrue(CrochetTransformer.hasSkipAnnotation(bytes, null),
                "hasSkipAnnotation must return true for directly annotated class");
    }

    @Test
    void hasSkipAnnotationTrueForSubclassViaInheritance() throws IOException {
        byte[] bytes = readClassBytes(SkipBeanSubclass.class.getName());
        assertTrue(CrochetTransformer.hasSkipAnnotation(bytes, null),
                "hasSkipAnnotation must return true for subclass whose ancestor is annotated");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static byte[] readClassBytes(String fullyQualifiedName) throws IOException {
        String resource = fullyQualifiedName.replace('.', '/') + ".class";
        try (InputStream in = CrochetSkipTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("class not found on classpath: " + resource);
            }
            return in.readAllBytes();
        }
    }

    /** Returns true if the class bytes contain any method prefixed {@code $$crochet}. */
    private static boolean hasCrochetMethods(byte[] bytes) {
        CrochetMethodDetector v = new CrochetMethodDetector();
        new ClassReader(bytes).accept(v, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return v.found;
    }

    private static final class CrochetMethodDetector extends ClassVisitor {
        boolean found;

        CrochetMethodDetector() { super(Opcodes.ASM9); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (name != null && name.startsWith("$$crochet")) {
                found = true;
            }
            return null;
        }
    }
}
