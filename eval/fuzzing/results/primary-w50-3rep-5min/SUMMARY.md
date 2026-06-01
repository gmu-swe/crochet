
## IV.3 Fuzz campaign summary

| mode | initIters | reps | iter/s (mean±sd) | branches (mean±sd) | iters total | setup ms | rollback ms |
|---|---|---|---|---|---|---|---|
| baseline_perIter | 50 | 3 | 9.88 ± 0.05 | 300.7 ± 5.9 | 2963 | 279546 | 0 |
| baseline_shared | 50 | 3 | 26.44 ± 2.05 | 403.0 ± 2.0 | 7935 | 401 | 0 |
| crochet_rollback | 50 | 3 | 19.85 ± 1.05 | 403.0 ± 2.6 | 5956 | 401 | 787 |
| crochet_scoped | 50 | 3 | 18.94 ± 0.54 | 400.7 ± 2.1 | 5682 | 411 | 175 |

## Speedup vs baseline_perIter (same initIters)

| initIters | mode | iter/s ratio | branches ratio |
|---|---|---|---|
| 50 | baseline_shared | 2.68× | 1.34× |
| 50 | crochet_scoped | 1.92× | 1.33× |
| 50 | crochet_rollback | 2.01× | 1.34× |

Branches-over-time CSVs written to results/primary-w50-3rep-5min/branches-over-time-*.csv
