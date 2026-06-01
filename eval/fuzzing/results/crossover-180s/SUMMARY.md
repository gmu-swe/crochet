
## IV.3 Fuzz campaign summary

| mode | initIters | reps | iter/s (mean±sd) | branches (mean±sd) | iters total | setup ms | rollback ms |
|---|---|---|---|---|---|---|---|
| baseline_perIter | 1 | 1 | 320.36 ± 0.00 | 383.0 ± 0.0 | 57663 | 146308 | 0 |
| baseline_shared | 1 | 1 | 29.89 ± 0.00 | 402.0 ± 0.0 | 5381 | 327 | 0 |
| crochet_rollback | 1 | 1 | 22.15 ± 0.00 | 399.0 ± 0.0 | 3988 | 302 | 531 |
| crochet_scoped | 1 | 1 | 22.14 ± 0.00 | 399.0 ± 0.0 | 3986 | 323 | 138 |
| baseline_perIter | 10 | 1 | 49.04 ± 0.00 | 348.0 ± 0.0 | 8827 | 163524 | 0 |
| baseline_shared | 10 | 1 | 29.42 ± 0.00 | 401.0 ± 0.0 | 5295 | 326 | 0 |
| crochet_rollback | 10 | 1 | 29.70 ± 0.00 | 401.0 ± 0.0 | 5351 | 349 | 641 |
| crochet_scoped | 10 | 1 | 29.70 ± 0.00 | 401.0 ± 0.0 | 5351 | 337 | 140 |
| baseline_perIter | 30 | 1 | 16.40 ± 0.00 | 303.0 ± 0.0 | 2952 | 165750 | 0 |
| baseline_shared | 30 | 1 | 30.11 ± 0.00 | 402.0 ± 0.0 | 5426 | 374 | 0 |
| crochet_rollback | 30 | 1 | 22.55 ± 0.00 | 397.0 ± 0.0 | 4060 | 360 | 575 |
| crochet_scoped | 30 | 1 | 22.75 ± 0.00 | 397.0 ± 0.0 | 4095 | 357 | 127 |

## Speedup vs baseline_perIter (same initIters)

| initIters | mode | iter/s ratio | branches ratio |
|---|---|---|---|
| 1 | baseline_shared | 0.09× | 1.05× |
| 1 | crochet_scoped | 0.07× | 1.04× |
| 1 | crochet_rollback | 0.07× | 1.04× |
| 10 | baseline_shared | 0.60× | 1.15× |
| 10 | crochet_scoped | 0.61× | 1.15× |
| 10 | crochet_rollback | 0.61× | 1.15× |
| 30 | baseline_shared | 1.84× | 1.33× |
| 30 | crochet_scoped | 1.39× | 1.31× |
| 30 | crochet_rollback | 1.38× | 1.31× |

Branches-over-time CSVs written to results/crossover-180s/branches-over-time-*.csv
