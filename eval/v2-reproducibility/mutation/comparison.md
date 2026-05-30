| Mode | V.2 median (s) | V.2 min/max | V.2 N | Committed median (s) | Committed N | Δ% |
|---|---:|---:|---:|---:|---:|---:|
| baseline-fork | 123.43 | 123.23/123.80 | 3 | 124.43 | 3 | -0.8% |
| baseline-nofork | 52.74 | 52.50/52.92 | 3 | 50.72 | 3 | +4.0% |
| crochet | 64.91 | 64.37/65.25 | 3 | 61.89 | 3 | +4.9% |

### V.2 kill-set parity (sanity)
- baseline-fork: killed in [215], survived in [37] (over 3 reps)
- baseline-nofork: killed in [226], survived in [46] (over 3 reps)
- crochet: killed in [226], survived in [46] (over 3 reps)

### V.2 speedup ratios
- baseline-fork / crochet         = 1.90× (committed 2.01×)
- baseline-fork / baseline-nofork = 2.34× (committed 2.45×)
- baseline-nofork / crochet       = 0.81× (committed 0.82×)
