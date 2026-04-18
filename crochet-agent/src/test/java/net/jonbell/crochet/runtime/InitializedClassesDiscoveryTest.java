package net.jonbell.crochet.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import net.jonbell.crochet.transform.CrochetTransformer;

import org.junit.jupiter.api.Test;

/**
 * Closes the {@code ClassCoverageProbe} / {@code RootCollector} gap — verifies
 * that {@code <clinit>} registration populates
 * {@link CheckpointRollbackAgent#INITIALIZED_CLASSES} and that
 * {@code checkpointAll} discovers classes via either the
 * {@code <clinit>}-emit path or the {@link ClassMeta#of} path.
 *
 * <p>Fixtures exercise three code paths the legacy CROCHET would have missed
 * when a class's {@code <clinit>} fired through a non-hooked route:
 * <ul>
 *   <li>Fixture A: only {@code <clinit>}-triggered (the
 *       {@link CheckpointRollbackAgent#INITIALIZED_CLASSES} path).
 *   <li>Fixture B: only {@code ClassMeta.of} (the
 *       {@link CheckpointRollbackAgent#TOUCHED_CLASSES} path).
 *   <li>Fixture C: both.
 * </ul>
 *
 * <p>All three fixtures must appear in the root set that backs
 * {@code checkpointAll} after the setup sequence runs.
 */
class InitializedClassesDiscoveryTest {

    @Test
    void clinitEmissionPopulatesInitializedClasses() throws Exception {
        // Transform each fixture. Load it in an isolated ClassLoader that
        // shares the parent (the test classloader) so references to
        // CheckpointRollbackAgent resolve to this JVM's runtime class.
        // Fixtures live under net.jonbell.crochet.tests (not *.runtime) so
        // the transformer's RUNTIME_PACKAGE_PREFIX skip-list doesn't reject
        // them.
        String nameA = net.jonbell.crochet.tests.CoverageFixtureA.class.getName();
        String nameB = net.jonbell.crochet.tests.CoverageFixtureB.class.getName();
        String nameC = net.jonbell.crochet.tests.CoverageFixtureC.class.getName();

        byte[] bytesA = new CrochetTransformer().transform(readClassBytes(nameA), false);
        byte[] bytesB = new CrochetTransformer().transform(readClassBytes(nameB), false);
        byte[] bytesC = new CrochetTransformer().transform(readClassBytes(nameC), false);
        assertNotNull(bytesA);
        assertNotNull(bytesB);
        assertNotNull(bytesC);

        Map<String, byte[]> defs = new HashMap<>();
        defs.put(nameA, bytesA);
        defs.put(nameB, bytesB);
        defs.put(nameC, bytesC);
        MultiClassLoader loader = new MultiClassLoader(
                InitializedClassesDiscoveryTest.class.getClassLoader(), defs);

        // Precondition: none are in INITIALIZED_CLASSES (names unique per
        // test run; the transformed copies live in a different ClassLoader
        // than the top-level fixtures and compare by identity).
        Class<?> a = Class.forName(nameA, false, loader);
        Class<?> b = Class.forName(nameB, false, loader);
        Class<?> c = Class.forName(nameC, false, loader);
        assertFalse(CheckpointRollbackAgent.INITIALIZED_CLASSES.contains(a),
                "A should not be INITIALIZED before <clinit> fires");
        assertFalse(CheckpointRollbackAgent.INITIALIZED_CLASSES.contains(b),
                "B should not be INITIALIZED before <clinit> fires");

        // Fixture A: force <clinit> only — this must populate INITIALIZED_CLASSES.
        Class.forName(nameA, /*init=*/ true, loader);
        assertTrue(CheckpointRollbackAgent.INITIALIZED_CLASSES.contains(a),
                "Fixture A's synthesized <clinit> should have registered it");

        // Fixture B: only touch via ClassMeta.of (no <clinit>). Goes to
        // TOUCHED_CLASSES, NOT INITIALIZED_CLASSES.
        ClassMeta.of(b);
        assertTrue(CheckpointRollbackAgent.TOUCHED_CLASSES.contains(b),
                "Fixture B should land in TOUCHED_CLASSES via ClassMeta.of");
        assertFalse(CheckpointRollbackAgent.INITIALIZED_CLASSES.contains(b),
                "Fixture B's <clinit> must not have fired");

        // Fixture C: both.
        ClassMeta.of(c);
        Class.forName(nameC, /*init=*/ true, loader);
        assertTrue(CheckpointRollbackAgent.TOUCHED_CLASSES.contains(c),
                "Fixture C should land in TOUCHED_CLASSES via ClassMeta.of");
        assertTrue(CheckpointRollbackAgent.INITIALIZED_CLASSES.contains(c),
                "Fixture C's synthesized <clinit> should have registered it");
    }

