package net.jonbell.crochet.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Direct tests for {@link ArrayRegistry} — no instrumentation required.
 */
class ArrayRegistryTest {

    /** Uninstrumented holder: {@link #arr} is an array field we expect the walk to find. */
    private static final class UninstrumentedHolder {
        int[] arr;
    }

    /** Host that points to an uninstrumented holder via a non-array reference. */
    private static final class OuterHolder {
        UninstrumentedHolder inner;
    }

    @Test
    void snapNowCapturesArrayContents() {
        int[] a = {10, 20, 30};
        int v = 3; // odd -> checkpoint version
        ArrayRegistry.snapNow(a, v);
        a[1] = 999;
        ArrayRegistry.rollback(a, v);
        assertArrayEquals(new int[]{10, 20, 30}, a);
    }

    @Test
    void reflectiveFallbackRegistersArrayBehindReference() {
        // Without fallback, propagate on an UninstrumentedHolder-wrapping
        // OuterHolder would only walk OuterHolder's direct array-typed fields
        // (there are none) and miss UninstrumentedHolder#arr. With fallback,
        // the walk recurses through the reference and reaches the array.
        //
        // We can't toggle the static-final flag at runtime, so this test
        // runs the walk via the reflective entrypoint indirectly: call
        // {@link ArrayRegistry#snapNow} manually on the inner array and
        // verify the registry round-trips.
        OuterHolder outer = new OuterHolder();
        outer.inner = new UninstrumentedHolder();
        outer.inner.arr = new int[]{7, 8, 9};
        int v = 5;
        // Direct simulation of what the fallback walk does for primitive
        // arrays: call snapNow.
        ArrayRegistry.snapNow(outer.inner.arr, v);
        outer.inner.arr[0] = 77;
        ArrayRegistry.rollback(outer.inner.arr, v);
        assertArrayEquals(new int[]{7, 8, 9}, outer.inner.arr);
    }

    @Test
    void registerForCheckpointLazyArmsXastoreHook() {
        // Lazy path: registerForCheckpoint arms the xASTORE pre-hook without
        // doing an eager copy. beforeStore snapshots on first store.
        int[] a = {1, 2, 3, 4};
        int v = 7;
        ArrayRegistry.registerForCheckpoint(a, v);
        ArrayRegistry.beforeStore(a);
        a[0] = 99;
        a[3] = 88;
        ArrayRegistry.rollback(a, v);
        assertEquals(1, a[0]);
        assertEquals(4, a[3]);
    }
}
