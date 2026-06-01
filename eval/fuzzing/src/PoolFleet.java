package eval.fuzzing;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful fuzz target: a fleet of {@link GenericObjectPool}s, each with a
 * heavy custom factory. Operations mutate per-pool state (idle/active counts,
 * eviction config, factory counters) and inter-pool state (round-robin
 * counter, the {@link #lastBorrowed} map of pool-id to borrowed handles).
 *
 * <p>The fleet shape (N pools, each preloaded to its min-idle) makes
 * {@link #setup()} cost a few hundred ms. This is the cost Mode-3
 * checkpoint/rollback amortises across the fuzz campaign.
 *
 * <p>Each public {@code op*} method has 1-3 instrumented Coverage probes at
 * its branch points; that's where the fuzzer's edge-coverage signal comes
 * from. Op IDs are stable so the fuzzer's bucket counts are reproducible.
 */
public final class PoolFleet {

    public static final int FLEET_SIZE = 16;
    public static final int INITIAL_PRELOAD = 8;

    /**
     * Per-Widget init iterations — the {@code makeObject} factory hashes its
     * 4KB buffer this many times. Bumping this dial tunes the setup-vs-rollback
     * crossover: low values keep setup cheap (rollback can't win); high values
     * make setup heavy enough for rollback to amortise. Default 1 = ~3 ms per
     * setup; 50 = ~50 ms per setup.
     */
    public static int WIDGET_INIT_ITERS = Integer.parseInt(
            System.getProperty("eval.fuzzing.widgetInitIters", "1"));

    private final GenericObjectPool<Widget>[] pools;
    private final WidgetFactory[] factories;
    private final Map<Integer, List<Widget>> lastBorrowed = new HashMap<>();
    private int rrCounter = 0;

    @SuppressWarnings("unchecked")
    public PoolFleet() {
        pools = (GenericObjectPool<Widget>[]) new GenericObjectPool[FLEET_SIZE];
        factories = new WidgetFactory[FLEET_SIZE];
    }

    /**
     * Heavy init: build {@link #FLEET_SIZE} pools and preload each to
     * {@code INITIAL_PRELOAD} idle objects. Each {@code makeObject} call does a
     * non-trivial allocation (Widget allocates a 4KB byte[] and runs a small
     * checksum). The aggregate cost is intended to be in the 100-500ms range.
     */
    public void setup() throws Exception {
        for (int i = 0; i < FLEET_SIZE; i++) {
            GenericObjectPoolConfig<Widget> cfg = new GenericObjectPoolConfig<>();
            cfg.setMaxTotal(32);
            cfg.setMaxIdle(16);
            cfg.setMinIdle(4);
            cfg.setBlockWhenExhausted(false);
            // No background eviction thread. The evictor daemon trips
            // IllegalMonitorStateException under {@code rollbackAll} when its
            // AQS condition state gets restored mid-wait. We exercise eviction
            // synchronously via {@link #opEvict} instead.
            cfg.setTimeBetweenEvictionRuns(Duration.ZERO);
            cfg.setMinEvictableIdleDuration(Duration.ofMillis(100));
            cfg.setTestOnBorrow(true);
            cfg.setTestOnReturn(true);
            factories[i] = new WidgetFactory(i);
            pools[i] = new GenericObjectPool<>(factories[i], cfg);
            pools[i].setMaxWait(Duration.ofMillis(50));
            // Preload.
            List<Widget> tmp = new ArrayList<>();
            for (int j = 0; j < INITIAL_PRELOAD; j++) {
                tmp.add(pools[i].borrowObject());
            }
            for (Widget w : tmp) {
                pools[i].returnObject(w);
            }
            lastBorrowed.put(i, new ArrayList<>());
        }
    }

    /**
     * Borrow op. Edge probes: 0x0100 (entry), 0x0101 (success), 0x0102 (failure).
     */
    public void opBorrow(int poolIdx) {
        Coverage.hit(0x0100 | (poolIdx & 0xF));
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        // Saturation-band probe: emits a different edge depending on the
        // pool's current active-count band. This makes coverage state-dependent
        // so the fuzzer can keep discovering edges as it explores deeper
        // pool configurations.
        int active = pools[idx].getNumActive();
        if (active == 0) Coverage.hit(0x0110 | idx);
        else if (active < 4) Coverage.hit(0x0120 | idx);
        else if (active < 16) Coverage.hit(0x0130 | idx);
        else Coverage.hit(0x0140 | idx);
        try {
            Widget w = pools[idx].borrowObject();
            if (w != null) {
                Coverage.hit(0x0150 | idx);
                lastBorrowed.get(idx).add(w);
                // After-borrow band probe.
                int idle = pools[idx].getNumIdle();
                if (idle == 0) Coverage.hit(0x0160 | idx);
                else if (idle < 4) Coverage.hit(0x0170 | idx);
                else Coverage.hit(0x0180 | idx);
            }
        } catch (Exception e) {
            Coverage.hit(0x0190 | idx);
        }
    }

    /**
     * Return op. Pops the most recently borrowed Widget for the chosen pool
     * (LIFO matches real-world borrow-and-release patterns). Probes:
     * 0x0200 (entry), 0x0201 (success), 0x0202 (no-borrow case).
     */
    public void opReturn(int poolIdx) {
        Coverage.hit(0x0200 | (poolIdx & 0xF));
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        List<Widget> bs = lastBorrowed.get(idx);
        if (bs.isEmpty()) {
            Coverage.hit(0x0210 | idx);
            return;
        }
        Widget w = bs.remove(bs.size() - 1);
        // Coverage on the depth of the borrowed-stack at return time.
        int depth = bs.size();
        if (depth == 0) Coverage.hit(0x0220 | idx);
        else if (depth < 4) Coverage.hit(0x0230 | idx);
        else Coverage.hit(0x0240 | idx);
        try {
            pools[idx].returnObject(w);
            Coverage.hit(0x0250 | idx);
        } catch (Exception e) {
            Coverage.hit(0x0260 | idx);
        }
    }

    /**
     * Invalidate op. Probes 0x0300 entry, 0x0301 success, 0x0302 no-borrow.
     */
    public void opInvalidate(int poolIdx) {
        Coverage.hit(0x0300);
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        List<Widget> bs = lastBorrowed.get(idx);
        if (bs.isEmpty()) {
            Coverage.hit(0x0302);
            return;
        }
        Widget w = bs.remove(bs.size() - 1);
        try {
            pools[idx].invalidateObject(w);
            Coverage.hit(0x0301);
        } catch (Exception e) {
            Coverage.hit(0x0303);
        }
    }

    public void opClear(int poolIdx) {
        Coverage.hit(0x0400);
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        try {
            pools[idx].clear();
            Coverage.hit(0x0401);
            lastBorrowed.get(idx).clear();
        } catch (Exception e) {
            Coverage.hit(0x0402);
        }
    }

    public void opEvict(int poolIdx) {
        Coverage.hit(0x0500);
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        try {
            pools[idx].evict();
            Coverage.hit(0x0501);
        } catch (Exception e) {
            Coverage.hit(0x0502);
        }
    }

    public void opSetMaxTotal(int poolIdx, int value) {
        Coverage.hit(0x0600 | (poolIdx & 0xF));
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        int v = Math.max(1, Math.min(value & 0x7F, 128));
        int before = pools[idx].getMaxTotal();
        pools[idx].setMaxTotal(v);
        // Band-cross probes: did we widen or narrow the cap?
        if (v > before) Coverage.hit(0x0610 | idx);
        else if (v < before) Coverage.hit(0x0620 | idx);
        else Coverage.hit(0x0630 | idx);
        if (v > 64) Coverage.hit(0x0640 | idx);
        else if (v > 32) Coverage.hit(0x0650 | idx);
        else if (v > 8) Coverage.hit(0x0660 | idx);
        else Coverage.hit(0x0670 | idx);
    }

    public void opSetMaxIdle(int poolIdx, int value) {
        Coverage.hit(0x0700);
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        int v = Math.max(0, Math.min(value & 0x3F, 64));
        pools[idx].setMaxIdle(v);
        if (v == 0) Coverage.hit(0x0701);
        else if (v > 16) Coverage.hit(0x0702);
        else Coverage.hit(0x0703);
    }

    public void opSetMinIdle(int poolIdx, int value) {
        Coverage.hit(0x0800);
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        int v = Math.max(0, Math.min(value & 0x0F, 16));
        pools[idx].setMinIdle(v);
        if (v > pools[idx].getMaxIdle()) Coverage.hit(0x0801);
        else Coverage.hit(0x0802);
    }

    public void opPreparePool(int poolIdx) {
        Coverage.hit(0x0900);
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        try {
            pools[idx].preparePool();
            Coverage.hit(0x0901);
        } catch (Exception e) {
            Coverage.hit(0x0902);
        }
    }

    public void opAddObjects(int poolIdx, int count) {
        Coverage.hit(0x0A00);
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        int n = Math.max(0, Math.min(count & 0x1F, 16));
        try {
            pools[idx].addObjects(n);
            Coverage.hit(0x0A01);
        } catch (Exception e) {
            Coverage.hit(0x0A02);
        }
    }

    public void opSetTestOnBorrow(int poolIdx, boolean v) {
        Coverage.hit(0x0B00);
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        pools[idx].setTestOnBorrow(v);
        if (v) Coverage.hit(0x0B01); else Coverage.hit(0x0B02);
    }

    public void opSetBlockWhenExhausted(int poolIdx, boolean v) {
        Coverage.hit(0x0C00);
        int idx = Math.floorMod(poolIdx, FLEET_SIZE);
        pools[idx].setBlockWhenExhausted(v);
        if (v) Coverage.hit(0x0C01); else Coverage.hit(0x0C02);
    }

    public void opCrossPoolMove(int srcIdx, int dstIdx) {
        Coverage.hit(0x0D00);
        int s = Math.floorMod(srcIdx, FLEET_SIZE);
        int d = Math.floorMod(dstIdx, FLEET_SIZE);
        List<Widget> sb = lastBorrowed.get(s);
        if (sb.isEmpty()) {
            Coverage.hit(0x0D02);
            return;
        }
        Widget w = sb.remove(sb.size() - 1);
        // Return to src (we can't borrow into a different pool without
        // factory affinity, but we exercise both pools' state).
        try {
            pools[s].returnObject(w);
            try {
                Widget nw = pools[d].borrowObject();
                if (nw != null) {
                    lastBorrowed.get(d).add(nw);
                    Coverage.hit(0x0D01);
                }
            } catch (Exception e) {
                Coverage.hit(0x0D03);
            }
        } catch (Exception e) {
            Coverage.hit(0x0D04);
        }
    }

    /**
     * Tear-down for Mode-1 (full setup/teardown). Close every pool, drop the
     * borrowed-handle map. Mode-3 SKIPS this — its work is replaced by
     * {@code rollbackAll}.
     */
    public void teardown() {
        for (int i = 0; i < FLEET_SIZE; i++) {
            if (pools[i] != null) {
                try {
                    pools[i].close();
                } catch (Exception ignored) {}
                pools[i] = null;
                factories[i] = null;
            }
        }
        lastBorrowed.clear();
    }

    /** State checksum — used by correctness validation (Mode 1 vs 3). */
    public long stateChecksum() {
        long acc = 0;
        for (int i = 0; i < FLEET_SIZE; i++) {
            if (pools[i] == null) continue;
            acc = acc * 31 + pools[i].getNumActive();
            acc = acc * 31 + pools[i].getNumIdle();
            acc = acc * 31 + pools[i].getMaxTotal();
            acc = acc * 31 + pools[i].getMaxIdle();
            acc = acc * 31 + pools[i].getMinIdle();
            acc = acc * 31 + (pools[i].getBlockWhenExhausted() ? 1 : 0);
            acc = acc * 31 + (pools[i].getTestOnBorrow() ? 1 : 0);
            acc = acc * 31 + factories[i].madeCount();
            acc = acc * 31 + factories[i].destroyedCount();
        }
        return acc;
    }

    /** Per-component state breakdown for parity-debug. */
    public String stateBreakdown() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FLEET_SIZE; i++) {
            if (pools[i] == null) continue;
            sb.append("[p").append(i).append(" act=").append(pools[i].getNumActive())
                    .append(" idle=").append(pools[i].getNumIdle())
                    .append(" mt=").append(pools[i].getMaxTotal())
                    .append(" mi=").append(pools[i].getMaxIdle())
                    .append(" mn=").append(pools[i].getMinIdle())
                    .append(" bwe=").append(pools[i].getBlockWhenExhausted() ? 1 : 0)
                    .append(" tob=").append(pools[i].getTestOnBorrow() ? 1 : 0)
                    .append(" made=").append(factories[i].madeCount())
                    .append(" dst=").append(factories[i].destroyedCount())
                    .append("]");
        }
        return sb.toString();
    }

    // --- Widget + factory ---

    public static final class Widget {
        final int poolId;
        final int serial;
        final byte[] buf;
        long checksum;

        Widget(int poolId, int serial) {
            this.poolId = poolId;
            this.serial = serial;
            this.buf = new byte[4096];
            // Compute a checksum to make makeObject non-trivial.
            // {@link #WIDGET_INIT_ITERS} dials the cost: each iteration scans
            // the 4KB buffer once. With the default of 1, total fleet setup is
            // roughly 3 ms; at 50, it's roughly 50 ms.
            long c = 0;
            for (int iter = 0; iter < WIDGET_INIT_ITERS; iter++) {
                for (int i = 0; i < buf.length; i++) {
                    buf[i] = (byte) (i * 7 + poolId + serial + iter);
                    c = c * 31 + buf[i];
                }
            }
            this.checksum = c;
        }
    }

    public static final class WidgetFactory extends BasePooledObjectFactory<Widget> {
        private final int poolId;
        private int madeCount = 0;
        private int destroyedCount = 0;

        public WidgetFactory(int poolId) {
            this.poolId = poolId;
        }

        @Override
        public Widget create() {
            int s = ++madeCount;
            return new Widget(poolId, s);
        }

        @Override
        public PooledObject<Widget> wrap(Widget w) {
            return new DefaultPooledObject<>(w);
        }

        @Override
        public void destroyObject(PooledObject<Widget> p) throws Exception {
            destroyedCount++;
            super.destroyObject(p);
        }

        public int madeCount() { return madeCount; }
        public int destroyedCount() { return destroyedCount; }
    }
}
