package net.jonbell.crochet.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import net.jonbell.crochet.runtime.CRIJInstrumented;
import net.jonbell.crochet.runtime.CheckpointRollbackAgent;
import net.jonbell.crochet.runtime.RollbackException;

/**
 * End-to-end tests for the {@code @CrochetEager} / {@code -Dcrochet.eagerClasses}
 * opt-in. Runs the full transformer chain on the fixture classes, loads the
 * result via an in-memory loader that delegates to the agent's own loader for
 * {@code net.jonbell.crochet.*} classes, and exercises
 * {@link CheckpointRollbackAgent#checkpoint(Object)} /
 * {@link CheckpointRollbackAgent#rollback(Object, int)} on the loaded instance.
 *
 * <p>The load path is load-bearing: the transformed class declares
 * {@code implements CRIJInstrumented}, so the loader must share the runtime
 * package with the agent (delegation parent). The test uses the fixture
 * package's classloader as the parent so runtime types resolve and the
 * loaded class is assignable to {@link CRIJInstrumented}.
 */
class EagerCheckpointTest {

    @Test
    void annotatedClassCheckpointMutateRollbackRestoresState() throws Exception {
        Class<?> loaded = instrumentAndLoad("net.jonbell.crochet.tests.EagerBean");

        Object bean = loaded.getConstructor(int.class, String.class).newInstance(7, "init");
        Field fx = loaded.getDeclaredField("x");
        Field fLabel = loaded.getDeclaredField("label");

        assertEquals(7, fx.getInt(bean));
        assertEquals("init", fLabel.get(bean));

        int v = CheckpointRollbackAgent.checkpoint(bean);
        assertTrue(v > 0, "checkpoint must return a positive version");

        fx.setInt(bean, 42);
        fLabel.set(bean, "mutated");

        CheckpointRollbackAgent.rollback(bean, v);

        assertEquals(7, fx.getInt(bean));
        assertEquals("init", fLabel.get(bean));
    }

    @Test
    void eagerCheckpointDoesNotSwapKlass() throws Exception {
        Class<?> loaded = instrumentAndLoad("net.jonbell.crochet.tests.EagerBean");
        Object bean = loaded.getConstructor(int.class, String.class).newInstance(1, "a");

        CheckpointRollbackAgent.checkpoint(bean);

        // Core eager invariant: klass remains the original user class, so any
        // identity-sensitive downstream code (instanceof, getClass()) stays
        // stable across checkpoints. This is the whole point of the opt-in.
        assertSame(loaded, bean.getClass(),
                "eager checkpoint must leave obj.getClass() unchanged");
    }

    @Test
    void systemPropertyOptInTreatsPlainBeanAsEager() throws Exception {
        // Inject the opt-in BEFORE loading any code that parses it. The
        // transformer reads the property at FieldAdder class init (first
        // access). Since this test class only triggers FieldAdder via
        // instrumentAndLoad below, setting the property here first is
        // effective — tests run in isolated JVMs with surefire's default
        // forkCount=1.
        System.setProperty("crochet.eagerClasses", "net.jonbell.crochet.tests.PlainBean");

        Class<?> loaded = instrumentAndLoad("net.jonbell.crochet.tests.PlainBean");

        Object bean = loaded.getConstructor(int.class, String.class).newInstance(5, "s");
        Field fx = loaded.getDeclaredField("x");
        Field fLabel = loaded.getDeclaredField("label");

        int v = CheckpointRollbackAgent.checkpoint(bean);
        fx.setInt(bean, 99);
        fLabel.set(bean, "mut");
        CheckpointRollbackAgent.rollback(bean, v);

        assertEquals(5, fx.getInt(bean));
        assertEquals("s", fLabel.get(bean));
        // And no klass swap: the list is authoritative at transform time.
        assertSame(loaded, bean.getClass(),
                "eagerClasses list must suppress the Fast-proxy swap");
        // Facade lookup should also report eager.
        assertTrue(CheckpointRollbackAgent.isEagerClass(loaded));
    }

