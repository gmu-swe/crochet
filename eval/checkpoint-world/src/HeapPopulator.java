import net.jonbell.crochet.runtime.CheckpointRollbackAgent;
import net.jonbell.crochet.runtime.CrochetWorldSafe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Pre-allocates a mix of ordinary Java objects to fill a target heap size.
 * When run under the Crochet javaagent + instrumented JDK, the transformer
 * automatically converts these plain classes into CRIJInstrumented instances
 * (adds {@code $$crochet*} fields/methods and the CRIJInstrumented interface).
 *
 * <p>Object mix (by bytes):
 * <ul>
 *   <li>40%: {@link SmallData} — ~56 bytes (2 int fields)
 *   <li>30%: {@link MediumData} — ~128 bytes (16-int array)
 *   <li>30%: {@link LargeData} — ~272 bytes (64-int array)
 * </ul>
 *
 * <p>The heap is filled to ~80% of the target size to leave room for GC
 * bookkeeping and measurement overhead. All root objects are held in a
 * top-level list to prevent premature collection.
 */
public class HeapPopulator {

    /**
     * Plain class with 2 int fields. The Crochet transformer instruments this
     * to implement CRIJInstrumented when run under the javaagent.
     */
    public static final class SmallData {
        public int a;
        public int b;

        public SmallData(int a, int b) { this.a = a; this.b = b; }
    }

    /**
     * Plain class backed by a 16-int array. Instrumented by Crochet.
     */
    public static final class MediumData {
        public int[] data;

        public MediumData(int seed) {
            data = new int[16];
            for (int i = 0; i < data.length; i++) data[i] = seed + i;
        }
    }

    /**
     * Plain class backed by a 64-int array. Instrumented by Crochet.
     */
    public static final class LargeData {
        public int[] data;

        public LargeData(int seed) {
            data = new int[64];
            for (int i = 0; i < data.length; i++) data[i] = seed + i;
        }
    }

    /** All allocated roots, held strongly to prevent GC during measurement. */
    public final List<Object> roots = new ArrayList<>();

    /** A HashMap simulating a realistic application data structure. */
    public final HashMap<Integer, SmallData> cache = new HashMap<>();

    public int smallCount;
    public int mediumCount;
    public int largeCount;

    /**
     * Allocates objects to fill ~80% of the heap.
     *
     * @param targetHeapBytes the -Xmx value in bytes
     */
    public void populate(long targetHeapBytes) {
        // Target 40% occupancy — leave ~60% headroom for GC bookkeeping and
        // the snap objects allocated by $$crochetCheckpoint (one per live
        // object on first checkpoint).  Filling to 80%+ causes GC thrashing
        // because the checkpoint pass itself doubles the live set.
        long targetLive = (long) (targetHeapBytes * 0.40);

        // Average object sizes (bytes, including Crochet instrumentation overhead):
        //   SmallData:  ~56 bytes (12 header + 4+4 payload + 4 version + 8 snap ref + align)
        //   MediumData: ~128 bytes (12 header + 8 ref + 4 version + 8 snap + 16*4 int-array + array header)
        //   LargeData:  ~272 bytes (12 header + 8 ref + 4 version + 8 snap + 64*4 int-array + array header)
        final long smallSize  = 56;
        final long mediumSize = 128;
        final long largeSize  = 272;

        // Mix: 40% small, 30% medium, 30% large (by bytes)
        long smallBytes  = (long) (targetLive * 0.40);
        long mediumBytes = (long) (targetLive * 0.30);
        long largeBytes  = (long) (targetLive * 0.30);

        smallCount  = (int) (smallBytes  / smallSize);
        mediumCount = (int) (mediumBytes / mediumSize);
        largeCount  = (int) (largeBytes  / largeSize);

        System.err.println("[HeapPopulator] target heap: " + (targetHeapBytes >> 20) + " MB"
                + " | target live: " + (targetLive >> 20) + " MB"
                + " | SmallData=" + smallCount
                + " | MediumData=" + mediumCount
                + " | LargeData=" + largeCount);

        // Allocate SmallData (half go into the cache map, half into roots list).
        int halfSmall = smallCount / 2;
        for (int i = 0; i < halfSmall; i++) {
            SmallData b = new SmallData(i, i * 2);
            cache.put(i, b);
        }
        for (int i = halfSmall; i < smallCount; i++) {
            roots.add(new SmallData(i, i * 2));
        }
        // MediumData goes into roots list.
        for (int i = 0; i < mediumCount; i++) {
            roots.add(new MediumData(i));
        }
        // LargeData goes into roots list.
        for (int i = 0; i < largeCount; i++) {
            roots.add(new LargeData(i));
        }

        // Also add the cache itself to roots so it's reachable.
        roots.add(cache);

        System.err.println("[HeapPopulator] allocated " + roots.size() + " root entries"
                + " + " + cache.size() + " cache entries");

        // Force a full GC to compact the heap and verify it fits.
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) >> 20;
        System.err.println("[HeapPopulator] post-GC heap usage: ~" + usedMB + " MB"
                + " (target occupancy: " + (targetLive >> 20) + " MB)");
    }

    /** Returns total live allocated instance count. */
    public int totalCount() {
        return smallCount + mediumCount + largeCount;
    }
}
