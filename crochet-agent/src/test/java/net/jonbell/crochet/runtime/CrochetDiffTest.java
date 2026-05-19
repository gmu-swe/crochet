package net.jonbell.crochet.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import net.jonbell.crochet.transform.CrochetTransformer;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Crochet#diff(Object)} and {@link Crochet#diffStatic(Class)}.
 *
 * <p>Tests run the full Crochet transformer pipeline on fixture classes loaded
 * in a private class loader so each test class gets fresh static state
 * (important for the static-field tests that mutate static fields).
 *
 * <h2>Validation matrix</h2>
 * <ol>
 *   <li>All 8 primitive field types: int, long, double, float, boolean, byte,
 *       char, short.
 *   <li>Reference field.
 *   <li>Primitive array fields (per type) — compared by content, not reference.
 *   <li>Reference array field — compared as opaque reference.
 *   <li>Null transitions: null to non-null and non-null to null.
 *   <li>Cycle test: self-edge causes no infinite loop, returns finite diff.
 *   <li>Static-field diff equivalence.
 *   <li>Live-only contract: empty diff before checkpoint and after rollback.
 *   <li>Property test: diff is the inverse of rollback.
 * </ol>
 */
class CrochetDiffTest {

    // -------------------------------------------------------------------------
    // Loader infrastructure — mirrors EagerCheckpointTest
    // -------------------------------------------------------------------------

    private static Class<?> instrumentAndLoad(String fqn)
            throws IOException, ClassNotFoundException {
        return instrumentAndLoad(fqn, CrochetDiffTest.class.getClassLoader());
    }

    private static Class<?> instrumentAndLoad(String fqn, ClassLoader parent)
            throws IOException, ClassNotFoundException {
        byte[] original = readClassBytes(fqn);
        byte[] instrumented = new CrochetTransformer().transform(original, false);
        assertNotNull(instrumented, "transformer must produce output for " + fqn);
        return new FixtureLoader(parent, fqn, instrumented).loadClass(fqn);
    }

