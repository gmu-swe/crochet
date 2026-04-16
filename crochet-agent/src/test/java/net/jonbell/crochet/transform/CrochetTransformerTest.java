package net.jonbell.crochet.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import net.jonbell.crochet.tests.Sample;
import net.jonbell.crochet.tests.SampleInterface;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class CrochetTransformerTest {

    @Test
    void skipsObjectAndModuleInfoAndInternalPackages() {
        assertTrue(CrochetTransformer.shouldSkip("java/lang/Object"));
        assertTrue(CrochetTransformer.shouldSkip("module-info"));
        assertTrue(CrochetTransformer.shouldSkip("net/jonbell/crochet/runtime/Tag"));
        assertTrue(CrochetTransformer.shouldSkip("net/jonbell/crochet/transform/CrochetTransformer"));
        assertTrue(CrochetTransformer.shouldSkip(null));
    }

    @Test
    void transformingObjectReturnsNull() throws IOException {
        byte[] bytes = readClassBytes("java.lang.Object");
        assertNull(new CrochetTransformer().transform(bytes, false));
    }

    @Test
    void injectsLookupMethodIntoOrdinaryClass() throws Exception {
        byte[] original = readClassBytes(Sample.class.getName());
        byte[] instrumented = new CrochetTransformer().transform(original, false);
        assertNotNull(instrumented);

        MethodPresence presence = new MethodPresence();
        new ClassReader(instrumented).accept(presence, 0);
        assertTrue(presence.found, "$$crochetLookup should be present after instrumentation");

        Class<?> loaded = new InMemoryLoader(Sample.class.getClassLoader(), Sample.class.getName(), instrumented)
                .loadClass(Sample.class.getName());
        Method lookupMethod = loaded.getDeclaredMethod(LookupInjector.LOOKUP_METHOD_NAME);
        MethodHandles.Lookup lookup = (MethodHandles.Lookup) lookupMethod.invoke(null);
        assertEquals(loaded, lookup.lookupClass(),
                "Lookup should be anchored in the instrumented class");
    }

    @Test
    void idempotentOnSecondPass() throws Exception {
        byte[] original = readClassBytes(Sample.class.getName());
        CrochetTransformer t = new CrochetTransformer();
        byte[] first = t.transform(original, false);
        byte[] second = t.transform(first, false);
        assertNotNull(second);

        MethodCount count = new MethodCount(LookupInjector.LOOKUP_METHOD_NAME);
        new ClassReader(second).accept(count, 0);
        assertEquals(1, count.count, "second pass must not duplicate the injected method");
    }

    @Test
    void skipsInterfaces() throws Exception {
        byte[] original = readClassBytes(SampleInterface.class.getName());
        byte[] instrumented = new CrochetTransformer().transform(original, false);
        assertNotNull(instrumented);

        MethodCount count = new MethodCount(LookupInjector.LOOKUP_METHOD_NAME);
        new ClassReader(instrumented).accept(count, 0);
        assertEquals(0, count.count, "interfaces should not receive a static lookup method");
    }

    private static byte[] readClassBytes(String fullyQualifiedName) throws IOException {
        String resource = fullyQualifiedName.replace('.', '/') + ".class";
        try (InputStream in = CrochetTransformerTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("class not found on classpath: " + resource);
            }
            return in.readAllBytes();
        }
    }

    private static final class InMemoryLoader extends ClassLoader {
        private final String name;
        private final byte[] bytes;

        InMemoryLoader(ClassLoader parent, String name, byte[] bytes) {
            super(parent);
            this.name = name;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String n) throws ClassNotFoundException {
            if (n.equals(name)) {
                return defineClass(n, bytes, 0, bytes.length);
            }
            return super.findClass(n);
        }

        @Override
        public Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
            if (n.equals(name)) {
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

    private static final class MethodPresence extends ClassVisitor {
        boolean found;

        MethodPresence() { super(Opcodes.ASM9); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (LookupInjector.LOOKUP_METHOD_NAME.equals(name)
                    && LookupInjector.LOOKUP_METHOD_DESCRIPTOR.equals(descriptor)) {
                found = true;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }

    private static final class MethodCount extends ClassVisitor {
        private final String target;
        int count;

        MethodCount(String target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (target.equals(name)) {
                count++;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }
}
