# Reproduction harnesses

Every experimental result in `BENCHMARK.md` is produced by one of these
three harnesses. They assume you have already built the agent (`mvn install
-DskipTests`) and the instrumented JDK (`/tmp/jdk-inst` by default — see the
top-level `README.md`).

| Directory | Purpose | Wall clock |
|---|---|---|
| `microbench/` | Paper §5.1 Table 1 replication (HashMap/TreeMap/LinkedHashMap/ConcurrentHashMap checkpoint/rollback microbenchmarks, 4 structures × 4 sizes × 3 configs × 20 iters = 960 measurements) | ~2 min |
| `dacapo/` | Full DaCapo 23.11-chopin sweep (22 benches × {base, inst} × 3 runs, 5–10 iters per run) | ~36 min |
| `dacapo-func/` | Functional-only DaCapo sweep (-n 1 -s small, PASS/FAIL by digest) | ~5 min |

Each harness is self-contained — no state written outside its own directory
(except for the standard `/tmp/jdk-inst` instrumented JDK produced by
`crochet-instrument`). All paths default to repo-relative locations; the
environment variables documented at the top of each script override.

Paper §5.1 uses all three harnesses combined:
- §5.1 Table 1 numbers: `microbench/`
- §5.1 "full DaCapo works" claim: `dacapo-func/`
- §5.1 DaCapo overhead table: `dacapo/`
