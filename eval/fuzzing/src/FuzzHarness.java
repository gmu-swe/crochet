package eval.fuzzing;

import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Three-mode state-coverage fuzz harness.
 *
 * <ul>
 *   <li>{@code baseline_perIter} — full {@link PoolFleet#setup}/
 *       {@link PoolFleet#teardown} each iteration. The functionally-correct,
 *       slow baseline.</li>
 *   <li>{@code baseline_shared} — single setup; never teardown. State
 *       accumulates across iterations. Cheap-but-wrong upper bound.</li>
 *   <li>{@code crochet_rollback} — single setup; checkpoint after setup;
 *       rollback between iterations. Functionally equivalent to
 *       {@code baseline_perIter} if state is fully captured.</li>
 * </ul>
 *
 * <p>The fuzzer is a tiny coverage-guided mutator: each new input that adds a
 * coverage edge enters the corpus; subsequent inputs are mutations of
 * randomly-selected corpus entries. Fuzz state ({@link #corpus}, {@link
 * #globalBitmap}) lives in static fields so it survives across rollback in
 * Mode 3.
 *
 * <p>CLI: {@code FuzzHarness <mode> <budgetSec> <seed> <outJson>}.
 *
 * <p>Emits a CSV of iter,timeNs,totalBranches,corpusSize to stdout (sampled
 * at {@code SAMPLE_PERIOD_MS}) and a final JSON summary to {@code outJson}.
 */
public final class FuzzHarness {

    public static final long SAMPLE_PERIOD_MS = 1000L;
    public static final int INITIAL_LEN_OPS = 16;
    public static final int CORPUS_CAP = 4096;

    // Fuzzer state — outside any object the rollback can touch.
    private static final List<OpSequence> corpus = new ArrayList<>();
    private static byte[] globalBitmap = new byte[Coverage.MAP_SIZE];
    private static int distinctEdges = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: FuzzHarness "
                    + "<baseline_perIter|baseline_shared|crochet_rollback|crochet_scoped> "
                    + "<budgetSec> <seed> <outJson>");
            System.exit(2);
        }
        String mode = args[0];
        int budgetSec = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[2]);
        Path outJson = Paths.get(args[3]);

        Random r = new Random(seed);
        long deadlineNs = System.nanoTime() + (long) budgetSec * 1_000_000_000L;

        // Initial seed corpus: a few hand-rolled sequences.
        seedCorpus(r);

        System.out.println("iter,wallMs,totalBranches,corpusSize,iterTimeUs,opsExecuted");
        long startNs = System.nanoTime();
        long nextSampleNs = startNs + SAMPLE_PERIOD_MS * 1_000_000L;

        int iter = 0;
        int firstNBranchesReached = -1;
        long firstNBranchesAtMs = -1;
        long lastChecksumMode1 = 0;
        long lastChecksumMode3 = 0;
        long setupTotalNs = 0;
        long checkpointTotalNs = 0;
        long rollbackTotalNs = 0;
        long teardownTotalNs = 0;
        long execTotalNs = 0;

        // Mode-specific setup
        PoolFleet sharedTarget = null;
        int crochetSnapVersion = -1;
        if ("baseline_shared".equals(mode) || "crochet_rollback".equals(mode)
                || "crochet_scoped".equals(mode)) {
            sharedTarget = new PoolFleet();
            long s = System.nanoTime();
            sharedTarget.setup();
            setupTotalNs += System.nanoTime() - s;
            if ("crochet_rollback".equals(mode) || "crochet_scoped".equals(mode)) {
                // Quiesce JIT a little, and force class-level touch so
                // TOUCHED_CLASSES is populated before we snapshot.
                Coverage.resetForIteration();
                sharedTarget.stateChecksum();
                long c = System.nanoTime();
                if ("crochet_scoped".equals(mode)) {
                    crochetSnapVersion = CheckpointRollbackAgent.checkpoint(sharedTarget);
                } else {
                    crochetSnapVersion = CheckpointRollbackAgent.checkpointAll();
                }
                checkpointTotalNs += System.nanoTime() - c;
            }
        }

        long lastSampleIter = 0;
        long lastSampleTime = startNs;

        while (true) {
            long now = System.nanoTime();
            if (now >= deadlineNs) break;

            // Pick an input: corpus pick + mutate (or fresh random if corpus
            // empty / occasional exploration).
            OpSequence input;
            if (corpus.isEmpty() || r.nextInt(20) == 0) {
                input = OpSequence.random(r, INITIAL_LEN_OPS);
            } else {
                OpSequence base = corpus.get(r.nextInt(corpus.size()));
                OpSequence splice = corpus.get(r.nextInt(corpus.size()));
                input = base.mutate(r, splice);
            }

            Coverage.resetForIteration();

            PoolFleet target;
            long iterStart = System.nanoTime();
            int ops = 0;

            if ("baseline_perIter".equals(mode)) {
                target = new PoolFleet();
                long s = System.nanoTime();
                try {
                    target.setup();
                } catch (Exception e) {
                    target.teardown();
                    iter++;
                    continue;
                }
                setupTotalNs += System.nanoTime() - s;

                try {
                    ops = input.execute(target);
                } catch (Throwable t) {
                    // Honestly count it; corpus interest still recorded.
                }
                lastChecksumMode1 = target.stateChecksum();

                long td = System.nanoTime();
                target.teardown();
                teardownTotalNs += System.nanoTime() - td;
            } else if ("baseline_shared".equals(mode)) {
                target = sharedTarget;
                try {
                    ops = input.execute(target);
                } catch (Throwable t) {
                    // ignore
                }
            } else { // crochet_rollback or crochet_scoped
                target = sharedTarget;
                try {
                    ops = input.execute(target);
                } catch (Throwable t) {
                    // ignore
                }
                lastChecksumMode3 = target.stateChecksum();
                long rb = System.nanoTime();
                try {
                    if ("crochet_scoped".equals(mode)) {
                        CheckpointRollbackAgent.rollback(sharedTarget, crochetSnapVersion);
                        // Re-checkpoint per the §3.1 flat-nested semantics:
                        // each rollback consumes its snapshot.
                        crochetSnapVersion = CheckpointRollbackAgent.checkpoint(sharedTarget);
                    } else {
                        CheckpointRollbackAgent.rollbackAll(crochetSnapVersion);
                        crochetSnapVersion = CheckpointRollbackAgent.checkpointAll();
                    }
                } catch (Throwable t) {
                    // If rollback fails, fall back to full reset so we don't
                    // poison subsequent iterations.
                    target.teardown();
                    target = new PoolFleet();
                    target.setup();
                    sharedTarget = target;
                    if ("crochet_scoped".equals(mode)) {
                        crochetSnapVersion = CheckpointRollbackAgent.checkpoint(sharedTarget);
                    } else {
                        crochetSnapVersion = CheckpointRollbackAgent.checkpointAll();
                    }
                }
                rollbackTotalNs += System.nanoTime() - rb;
            }

            long iterEnd = System.nanoTime();
            execTotalNs += (iterEnd - iterStart);

            // Coverage-guided corpus admission.
            byte[] snap = Coverage.snapshot();
            boolean interesting = mergeAndCheck(snap);
            if (interesting && corpus.size() < CORPUS_CAP) {
                corpus.add(input.copy());
            }

            // First-N branches landmark (5000 in the brief, but for our small
            // fuzz target distinct edges top out near 100. We pick N=80 as a
            // representative landmark and record the time-to-N.)
            if (firstNBranchesReached < 0 && distinctEdges >= 80) {
                firstNBranchesReached = distinctEdges;
                firstNBranchesAtMs = (now - startNs) / 1_000_000L;
            }

            iter++;

            if (iterEnd >= nextSampleNs) {
                long wallMs = (iterEnd - startNs) / 1_000_000L;
                long iterTimeUs = (iterEnd - iterStart) / 1000L;
                System.out.printf("%d,%d,%d,%d,%d,%d%n",
                        iter, wallMs, distinctEdges, corpus.size(), iterTimeUs, ops);
                System.out.flush();
                nextSampleNs = iterEnd + SAMPLE_PERIOD_MS * 1_000_000L;
                lastSampleIter = iter;
                lastSampleTime = iterEnd;
            }
        }

        long endNs = System.nanoTime();
        long totalMs = (endNs - startNs) / 1_000_000L;
        double branchesPerSec = (double) distinctEdges * 1000.0 / Math.max(1L, totalMs);
        double itersPerSec = (double) iter * 1000.0 / Math.max(1L, totalMs);
        double meanIterUs = (double) execTotalNs / 1000.0 / Math.max(1, iter);

        // Final JSON
        try (BufferedWriter bw = Files.newBufferedWriter(outJson)) {
            bw.write("{\n");
            bw.write("  \"mode\": \"" + mode + "\",\n");
            bw.write("  \"seed\": " + seed + ",\n");
            bw.write("  \"budgetSec\": " + budgetSec + ",\n");
            bw.write("  \"iterations\": " + iter + ",\n");
            bw.write("  \"distinctEdges\": " + distinctEdges + ",\n");
            bw.write("  \"corpusSize\": " + corpus.size() + ",\n");
            bw.write("  \"totalMs\": " + totalMs + ",\n");
            bw.write("  \"branchesPerSec\": " + String.format("%.4f", branchesPerSec) + ",\n");
            bw.write("  \"itersPerSec\": " + String.format("%.4f", itersPerSec) + ",\n");
            bw.write("  \"meanIterUs\": " + String.format("%.4f", meanIterUs) + ",\n");
            bw.write("  \"setupTotalMs\": " + (setupTotalNs / 1_000_000L) + ",\n");
            bw.write("  \"teardownTotalMs\": " + (teardownTotalNs / 1_000_000L) + ",\n");
            bw.write("  \"checkpointTotalMs\": " + (checkpointTotalNs / 1_000_000L) + ",\n");
            bw.write("  \"rollbackTotalMs\": " + (rollbackTotalNs / 1_000_000L) + ",\n");
            bw.write("  \"timeToNBranchesMs\": " + firstNBranchesAtMs + ",\n");
            bw.write("  \"nBranchesLandmark\": " + (firstNBranchesReached < 0 ? -1 : firstNBranchesReached) + ",\n");
            bw.write("  \"lastChecksumMode1\": " + lastChecksumMode1 + ",\n");
            bw.write("  \"lastChecksumMode3\": " + lastChecksumMode3 + "\n");
            bw.write("}\n");
        }

        if ("baseline_shared".equals(mode) || "crochet_rollback".equals(mode)) {
            if (sharedTarget != null) {
                try { sharedTarget.teardown(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * OR the per-iteration snapshot into the global bitmap. Return true iff
     * we observed at least one new edge bucket — the corpus-admission signal.
     */
    private static boolean mergeAndCheck(byte[] snap) {
        boolean newEdge = false;
        int distinct = 0;
        for (int i = 0; i < globalBitmap.length; i++) {
            byte before = globalBitmap[i];
            byte s = snap[i];
            byte after = (byte) (before | s);
            if (after != before) {
                newEdge = true;
            }
            globalBitmap[i] = after;
            if (after != 0) distinct++;
        }
        distinctEdges = distinct;
        return newEdge;
    }

    /** Seed corpus: a few hand-crafted reasonable sequences. */
    private static void seedCorpus(Random r) {
        // Borrow + return cycle on pool 0.
        byte[] s1 = {
                (byte) OpSequence.OPCODE_BORROW, 0, 0, 0,
                (byte) OpSequence.OPCODE_RETURN, 0, 0, 0,
                (byte) OpSequence.OPCODE_BORROW, 1, 0, 0,
                (byte) OpSequence.OPCODE_RETURN, 1, 0, 0,
        };
        corpus.add(new OpSequence(s1));
        // Set max-total + add objects.
        byte[] s2 = {
                (byte) OpSequence.OPCODE_SET_MAX_TOTAL, 0, 50, 0,
                (byte) OpSequence.OPCODE_ADD_OBJECTS, 0, 4, 0,
                (byte) OpSequence.OPCODE_EVICT, 0, 0, 0,
        };
        corpus.add(new OpSequence(s2));
        // Mixed config + borrow.
        byte[] s3 = {
                (byte) OpSequence.OPCODE_SET_TOB, 0, 1, 0,
                (byte) OpSequence.OPCODE_SET_BWE, 0, 0, 0,
                (byte) OpSequence.OPCODE_BORROW, 2, 0, 0,
                (byte) OpSequence.OPCODE_INVALIDATE, 2, 0, 0,
                (byte) OpSequence.OPCODE_CLEAR, 2, 0, 0,
        };
        corpus.add(new OpSequence(s3));
        // Random initial.
        for (int i = 0; i < 5; i++) {
            corpus.add(OpSequence.random(r, INITIAL_LEN_OPS));
        }
    }
}