    /**
     * Tests the union semantics of {@code checkpointAll}: any class present in
     * EITHER {@code TOUCHED_CLASSES} or {@code INITIALIZED_CLASSES} should be
     * visible to the root-collection pass that backs
     * {@link CheckpointRollbackAgent#checkpointAll()}. We reach into the
     * package-private sets directly to avoid requiring a full SF-helper
     * materialisation path in this unit test.
     */
    @Test
    void rootSetIsUnionOfBothSources() throws Exception {
        // Create one transformed class per path.
        String nameOnlyInit = "net.jonbell.crochet.tests.OnlyClinitFixture";
        String nameOnlyTouched = "net.jonbell.crochet.tests.OnlyTouchedFixture";

        byte[] onlyInit = transformEmptyClass(nameOnlyInit);
        byte[] onlyTouched = transformEmptyClass(nameOnlyTouched);

        Map<String, byte[]> defs = new HashMap<>();
        defs.put(nameOnlyInit, onlyInit);
        defs.put(nameOnlyTouched, onlyTouched);
        MultiClassLoader loader = new MultiClassLoader(
                InitializedClassesDiscoveryTest.class.getClassLoader(), defs);

        // Force clinit on the first.
        Class<?> classInit = Class.forName(nameOnlyInit, true, loader);
        // Touch-only on the second via ClassMeta.of.
        Class<?> classTouched = Class.forName(nameOnlyTouched, false, loader);
        ClassMeta.of(classTouched);

        assertTrue(CheckpointRollbackAgent.INITIALIZED_CLASSES.contains(classInit));
        assertTrue(CheckpointRollbackAgent.TOUCHED_CLASSES.contains(classTouched));
        assertFalse(CheckpointRollbackAgent.TOUCHED_CLASSES.contains(classInit),
                "classInit should not yet be in TOUCHED_CLASSES (no ClassMeta.of call)");
        assertFalse(CheckpointRollbackAgent.INITIALIZED_CLASSES.contains(classTouched),
                "classTouched must not have had <clinit> fire");

        // The union must include both.
        java.util.Set<Class<?>> union = new java.util.HashSet<>();
        union.addAll(CheckpointRollbackAgent.TOUCHED_CLASSES);
        union.addAll(CheckpointRollbackAgent.INITIALIZED_CLASSES);
        assertTrue(union.contains(classInit),
                "root set union must include clinit-only fixture");
        assertTrue(union.contains(classTouched),
                "root set union must include ClassMeta.of-only fixture");
    }

    /**
     * Emits a minimal class file named {@code internalName}, runs it through
     * {@link CrochetTransformer}, and returns the transformed bytes. Used for
     * tests that need on-the-fly fixture classes without a corresponding
     * top-level {@code .java} source.
     */
    private static byte[] transformEmptyClass(String fullyQualifiedName) {
        String internal = fullyQualifiedName.replace('.', '/');
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
                internal, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        byte[] raw = cw.toByteArray();
        byte[] out = new CrochetTransformer().transform(raw, false);
        assertNotNull(out, "transformer must handle empty fixture");
        return out;
    }

    private static byte[] readClassBytes(String fullyQualifiedName) throws IOException {
        String resource = fullyQualifiedName.replace('.', '/') + ".class";
        try (InputStream in =
                     InitializedClassesDiscoveryTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("class not found on classpath: " + resource);
            }
            return in.readAllBytes();
        }
    }

    /** Defines multiple classes from a name→bytes map; parent-first delegation otherwise. */
    private static final class MultiClassLoader extends ClassLoader {
        private final Map<String, byte[]> defs;

        MultiClassLoader(ClassLoader parent, Map<String, byte[]> defs) {
            super(parent);
            this.defs = defs;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = defs.get(name);
            if (bytes == null) {
                return super.findClass(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (defs.containsKey(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = findClass(name);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }

    /** Sanity: a synthesized-only {@code <clinit>} shouldn't require ClassMeta.of to register. */
    @Test
    void synthesizedClinitRegistersWithoutClassMetaOf() throws Exception {
        String name = "net.jonbell.crochet.tests.SynthClinitOnlyFixture";
        byte[] bytes = transformEmptyClass(name);
        MultiClassLoader loader = new MultiClassLoader(
                InitializedClassesDiscoveryTest.class.getClassLoader(),
                Map.of(name, bytes));
        Class<?> c = Class.forName(name, true, loader);
        assertTrue(CheckpointRollbackAgent.INITIALIZED_CLASSES.contains(c),
                "synthesized <clinit> must register the class");
        // No ClassMeta.of invocation; ensure it is NOT in TOUCHED_CLASSES.
        assertFalse(CheckpointRollbackAgent.TOUCHED_CLASSES.contains(c),
                "TOUCHED_CLASSES should remain untouched without ClassMeta.of call");
        // Union must contain it.
        assertEquals(1L,
                java.util.stream.Stream.of(c).filter(CheckpointRollbackAgent.INITIALIZED_CLASSES::contains).count());
    }
}
