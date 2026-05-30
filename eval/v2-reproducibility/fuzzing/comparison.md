| Mode | V.2 iter/s (mean ± sd) | V.2 branches (mean ± sd) | V.2 N | Committed iter/s | Committed branches | Δ% iter/s |
|---|---:|---:|---:|---:|---:|---:|
| `baseline_perIter` | 9.21 ± 0.54 | 298.7 ± 5.9 | 3 | 9.88 | 300.7 | -6.7% |
| `baseline_shared` | 26.35 ± 2.06 | 403.0 ± 2.0 | 3 | 26.44 | 403.0 | -0.4% |
| `crochet_scoped` | 20.64 ± 0.56 | 401.3 ± 1.5 | 3 | 18.94 | 400.7 | +9.0% |
| `crochet_rollback` | 20.87 ± 0.71 | 402.0 ± 2.6 | 3 | 19.85 | 403.0 | +5.1% |

### V.2 speedup ratios (mean iter/s)
- baseline_shared / baseline_perIter = 2.86× iter/s
- crochet_scoped / baseline_perIter = 2.24× iter/s
- crochet_rollback / baseline_perIter = 2.26× iter/s
