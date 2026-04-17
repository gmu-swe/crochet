package net.jonbell.crochet.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.jonbell.crochet.tests.Sample;
import net.jonbell.crochet.transform.CrochetTransformer;
import net.jonbell.crochet.transform.FieldAdder;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link ReflectionFilter}. Uses a live {@link CrochetTransformer}
 * pass over the {@link Sample} fixture so the injected {@code $$crochet*}
 * members carry the real modifier bits (ACC_SYNTHETIC) we filter on.
 */
class ReflectionFilterTest {

    @Test
    void filterFieldsStripsInjectedFields() throws Exception {
        Class<?> instr = loadInstrumentedSample();
        Field[] raw = instr.getDeclaredFields();
        assertTrue(containsName(raw, FieldAdder.VERSION_FIELD),
                "raw declared fields must contain injected $$crochetVersion");
        assertTrue(containsName(raw, FieldAdder.SNAP_FIELD),
                "raw declared fields must contain injected $$crochetSnap");

        Field[] filtered = ReflectionFilter.filterFields(raw);
        assertFalse(containsName(filtered, FieldAdder.VERSION_FIELD),
                "filtered fields must not contain $$crochetVersion");
        assertFalse(containsName(filtered, FieldAdder.SNAP_FIELD),
                "filtered fields must not contain $$crochetSnap");
        assertTrue(containsName(filtered, "counter"),
                "filtered fields must still contain the user field 'counter'");
    }

    @Test
    void filterMethodsStripsInjectedMethods() throws Exception {
        Class<?> instr = loadInstrumentedSample();
        Method[] raw = instr.getDeclaredMethods();
        assertTrue(containsMethodName(raw, "$$crochetCheckpoint"),
                "raw declared methods must contain injected $$crochetCheckpoint");
        assertTrue(containsMethodName(raw, "$$crochetAccess"),
                "raw declared methods must contain injected $$crochetAccess");

        Method[] filtered = ReflectionFilter.filterMethods(raw);
        for (Method m : filtered) {
            assertFalse(m.getName().startsWith("$$crochet"),
                    "filtered methods must not contain any $$crochet* method, saw "
                            + m.getName());
        }
        assertTrue(containsMethodName(filtered, "bump"),
                "filtered methods must still contain the user method 'bump'");
    }

    @Test
    void filterInterfacesStripsCrijInstrumented() throws Exception {
        Class<?> instr = loadInstrumentedSample();
        Class<?>[] raw = instr.getInterfaces();
        boolean sawMarker = false;
        for (Class<?> c : raw) {
            if (c == CRIJInstrumented.class) {
                sawMarker = true;
                break;
            }
        }
        assertTrue(sawMarker, "instrumented class must implement CRIJInstrumented");

        Class<?>[] filtered = ReflectionFilter.filterInterfaces(raw);
        for (Class<?> c : filtered) {
            assertFalse(c == CRIJInstrumented.class,
                    "filtered interfaces must not contain CRIJInstrumented");
        }
    }

    @Test
    void filterFieldsShortCircuitsWhenNothingToStrip() {
        Field[] empty = new Field[0];
        assertSame(empty, ReflectionFilter.filterFields(empty));
        assertSame(null, ReflectionFilter.filterFields(null));
    }

    @Test
    void filterMethodsShortCircuitsWhenNothingToStrip() {
        Method[] empty = new Method[0];
        assertSame(empty, ReflectionFilter.filterMethods(empty));
        assertSame(null, ReflectionFilter.filterMethods(null));
    }

    @Test
    void filterInterfacesShortCircuitsOnEmpty() {
        Class<?>[] empty = new Class<?>[0];
        assertSame(empty, ReflectionFilter.filterInterfaces(empty));
    }

    @Test
    void unwrapClassPreservesNonProxy() {
        assertSame(String.class, ReflectionFilter.unwrapClass(String.class));
        assertSame(null, ReflectionFilter.unwrapClass(null));
    }

