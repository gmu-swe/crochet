package eval.fuzzing;

import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * IV.3.c correctness validator. Runs a fixed sequence of fuzz inputs (same
 * seed) under both Mode 1 (full setup/teardown) and Mode 3 (Crochet rollback),
 * comparing the per-input {@link PoolFleet#stateChecksum} and per-input
 * coverage-bitmap hash. Mode 1 and Mode 3 must agree on every input.
 *
 * <p>CLI: {@code TraceParity <numInputs> <seed> <outJson>}.
 *
 * <p>Exit code: 0 on parity, 1 on any divergence. The harness records the
 * first 16 divergences for debugging.
 */
public final class TraceParity {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: TraceParity <numInputs> <seed> <outJson>");
            System.exit(2);
        }
        int n = Integer.parseInt(args[0]);
        long seed = Long.parseLong(args[1]);
        String out = args[2];

        Random r = new Random(seed);
        List<OpSequence> inputs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            inputs.add(OpSequence.random(r, 16));
        }

        // Mode 1: full setup/teardown each input.
        long[] checksumsMode1 = new long[n];
        long[] covHashMode1 = new long[n];
        String[] breakdownMode1 = new String[n];
        for (int i = 0; i < n; i++) {
            Coverage.resetForIteration();
            PoolFleet t = new PoolFleet();
            t.setup();
            inputs.get(i).execute(t);
            checksumsMode1[i] = t.stateChecksum();
            covHashMode1[i] = hashBitmap(Coverage.snapshot());
            if (i < 3) breakdownMode1[i] = t.stateBreakdown();
            t.teardown();
        }

        // Mode 3: setup once, checkpoint, rollback between inputs.
        long[] checksumsMode3 = new long[n];
        long[] covHashMode3 = new long[n];
        String[] breakdownMode3 = new String[n];
        PoolFleet sharedT = new PoolFleet();
        sharedT.setup();
        // Use scoped checkpoint on the PoolFleet root — its propagateRollback
        // walks the {@code factories[]} array and recursively touches each
        // factory's instance fields. checkpointAll alone leaves instances
        // dirty (lazy fastAccess restore only fires on next touch); see
        // CASE_STUDY-FUZZING.md §"Correctness".
        sharedT.stateChecksum();
        int snap = CheckpointRollbackAgent.checkpoint(sharedT);
        for (int i = 0; i < n; i++) {
            Coverage.resetForIteration();
            inputs.get(i).execute(sharedT);
            checksumsMode3[i] = sharedT.stateChecksum();
            covHashMode3[i] = hashBitmap(Coverage.snapshot());
            if (i < 3) breakdownMode3[i] = sharedT.stateBreakdown();
            CheckpointRollbackAgent.rollback(sharedT, snap);
            snap = CheckpointRollbackAgent.checkpoint(sharedT);
        }
        sharedT.teardown();

        // Print first-N breakdowns side-by-side to stderr for debug.
        for (int i = 0; i < Math.min(3, n); i++) {
            System.err.println("--- i=" + i + " ---");
            System.err.println(" M1: " + breakdownMode1[i]);
            System.err.println(" M3: " + breakdownMode3[i]);
        }

        // Compare.
        int divergeCount = 0;
        int covDivergeCount = 0;
        StringBuilder divs = new StringBuilder();
        for (int i = 0; i < n; i++) {
            boolean stateDiff = checksumsMode1[i] != checksumsMode3[i];
            boolean covDiff = covHashMode1[i] != covHashMode3[i];
            if (stateDiff) divergeCount++;
            if (covDiff) covDivergeCount++;
            if ((stateDiff || covDiff) && divs.length() < 4096) {
                if (divs.length() > 0) divs.append(",");
                divs.append("{\"i\":").append(i)
                        .append(",\"m1_state\":").append(checksumsMode1[i])
                        .append(",\"m3_state\":").append(checksumsMode3[i])
                        .append(",\"m1_cov\":").append(covHashMode1[i])
                        .append(",\"m3_cov\":").append(covHashMode3[i])
                        .append("}");
            }
        }

        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(out))) {
            bw.write("{\n");
            bw.write("  \"n\": " + n + ",\n");
            bw.write("  \"seed\": " + seed + ",\n");
            bw.write("  \"stateDivergences\": " + divergeCount + ",\n");
            bw.write("  \"coverageDivergences\": " + covDivergeCount + ",\n");
            bw.write("  \"divergences\": [" + divs + "]\n");
            bw.write("}\n");
        }

        if (divergeCount > 0 || covDivergeCount > 0) {
            System.err.println("FAIL: " + divergeCount + " state divergences, "
                    + covDivergeCount + " coverage divergences over " + n + " inputs");
            System.exit(1);
        }
        System.out.println("OK: " + n + " inputs traced identically (state + coverage)");
    }

    private static long hashBitmap(byte[] b) {
        long h = 1469598103934665603L;
        for (int i = 0; i < b.length; i++) {
            h ^= (b[i] & 0xFFL);
            h *= 1099511628211L;
        }
        return h;
    }
}