    @Test
    void isEagerClassReflectsAnnotationAndList() throws Exception {
        // Annotation-only opt-in
        Class<?> loaded = instrumentAndLoad("net.jonbell.crochet.tests.EagerBean");
        assertTrue(CheckpointRollbackAgent.isEagerClass(loaded),
                "@CrochetEager class must report isEagerClass=true");

        // Non-annotated, non-listed: NOT eager. Need to instrument a class
        // NOT in the opt-in list (set by the other test, which runs in the
        // same JVM). Use the Sample fixture (no annotation, no opt-in).
        Class<?> sample = instrumentAndLoad("net.jonbell.crochet.tests.Sample");
        // Sample must not accidentally be in the opt-in list
        String list = System.getProperty("crochet.eagerClasses", "");
        assertFalse(list.contains("Sample"),
                "fixture assumption: Sample must not be on the opt-in list");
        assertFalse(CheckpointRollbackAgent.isEagerClass(sample),
                "non-annotated non-listed class must report isEagerClass=false");
    }

    @Test
    void gap8ExceptionSafetyZeroesVersionOnAllocateShadowFailure() throws Exception {
        // Induce allocateShadow failure by constructing an abstract subclass:
        // Unsafe.allocateInstance throws InstantiationException on abstract
        // types, which the runtime rethrows as IllegalStateException. That
        // exception propagates up through the eager body's try block, hits
        // the Gap-8 handler, zeroes the version, and rethrows as a poisoned
        // RollbackException.
        Class<?> loaded = instrumentAndLoad("net.jonbell.crochet.tests.EagerBean");
        Object bean = loaded.getConstructor(int.class, String.class).newInstance(1, "a");

        // Swap the class's ClassMeta userBinding to an abstract class so
        // allocateShadow throws. We use a bare approach: pass a non-annotated
        // abstract test class to our AbstractPoison (defined below) via the
        // specific mechanism — here we directly invoke the body with a
        // clogged ClassMeta. Simpler: use reflection to force $$crochetSnap's
        // copyFieldsTo to throw, since FieldAdder wraps the whole body.
        //
        // Easiest: patch the EagerBean class's copyFieldsTo to throw via a
        // subclass that overrides (but we can't subclass here). Instead,
        // hijack the version CAS to induce an inconsistent view isn't easy
        // either. The cleanest test is a subclass fixture whose allocateShadow
        // genuinely fails.
        //
        // Strategy: instrument an abstract fixture, then call the body with
        // a concrete child instance. The emitted body uses Ldc of the class
        // internal name (concrete), so we must instrument the CONCRETE class
        // and then swap ClassMeta.userBinding's class to abstract. Too
        // invasive.
        //
        // Simpler strategy: rely on the copyFieldsTo dispatch throwing. Make
        // a fixture that declares a transient field with a corrupt bytecode
        // path — no, that's even more invasive.
        //
        // Cleanest strategy: have the test directly invoke the emitted
        // $$crochetCheckpoint with a version number, and use a fixture whose
        // static initializer or field reference forces allocateShadow to
        // throw. Since allocateShadow is static and takes the class, we can
        // monkey-patch using a test-only variant: register the fixture in
        // the eagerClasses list and let CheckpointRollbackAgent.allocateShadow
        // throw.
        //
        // We defer that to a positive-paranoid path: call $$crochetCheckpoint
        // directly with a bogus sentinel that causes a throw. Easier yet:
        // call rollback when snap is null — but that's a no-op, not a throw.
        //
        // Test the positive-path Gap-8 contract by transforming an abstract
        // subclass that fails to allocate shadow because the class loader
        // can't construct it. Since we transform a CONCRETE fixture
        // (EagerBean), we need the CROCHET-specific injection point.
        //
        // Let's take a pragmatic approach: run the checkpoint under a
        // replaced Thread context classloader that can't resolve ClassMeta,
        // making the VarHandle lookup throw. But the emitted body calls
        // versionVolatileGet first, which would throw before reaching the
        // try block — and that IS the Gap-8 contract: any pre-try throw is
        // a bug, the try covers only the body.
        //
        // Final approach: use a custom CRIJInstrumented implementation that
        // has the eager body but whose $$crochetCopyFieldsTo throws. Since
        // the emit is parametric on className, we generate a small adapter
        // directly: the emitted body calls this.$$crochetCopyFieldsTo via
        // INVOKEVIRTUAL — so if we override $$crochetCopyFieldsTo in a
        // subclass to throw, the eager checkpoint hits the try/catch.
        //
        // But we can't subclass EagerBean and still have it hit the
        // $$crochetCheckpoint we emitted on EagerBean, because the emitted
        // method is on EagerBean itself and the virtual dispatch to
        // copyFieldsTo hits the SUBCLASS override. So: subclass EagerBean,
        // override $$crochetCopyFieldsTo to throw, instantiate the
        // subclass, checkpoint it. The eager $$crochetCheckpoint on
        // EagerBean will virtual-dispatch to the override and throw.
        //
        // We emit the subclass via ASM in this test because the runtime
        // subclass must still implement CRIJInstrumented (inherited from
        // EagerBean).

        Class<?> thrower = new ThrowerLoader(loaded.getClassLoader(), loaded)
                .defineThrower();
        Object child = thrower.getConstructor(int.class, String.class)
                .newInstance(1, "a");

        try {
            CheckpointRollbackAgent.checkpoint(child);
            fail("expected RollbackException");
        } catch (RollbackException re) {
            assertEquals(RollbackException.POISON_VERSION, re.version,
                    "Gap-8: throw from copyFieldsTo must produce a poisoned RollbackException");
        }

        // After the failed checkpoint, version must read 0 (clear).
        // $$crochetGetVersion is emitted as public-synthetic on EagerBean.
        int ver = (int) loaded.getMethod("$$crochetGetVersion").invoke(child);
        assertEquals(0, ver,
                "Gap-8: version must be zeroed after exception in eager body");
    }

