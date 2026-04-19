import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * Replication driver for Table 1 of the CROCHET 2018 paper (Bell &amp; Pina,
 * ECOOP). Runs the paper's three-step fill/checksum/workload protocol on one
 * of four JDK collections (HashMap / TreeMap / LinkedHashMap /
 * ConcurrentHashMap) at a user-supplied SIZE in {10, 25, 50, 100}, with one
 * of three configurations:
 *
 * <ul>
 *   <li>{@code baseline} — plain stock JVM; no agent attached. Sanity-checks
 *       that the checksum function is deterministic across a single map state.
 *   <li>{@code crochet} — instrumented JDK + agent attached. No
 *       checkpoint/rollback is invoked. Measures steady-state instrumentation
 *       overhead (paper's "CROCHET" column). Asserts identity-hash preservation
 *       through instrumented access: checksum computed twice on the same map
 *       must be equal.
 *   <li>{@code crochet_cp} — checkpoint the map between step 2 (checksum) and
 *       step 3 (workload), then roll back after the workload. Re-verify the
 *       checksum post-rollback and assert equality against the pre-checkpoint
 *       value. Measures checkpoint/rollback round-trip cost (paper's
 *       "CROCHET_CP" column). This is the paper's RQ4 correctness criterion.
 * </ul>
 *
 * <p>Protocol, following §5.1 verbatim:
 * <ol>
 *   <li><b>Fill</b>: insert {@code SIZE} entries. Keys are random ints in
 *       {@code [0, SIZE*2]} (range is twice the fill count, so some key
 *       collisions happen). Values are freshly-allocated {@link FillValue}
 *       objects, which carry nothing but an identity hash.
 *   <li><b>Checksum</b>: XOR every key's {@link Integer#hashCode} with every
 *       value's {@link System#identityHashCode}. Computed once here.
 *   <li><b>Workload</b>: perform {@code SIZE} operations picked at random
 *       from {@code {get, put, delete, replace}}. Value args to put / replace
 *       are fresh {@code FillValue} allocations. Workload keys are drawn from
 *       the same {@code [0, SIZE*2]} range so ~50% miss by construction.
 * </ol>
 *
 * <p>Timing boundaries: we time steps 1 + 2 + 3 (the "single execution of
 * work" in the paper's Caliper setup) together, inclusive of checkpoint /
 * rollback where applicable for the {@code crochet_cp} config. This matches
 * the paper's measurement window. We report a per-iteration wall-clock time
 * in microseconds, CSV-formatted to stdout for the harness to collect.
 *
 * <p>CLI: {@code java MicroBench <ds> <size> <config> [<iterations>]} where
 * {@code ds} is one of {@code hm|tm|lhm|chm}, {@code size} is a positive int,
 * {@code config} is one of {@code baseline|crochet|crochet_cp}, and
 * {@code iterations} defaults to 20.
 */
public final class MicroBench {

    private static final String[] VALID_DS = {"hm", "tm", "lhm", "chm"};
    private static final String[] VALID_CONFIG = {"baseline", "crochet", "crochet_cp"};

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.err.println("usage: MicroBench <hm|tm|lhm|chm> <size> "
                    + "<baseline|crochet|crochet_cp> [<iterations>]");
            System.exit(2);
        }
        String ds = args[0];
        int size = Integer.parseInt(args[1]);
        String cfg = args[2];
        int iterations = args.length == 4 ? Integer.parseInt(args[3]) : 20;
        validate(ds, VALID_DS, "ds");
        validate(cfg, VALID_CONFIG, "config");
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }

        // Derive a reproducible but per-(ds, size) seed so different
        // configurations on the same (ds, size) see the SAME key / op
        // sequence. The correctness protocol relies on this: the checksum
        // depends on the exact key stream, so comparing baseline vs crochet
        // vs crochet_cp timings requires identical work.
        long baseSeed = seedFor(ds, size);

        // CSV header (stdout). Consumed by run.sh / the aggregator.
        System.out.println("ds,size,config,iter,time_us,checksum_ok,pre_checksum,post_checksum");

        int failCount = 0;
        for (int iter = 0; iter < iterations; iter++) {
            long iterSeed = baseSeed ^ (iter * 0x9E3779B97F4A7C15L);
            boolean ok = runOne(ds, size, cfg, iter, iterSeed);
            if (!ok) {
                failCount++;
            }
        }
        if (failCount > 0) {
            // Don't exit 1 — we want all 20 iterations emitted so the harness
            // can still aggregate timings. Report the failure count on stderr
            // so the harness's watchdog can flag it prominently.
            System.err.println("CHECKSUM FAILURES: ds=" + ds + " size=" + size
                    + " cfg=" + cfg + " fail=" + failCount + "/" + iterations);
        }
    }

    private static void validate(String v, String[] valid, String name) {
        for (String s : valid) {
            if (s.equals(v)) {
                return;
            }
        }
        throw new IllegalArgumentException(name + " must be one of " + String.join("|", valid));
    }

    private static long seedFor(String ds, int size) {
        // Deterministic: fixed 64-bit string XORed with ds / size hash.
        long a = 0xBE112A20180FFL;
        a ^= (long) ds.hashCode() * 0x100000001B3L;
        a ^= (long) size * 0x9E3779B97F4A7C15L;
        return a;
    }

    /** Returns true iff the checksum assertion passed for this iteration. */
    private static boolean runOne(String ds, int size, String cfg, int iter, long seed) {
        Random r = new Random(seed);
        // Pre-generate key stream + value stream so the workload phase sees
        // the same inputs regardless of config. The map is what differs, but
        // the work it does is identical across configs.
        int keyRange = size * 2;
        int[] fillKeys = new int[size];
        FillValue[] fillValues = new FillValue[size];
        for (int i = 0; i < size; i++) {
            fillKeys[i] = r.nextInt(keyRange);
            fillValues[i] = new FillValue();
        }
        int[] wlOps = new int[size];
        int[] wlKeys = new int[size];
        FillValue[] wlValues = new FillValue[size];
        for (int i = 0; i < size; i++) {
            wlOps[i] = r.nextInt(4);
            wlKeys[i] = r.nextInt(keyRange);
            // Pre-allocate even for get/delete so allocation cost is the
            // same across operations (matches the paper's "values are
            // newly-created objects" clause).
            wlValues[i] = new FillValue();
        }

        Map<Integer, FillValue> map = freshMap(ds);

        long t0 = System.nanoTime();

        // Step 1: fill.
        for (int i = 0; i < size; i++) {
            map.put(fillKeys[i], fillValues[i]);
        }

        // Step 2: checksum (pre-checkpoint if applicable).
        int preChecksum = checksum(map);

        boolean checksumOk;
        int postChecksum;

        if ("crochet_cp".equals(cfg)) {
            // Step 2.5: checkpoint the map (per-map, paper §5.1 Table 1).
            int v = CheckpointRollbackAgent.checkpoint(map);

            // Step 3: workload.
            runWorkload(map, wlOps, wlKeys, wlValues);

            // Rollback and re-checksum.
            CheckpointRollbackAgent.rollback(map, v);
            postChecksum = checksum(map);
            checksumOk = (preChecksum == postChecksum);
        } else {
            // baseline / crochet: sanity-check identity-hash preservation by
            // computing the checksum a SECOND time on the just-filled map.
            // If the instrumented $$crochetAccess hook or the klass-swap
            // alters System.identityHashCode, this fails. Then run the
            // workload (which IS the timed work, same as crochet_cp).
            int sanity = checksum(map);
            checksumOk = (preChecksum == sanity);
            runWorkload(map, wlOps, wlKeys, wlValues);
            postChecksum = sanity;
        }

        long t1 = System.nanoTime();
        long timeUs = (t1 - t0) / 1000L;

        System.out.printf("%s,%d,%s,%d,%d,%s,%d,%d%n", ds, size, cfg, iter, timeUs,
                checksumOk ? "OK" : "FAIL", preChecksum, postChecksum);
        return checksumOk;
    }

    private static Map<Integer, FillValue> freshMap(String ds) {
        switch (ds) {
            case "hm": return new HashMap<>();
            case "tm": return new TreeMap<>();
            case "lhm": return new LinkedHashMap<>();
            case "chm": return new ConcurrentHashMap<>();
            default: throw new IllegalStateException("ds=" + ds);
        }
    }

    /**
     * XOR of {@code (key.hashCode() ^ System.identityHashCode(value))} across
     * every entry. Deterministic for a given map state, irrespective of
     * iteration order — XOR is commutative.
     */
    private static int checksum(Map<Integer, FillValue> map) {
        int acc = 0;
        for (Map.Entry<Integer, FillValue> e : map.entrySet()) {
            acc ^= e.getKey().hashCode() ^ System.identityHashCode(e.getValue());
        }
        return acc;
    }

    /**
     * SIZE operations drawn from {get, put, delete, replace} over the map.
     * The paper's ~50% hit-rate comes from key range = 2 * fill range. For
     * correctness of the rollback assertion, what matters is that step 3
     * mutates the map however it likes — rollback must return it to the
     * pre-step-3 state regardless.
     */
    private static void runWorkload(Map<Integer, FillValue> map, int[] ops, int[] keys,
                                     FillValue[] vals) {
        for (int i = 0; i < ops.length; i++) {
            int op = ops[i];
            int k = keys[i];
            FillValue v = vals[i];
            switch (op) {
                case 0: // get
                    map.get(k);
                    break;
                case 1: // put
                    map.put(k, v);
                    break;
                case 2: // delete
                    map.remove(k);
                    break;
                case 3: // replace
                    map.replace(k, v);
                    break;
                default:
                    throw new IllegalStateException("op=" + op);
            }
        }
    }
}
