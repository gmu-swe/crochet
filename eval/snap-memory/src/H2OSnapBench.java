import java.util.*;
import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * Synthetic h2o-like benchmark for A.1 measurement.
 * H2O is an ML engine; it exercises large arrays, many object types,
 * and complex object graphs. We simulate this with:
 * - Large double[][] arrays (mimicking model parameters)
 * - ArrayList/HashMap structures (mimicking data frames/vectors)
 * - Many short-lived objects (mimicking H2O frame operations)
 *
 * Takes periodic checkpoints to measure fastAccess under a large
 * working-set workload.
 */
public class H2OSnapBench {
    static final int ROWS = 1000;
    static final int COLS = 50;
    static final int CLASSES = 50;

    // Simulate a data frame
    static double[][] frame = new double[ROWS][COLS];
    static List<Map<String, Object>> rows = new ArrayList<>();
    static Map<String, double[]> modelParams = new HashMap<>();

    public static void main(String[] args) throws Exception {
        int iters = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        int cpInterval = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        // Initialize model
        Random rng = new Random(42);
        for (int i = 0; i < ROWS; i++) {
            for (int j = 0; j < COLS; j++) {
                frame[i][j] = rng.nextGaussian();
            }
            Map<String, Object> row = new HashMap<>();
            row.put("id", i);
            row.put("label", i % CLASSES);
            row.put("weight", rng.nextDouble());
            rows.add(row);
        }

        for (int c = 0; c < CLASSES; c++) {
            double[] weights = new double[COLS];
            for (int j = 0; j < COLS; j++) weights[j] = rng.nextGaussian();
            modelParams.put("class_" + c, weights);
        }

        System.out.println("H2OSnapBench: warmup complete, " + ROWS + " rows, " + COLS + " cols, " + CLASSES + " classes");

        int checkpoints = 0;
        long t0 = System.nanoTime();

        for (int iter = 0; iter < iters; iter++) {
            // Simulate gradient update (mutates frame and modelParams)
            for (int i = 0; i < ROWS; i++) {
                int label = (Integer) rows.get(i).get("label");
                double weight = (Double) rows.get(i).get("weight");
                double[] classWeights = modelParams.get("class_" + (label % CLASSES));
                double dot = 0.0;
                for (int j = 0; j < COLS; j++) dot += frame[i][j] * classWeights[j];
                double grad = (dot - label) * weight * 0.001;
                for (int j = 0; j < COLS; j++) {
                    classWeights[j] -= grad * frame[i][j];
                    frame[i][j] += rng.nextGaussian() * 0.01; // mutation
                }
                // Update row metadata
                rows.get(i).put("score", dot);
                rows.get(i).put("iter", iter);
            }

            if (iter % cpInterval == 0) {
                CheckpointRollbackAgent.checkpointAll();
                checkpoints++;
            }
        }

        long elapsed = System.nanoTime() - t0;
        System.out.printf("H2OSnapBench: %d iters, %d checkpoints, %.2f ms%n",
                iters, checkpoints, elapsed / 1e6);
    }
}