    @Test
    void getFieldRoutesThroughCrochetAccess() throws Exception {
        RecordingInstrumented target = new RecordingInstrumented();
        target.value = 42;
        Field f = RecordingInstrumented.class.getDeclaredField("value");
        f.setAccessible(true);

        Object read = ReflectionFilter.getField(f, target);
        assertEquals(42, read);
        assertTrue(target.accessed,
                "$$crochetAccess() must fire before Field.get");
    }

    @Test
    void setFieldRoutesThroughCrochetAccess() throws Exception {
        RecordingInstrumented target = new RecordingInstrumented();
        Field f = RecordingInstrumented.class.getDeclaredField("value");
        f.setAccessible(true);

        ReflectionFilter.setField(f, target, 99);
        assertEquals(99, target.value);
        assertTrue(target.accessed,
                "$$crochetAccess() must fire before Field.set");
    }

    @Test
    void getIntRoutesThroughCrochetAccess() throws Exception {
        RecordingInstrumented target = new RecordingInstrumented();
        target.value = 7;
        Field f = RecordingInstrumented.class.getDeclaredField("value");
        f.setAccessible(true);

        int read = ReflectionFilter.getInt(f, target);
        assertEquals(7, read);
        assertTrue(target.accessed,
                "$$crochetAccess() must fire before Field.getInt");
    }

    @Test
    void nonInstrumentedTargetSkipsHook() throws Exception {
        // Reflective get/set on a plain POJO still works; the hook just isn't
        // fired. This guards against regressions where the helper NPEs on
        // uninstrumented targets.
        PlainPojo target = new PlainPojo();
        Field f = PlainPojo.class.getDeclaredField("x");
        f.setAccessible(true);
        ReflectionFilter.setField(f, target, 123);
        assertEquals(123, (int) (Integer) ReflectionFilter.getField(f, target));
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private static boolean containsName(Field[] fields, String name) {
        for (Field f : fields) {
            if (name.equals(f.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMethodName(Method[] methods, String name) {
        for (Method m : methods) {
            if (name.equals(m.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@link CrochetTransformer} over the {@link Sample} fixture and
     * defines the instrumented bytes in a fresh classloader so our tests
     * see a real instrumented class with synthetic {@code $$crochet*}
     * members.
     */
    private static Class<?> loadInstrumentedSample() throws Exception {
        byte[] original = readClassBytes(Sample.class.getName());
        byte[] instrumented = new CrochetTransformer().transform(original, false);
        assertNotNull(instrumented);
        InMemoryLoader loader = new InMemoryLoader(
                Sample.class.getClassLoader(), Sample.class.getName(), instrumented);
        return loader.loadClass(Sample.class.getName());
    }

    private static byte[] readClassBytes(String fullyQualifiedName) throws IOException {
        String resource = fullyQualifiedName.replace('.', '/') + ".class";
        try (InputStream in = ReflectionFilterTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("class not found: " + resource);
            }
            return in.readAllBytes();
        }
    }

    /** Minimal CRIJInstrumented fake that records whether access() fired. */
    public static final class RecordingInstrumented implements CRIJInstrumented {
        public int value;
        public boolean accessed;

        @Override public void $$crochetAccess() { accessed = true; }
        @Override public void $$crochetCopyFieldsTo(Object to) {}
        @Override public void $$crochetCopyFieldsFrom(Object old) {}
        @Override public void $$crochetCheckpoint(int v) {}
        @Override public void $$crochetRollback(int v) {}
        @Override public void $$crochetPropagateCheckpoint(int v) {}
        @Override public void $$crochetPropagateRollback(int v) {}
        @Override public int $$crochetGetVersion() { return 0; }
        @Override public void $$crochetSetVersion(int v) {}
        @Override public Object $$crochetGetSnap() { return null; }
        @Override public void $$crochetSetSnap(Object snap) {}
        @Override public boolean $$crochetIsRollbackState() { return false; }
    }

    /** Non-CRIJInstrumented POJO — reflective access should skip the hook. */
    public static final class PlainPojo {
        public int x;
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
}