    // ---------------------------------------------------------------------
    // Instrumentation + load helpers
    // ---------------------------------------------------------------------

    private static Class<?> instrumentAndLoad(String fqn) throws IOException, ClassNotFoundException {
        byte[] original = readClassBytes(fqn);
        byte[] instrumented = new CrochetTransformer().transform(original, false);
        assertNotNull(instrumented, "transformer must produce output for " + fqn);
        return new FixtureLoader(EagerCheckpointTest.class.getClassLoader(), fqn, instrumented)
                .loadClass(fqn);
    }

    private static byte[] readClassBytes(String fqn) throws IOException {
        String resource = fqn.replace('.', '/') + ".class";
        try (InputStream in = EagerCheckpointTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("class not found on classpath: " + resource);
            }
            return in.readAllBytes();
        }
    }

    /** Loader that defines a single fixture class and delegates the rest. */
    private static final class FixtureLoader extends ClassLoader {
        private final String fqn;
        private final byte[] bytes;

        FixtureLoader(ClassLoader parent, String fqn, byte[] bytes) {
            super(parent);
            this.fqn = fqn;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String n) throws ClassNotFoundException {
            if (n.equals(fqn)) {
                return defineClass(n, bytes, 0, bytes.length);
            }
            return super.findClass(n);
        }

        @Override
        public Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
            if (n.equals(fqn)) {
                Class<?> c = findLoadedClass(n);
                if (c == null) {
                    c = findClass(n);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            return super.loadClass(n, resolve);
        }
    }

    /**
     * Defines a subclass of the loaded {@code EagerBean} whose
     * {@code $$crochetCopyFieldsTo} override throws. Used by the Gap-8 test.
     * The superclass's emitted eager checkpoint body will virtual-dispatch
     * to this override, hit the throw, and trigger the try/catch.
     */
    private static final class ThrowerLoader extends ClassLoader {
        private final Class<?> parent;

        ThrowerLoader(ClassLoader parentLoader, Class<?> parent) {
            super(parentLoader);
            this.parent = parent;
        }

        Class<?> defineThrower() {
            byte[] bytes = emitThrowerSubclass(parent);
            return defineClass(parent.getName() + "$Thrower", bytes, 0, bytes.length);
        }
    }

    /**
     * Emits {@code class EagerBean$Thrower extends EagerBean { ctor; override
     * $$crochetCopyFieldsTo to throw; }} via ASM.
     */
    private static byte[] emitThrowerSubclass(Class<?> parent) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS
                        | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        String parentInternal = parent.getName().replace('.', '/');
        String childInternal = parentInternal + "$Thrower";
        cw.visit(org.objectweb.asm.Opcodes.V11,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
                childInternal, null, parentInternal, null);

        // Constructor: delegate to super(int, String)
        org.objectweb.asm.MethodVisitor ctor = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>",
                "(ILjava/lang/String;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        ctor.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 1);
        ctor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
        ctor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                parentInternal, "<init>", "(ILjava/lang/String;)V", false);
        ctor.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // Override $$crochetCopyFieldsTo to throw.
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC
                        | org.objectweb.asm.Opcodes.ACC_SYNTHETIC,
                "$$crochetCopyFieldsTo", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.NEW,
                "java/lang/IllegalStateException");
        mv.visitInsn(org.objectweb.asm.Opcodes.DUP);
        mv.visitLdcInsn("induced for Gap-8 test");
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