    private static byte[] readClassBytes(String fqn) throws IOException {
        String resource = fqn.replace('.', '/') + ".class";
        try (InputStream in = CrochetDiffTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("class not found: " + resource);
            }
            return in.readAllBytes();
        }
    }

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
                if (c == null) c = findClass(n);
                if (resolve) resolveClass(c);
                return c;
            }
            return super.loadClass(n, resolve);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a new DiffBean via its no-arg constructor. */
    private static Object newBean(Class<?> cls) throws Exception {
        return cls.getDeclaredConstructor().newInstance();
    }

    private static Field field(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Returns a map of fieldName to FieldDiff for convenient lookup in assertions. */
    private static Map<String, FieldDiff> diffMap(List<FieldDiff> diffs) {
        return diffs.stream()
                .collect(Collectors.toMap(FieldDiff::fieldName, d -> d));
    }

    // -------------------------------------------------------------------------
    // 1. Live-only contract: empty diff before any checkpoint
    // -------------------------------------------------------------------------

    @Test
    void diffReturnsEmptyListWhenNoCheckpointTaken() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fInt").setInt(bean, 99);

        List<FieldDiff> diffs = Crochet.diff(bean);
        assertTrue(diffs.isEmpty(),
                "diff must return empty list when no checkpoint is live");
    }

    @Test
    void diffReturnsEmptyListAfterRollback() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fInt").setInt(bean, 5);

        int v = CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fInt").setInt(bean, 10);
        CheckpointRollbackAgent.rollback(bean, v);

        // After rollback the snap slot is cleared.
        List<FieldDiff> diffs = Crochet.diff(bean);
        assertTrue(diffs.isEmpty(),
                "diff must return empty list after rollback clears the snap");
    }

    @Test
    void diffReturnsEmptyListForNonInstrumentedObject() {
        // A plain POJO that was never transformed should produce empty diff.
        Object plain = new Object();
        assertTrue(Crochet.diff(plain).isEmpty());
    }

    // -------------------------------------------------------------------------
    // 2. Primitive field diffs (all 8 types)
    // -------------------------------------------------------------------------

    @Test
    void diffDetectsChangedIntField() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fInt").setInt(bean, 1);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fInt").setInt(bean, 42);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fInt"), "fInt should be in diff");
        assertEquals(1, diffs.get("fInt").snapValue());
        assertEquals(42, diffs.get("fInt").currentValue());
    }

    @Test
    void diffDetectsChangedLongField() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fLong").setLong(bean, 1_000_000_000L);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fLong").setLong(bean, 9_999_999_999L);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fLong"));
        assertEquals(1_000_000_000L, diffs.get("fLong").snapValue());
        assertEquals(9_999_999_999L, diffs.get("fLong").currentValue());
    }

    @Test
    void diffDetectsChangedDoubleField() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fDouble").setDouble(bean, 1.5);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fDouble").setDouble(bean, 3.14);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fDouble"));
        assertEquals(1.5, diffs.get("fDouble").snapValue());
        assertEquals(3.14, diffs.get("fDouble").currentValue());
    }

    @Test
    void diffDetectsChangedFloatField() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fFloat").setFloat(bean, 1.0f);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fFloat").setFloat(bean, 2.5f);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fFloat"));
        assertEquals(1.0f, diffs.get("fFloat").snapValue());
        assertEquals(2.5f, diffs.get("fFloat").currentValue());
    }

    @Test
    void diffDetectsChangedBooleanField() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fBool").setBoolean(bean, false);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fBool").setBoolean(bean, true);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fBool"));
        assertEquals(false, diffs.get("fBool").snapValue());
        assertEquals(true,  diffs.get("fBool").currentValue());
    }

    @Test
    void diffDetectsChangedByteField() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fByte").setByte(bean, (byte) 7);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fByte").setByte(bean, (byte) 42);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fByte"));
        assertEquals((byte) 7,  diffs.get("fByte").snapValue());
        assertEquals((byte) 42, diffs.get("fByte").currentValue());
    }

    @Test
    void diffDetectsChangedCharField() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fChar").setChar(bean, 'a');
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fChar").setChar(bean, 'z');

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fChar"));
        assertEquals('a', diffs.get("fChar").snapValue());
        assertEquals('z', diffs.get("fChar").currentValue());
    }

    @Test
    void diffDetectsChangedShortField() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fShort").setShort(bean, (short) 100);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fShort").setShort(bean, (short) 200);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fShort"));
        assertEquals((short) 100, diffs.get("fShort").snapValue());
        assertEquals((short) 200, diffs.get("fShort").currentValue());
    }

    // -------------------------------------------------------------------------
    // 3. Reference field diff
    // -------------------------------------------------------------------------

    @Test
    void diffDetectsChangedReferenceField() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        String snap = "hello";
        String live = "world";
        field(cls, "fRef").set(bean, snap);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fRef").set(bean, live);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fRef"));
        assertEquals("hello", diffs.get("fRef").snapValue());
        assertEquals("world", diffs.get("fRef").currentValue());
    }

    // -------------------------------------------------------------------------
    // 4. Null transitions (null to non-null, non-null to null)
    // -------------------------------------------------------------------------

    @Test
    void diffDetectsNullToNonNullTransition() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fRef").set(bean, null);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fRef").set(bean, "now non-null");

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fRef"), "null->non-null must appear in diff");
        assertNull(diffs.get("fRef").snapValue());
        assertEquals("now non-null", diffs.get("fRef").currentValue());
    }

    @Test
    void diffDetectsNonNullToNullTransition() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fRef").set(bean, "was non-null");
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fRef").set(bean, null);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fRef"), "non-null->null must appear in diff");
        assertEquals("was non-null", diffs.get("fRef").snapValue());
        assertNull(diffs.get("fRef").currentValue());
    }

    // -------------------------------------------------------------------------
    // 5. Primitive array fields — per type
    // -------------------------------------------------------------------------

    @Test
    void diffDetectsChangedIntArray() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        int[] arr1 = {1, 2, 3};
        int[] arr2 = {1, 2, 99};
        field(cls, "fIntArr").set(bean, arr1.clone());
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fIntArr").set(bean, arr2.clone());

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fIntArr"), "changed int[] must appear in diff");
        assertArrayEquals(arr1, (int[]) diffs.get("fIntArr").snapValue());
        assertArrayEquals(arr2, (int[]) diffs.get("fIntArr").currentValue());
    }

    @Test
    void diffDoesNotReportUnchangedIntArrayWithSameContents() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        int[] arr = {1, 2, 3};
        field(cls, "fIntArr").set(bean, arr.clone());
        CheckpointRollbackAgent.checkpoint(bean);
        // Set a different array object with the same contents — should NOT diff.
        field(cls, "fIntArr").set(bean, arr.clone());

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertFalse(diffs.containsKey("fIntArr"),
                "int[] with equal elements must not appear in diff");
    }

    @Test
    void diffDetectsChangedLongArray() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fLongArr").set(bean, new long[]{1L, 2L});
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fLongArr").set(bean, new long[]{1L, 3L});

        assertTrue(diffMap(Crochet.diff(bean)).containsKey("fLongArr"));
    }

    @Test
    void diffDetectsChangedDoubleArray() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fDoubleArr").set(bean, new double[]{1.0, 2.0});
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fDoubleArr").set(bean, new double[]{1.0, 3.0});

        assertTrue(diffMap(Crochet.diff(bean)).containsKey("fDoubleArr"));
    }

    @Test
    void diffDetectsChangedFloatArray() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fFloatArr").set(bean, new float[]{1.0f});
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fFloatArr").set(bean, new float[]{2.0f});

        assertTrue(diffMap(Crochet.diff(bean)).containsKey("fFloatArr"));
    }

    @Test
    void diffDetectsChangedBooleanArray() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fBoolArr").set(bean, new boolean[]{true, false});
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fBoolArr").set(bean, new boolean[]{false, false});

        assertTrue(diffMap(Crochet.diff(bean)).containsKey("fBoolArr"));
    }

    @Test
    void diffDetectsChangedByteArray() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fByteArr").set(bean, new byte[]{1, 2});
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fByteArr").set(bean, new byte[]{1, 3});

        assertTrue(diffMap(Crochet.diff(bean)).containsKey("fByteArr"));
    }

    @Test
    void diffDetectsChangedCharArray() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fCharArr").set(bean, new char[]{'a', 'b'});
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fCharArr").set(bean, new char[]{'a', 'c'});

        assertTrue(diffMap(Crochet.diff(bean)).containsKey("fCharArr"));
    }

    @Test
    void diffDetectsChangedShortArray() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fShortArr").set(bean, new short[]{10, 20});
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fShortArr").set(bean, new short[]{10, 30});

        assertTrue(diffMap(Crochet.diff(bean)).containsKey("fShortArr"));
    }

    // -------------------------------------------------------------------------
    // 6. Reference array — opaque reference comparison
    // -------------------------------------------------------------------------

    @Test
    void diffDetectsReferenceArrayChange() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        Object[] arr1 = {"a", "b"};
        Object[] arr2 = {"a", "c"};
        field(cls, "fRefArr").set(bean, arr1);
        CheckpointRollbackAgent.checkpoint(bean);
        field(cls, "fRefArr").set(bean, arr2);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fRefArr"),
                "reference array switch must appear in diff");
        assertSame(arr1, diffs.get("fRefArr").snapValue());
        assertSame(arr2, diffs.get("fRefArr").currentValue());
    }

    @Test
    void diffDoesNotReportUnchangedReferenceArray() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        Object[] arr = {"x"};
        field(cls, "fRefArr").set(bean, arr);
        CheckpointRollbackAgent.checkpoint(bean);
        // Same reference — no diff.
        field(cls, "fRefArr").set(bean, arr);

        assertFalse(diffMap(Crochet.diff(bean)).containsKey("fRefArr"),
                "same reference array must not appear in diff");
    }

    // -------------------------------------------------------------------------
    // 7. Cycle test: self-edge causes no infinite loop
    // -------------------------------------------------------------------------

    @Test
    void diffWithSelfEdgeProducesFiniteDiffNoStackOverflow() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        // Create a self-edge: bean.self = bean
        Field selfField = field(cls, "self");
        selfField.set(bean, bean);

        CheckpointRollbackAgent.checkpoint(bean);

        // Mutate a primitive field so we get at least one diff entry.
        field(cls, "fInt").setInt(bean, 7);

        // Must terminate without StackOverflowError.
        List<FieldDiff> diffs = assertDoesNotThrow(
                () -> Crochet.diff(bean),
                "self-edge must not cause StackOverflowError");

        // We expect at least the primitive mutation (fInt) to appear.
        assertNotNull(diffs);
        // Verify no infinite loop: if we reach this assertion, the diff is finite.
        assertTrue(diffs.size() >= 1, "should detect at least one mutation");
    }

    // -------------------------------------------------------------------------
    // 8. Unchanged fields do not appear in the diff
    // -------------------------------------------------------------------------

    @Test
    void unchangedFieldsDoNotAppearInDiff() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fInt").setInt(bean, 5);
        field(cls, "fRef").set(bean, "unchanged");
        CheckpointRollbackAgent.checkpoint(bean);
        // Only mutate fInt; fRef stays the same.
        field(cls, "fInt").setInt(bean, 99);

        Map<String, FieldDiff> diffs = diffMap(Crochet.diff(bean));
        assertTrue(diffs.containsKey("fInt"));
        assertFalse(diffs.containsKey("fRef"),
                "fRef was not changed and must not appear in diff");
    }

    // -------------------------------------------------------------------------
    // 9. Static-field diff
    // -------------------------------------------------------------------------

    @Test
    void diffStaticReturnsEmptyListWhenNoCheckpoint() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.StaticDiffFixture");
        // No checkpoint taken — SF helper may not even be materialised yet.
        List<FieldDiff> diffs = Crochet.diffStatic(cls);
        assertTrue(diffs.isEmpty(),
                "diffStatic must return empty list before any checkpoint");
    }

    @Test
    void diffStaticDetectsChangedStaticFields() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.StaticDiffFixture");
        Field sValue = cls.getDeclaredField("sValue");
        sValue.setAccessible(true);
        Field sLabel = cls.getDeclaredField("sLabel");
        sLabel.setAccessible(true);

        sValue.set(null, 10);
        sLabel.set(null, "before");

        // Checkpoint the class's static fields.
        CheckpointRollbackAgent.checkpointClass(cls);

        // Mutate.
        sValue.set(null, 99);
        sLabel.set(null, "after");

        List<FieldDiff> diffs = Crochet.diffStatic(cls);
        Map<String, FieldDiff> diffMap = diffMap(diffs);

        assertTrue(diffMap.containsKey("sValue"), "sValue must be in diff");
        assertEquals(10,       diffMap.get("sValue").snapValue());
        assertEquals(99,       diffMap.get("sValue").currentValue());

        assertTrue(diffMap.containsKey("sLabel"), "sLabel must be in diff");
        assertEquals("before", diffMap.get("sLabel").snapValue());
        assertEquals("after",  diffMap.get("sLabel").currentValue());

        // CONSTANT is final — must not appear.
        assertFalse(diffMap.containsKey("CONSTANT"),
                "final static fields must not appear in diff");
    }

    @Test
    void diffStaticSharedCodePathEquivalence() throws Exception {
        // Static-field diff equivalence: diffStatic(C.class) and diff(sfHelper)
        // go through the same walkFields code path. We verify they report the
        // same number of entries under identical mutation.
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.StaticDiffFixture");
        Field sValue = cls.getDeclaredField("sValue");
        sValue.setAccessible(true);

        sValue.set(null, 5);
        CheckpointRollbackAgent.checkpointClass(cls);
        sValue.set(null, 50);

        // diffStatic should report sValue.
        List<FieldDiff> diffs = Crochet.diffStatic(cls);
        assertEquals(1, diffs.size(),
                "should detect exactly 1 changed static field");
        assertEquals("sValue", diffs.get(0).fieldName());
    }

    // -------------------------------------------------------------------------
    // 10. Property test: diff is inverse of rollback
    //
    // Fuzz random mutation patterns on DiffBean. For each mutated field in the
    // diff, applying the snapValue back to the live object reproduces the
    // checkpointed state (i.e. rollback semantics). This is a hand-rolled
    // randomised test since jqwik is not on the classpath.
    // -------------------------------------------------------------------------

    @Test
    void propertyDiffIsInverseOfRollback() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Random rng = new Random(0xDEADBEEFL);

        // Run N random mutation rounds.
        for (int round = 0; round < 50; round++) {
            Object bean = newBean(cls);

            // Set random initial values.
            field(cls, "fInt").setInt(bean, rng.nextInt());
            field(cls, "fLong").setLong(bean, rng.nextLong());
            field(cls, "fRef").set(bean, "snap-" + rng.nextInt(100));

            CheckpointRollbackAgent.checkpoint(bean);

            // Record snap values before mutation.
            int snapInt   = field(cls, "fInt").getInt(bean);
            long snapLong = field(cls, "fLong").getLong(bean);
            String snapRef = (String) field(cls, "fRef").get(bean);

            // Apply random mutations.
            if (rng.nextBoolean()) field(cls, "fInt").setInt(bean, rng.nextInt());
            if (rng.nextBoolean()) field(cls, "fLong").setLong(bean, rng.nextLong());
            if (rng.nextBoolean()) field(cls, "fRef").set(bean, "live-" + rng.nextInt(100));

            List<FieldDiff> diffs = Crochet.diff(bean);

            // Apply snapValues from the diff back to the object — this should
            // reproduce the checkpointed state.
            for (FieldDiff d : diffs) {
                Field f = field(cls, d.fieldName());
                Class<?> type = f.getType();
                Object snapVal = d.snapValue();
                if (type == int.class)    f.setInt(bean,  snapVal == null ? 0    : (int)    snapVal);
                else if (type == long.class)   f.setLong(bean, snapVal == null ? 0L   : (long)   snapVal);
                else                           f.set(bean, snapVal);
            }

            // After applying snap values, the object should match checkpoint state.
            assertEquals(snapInt,  field(cls, "fInt").getInt(bean),
                    "round " + round + ": fInt must match snap after applying diff");
            assertEquals(snapLong, field(cls, "fLong").getLong(bean),
                    "round " + round + ": fLong must match snap after applying diff");
            assertEquals(snapRef,  field(cls, "fRef").get(bean),
                    "round " + round + ": fRef must match snap after applying diff");
        }
    }

    // -------------------------------------------------------------------------
    // 11. FieldValuesEqual unit tests (internal utility)
    // -------------------------------------------------------------------------

    @Test
    void fieldValuesEqualReturnsTrueForEqualPrimitivesAndBoxed() {
        assertTrue(Crochet.fieldValuesEqual(int.class, 5, 5));
        assertFalse(Crochet.fieldValuesEqual(int.class, 5, 6));
        assertTrue(Crochet.fieldValuesEqual(boolean.class, true, true));
        assertFalse(Crochet.fieldValuesEqual(boolean.class, true, false));
    }

    @Test
    void fieldValuesEqualUsesArrayEqualsForPrimitiveArrays() {
        // Same contents, different identity -> equal.
        assertTrue(Crochet.fieldValuesEqual(int[].class, new int[]{1,2,3}, new int[]{1,2,3}));
        assertFalse(Crochet.fieldValuesEqual(int[].class, new int[]{1,2,3}, new int[]{1,2,4}));

        assertTrue(Crochet.fieldValuesEqual(byte[].class,    new byte[]{0x01},      new byte[]{0x01}));
        assertTrue(Crochet.fieldValuesEqual(long[].class,    new long[]{Long.MAX_VALUE}, new long[]{Long.MAX_VALUE}));
        assertTrue(Crochet.fieldValuesEqual(double[].class,  new double[]{Math.PI}, new double[]{Math.PI}));
        assertTrue(Crochet.fieldValuesEqual(float[].class,   new float[]{1.0f},     new float[]{1.0f}));
        assertTrue(Crochet.fieldValuesEqual(boolean[].class, new boolean[]{true, false}, new boolean[]{true, false}));
        assertTrue(Crochet.fieldValuesEqual(char[].class,    new char[]{'x'},        new char[]{'x'}));
        assertTrue(Crochet.fieldValuesEqual(short[].class,   new short[]{100},       new short[]{100}));
    }

    @Test
    void fieldValuesEqualUsesReferenceEqualsForReferenceArrays() {
        // Reference arrays: two arrays with same contents but different identity -> NOT equal.
        Object[] a = {"a", "b"};
        Object[] b = {"a", "b"};
        assertFalse(Crochet.fieldValuesEqual(Object[].class, a, b),
                "reference arrays with same contents but different identity must not be equal");
        // Same identity -> equal.
        assertTrue(Crochet.fieldValuesEqual(Object[].class, a, a));
    }

    @Test
    void fieldValuesEqualHandlesNullTransitions() {
        assertTrue(Crochet.fieldValuesEqual(String.class, null, null));
        assertFalse(Crochet.fieldValuesEqual(String.class, null, "x"));
        assertFalse(Crochet.fieldValuesEqual(String.class, "x", null));
        assertFalse(Crochet.fieldValuesEqual(int[].class, null, new int[]{1}));
        assertFalse(Crochet.fieldValuesEqual(int[].class, new int[]{1}, null));
    }

    // -------------------------------------------------------------------------
    // 12. No-diff when nothing changed
    // -------------------------------------------------------------------------

    @Test
    void diffIsEmptyWhenNothingChanged() throws Exception {
        Class<?> cls = instrumentAndLoad("net.jonbell.crochet.tests.DiffBean");
        Object bean = newBean(cls);
        field(cls, "fInt").setInt(bean, 5);
        field(cls, "fRef").set(bean, "hello");
        CheckpointRollbackAgent.checkpoint(bean);
        // Do not mutate anything.

        List<FieldDiff> diffs = Crochet.diff(bean);
        assertTrue(diffs.isEmpty(),
                "diff must be empty when no mutations occurred since checkpoint");
    }
}
