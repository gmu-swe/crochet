# IV.3 — State-Coverage Fuzzing Benchmark

This directory holds the IV.3 evaluation: a coverage-guided fuzz harness that
exercises a stateful target (Apache Commons Pool 2) under four execution
modes, measuring the throughput advantage of using Crochet
checkpoint/rollback as a setup/teardown replacement.

Read [`CASE_STUDY-FUZZING.md`](CASE_STUDY-FUZZING.md) for the narrative
writeup. This README is the operational reference.

## Layout

```
eval/fuzzing/
├── README.md                       # this file
├── CASE_STUDY-FUZZING.md           # narrative writeup
├── src/                            # harness sources
│   ├── Coverage.java               # 64K-edge AFL-style bucket bitmap
│   ├── PoolFleet.java              # stateful fuzz target (16 GenericObjectPools)
│   ├── OpSequence.java             # byte[] fuzz-input rep + havoc mutator
│   ├── FuzzHarness.java            # 4-mode driver (baseline_perIter,
│   │                               #   baseline_shared, crochet_scoped,
│   │                               #   crochet_rollback)
│   └── TraceParity.java            # IV.3.c correctness validator
├── scripts/
│   ├── build.sh                    # compile against agent jar + commons-pool2
│   ├── run-one.sh                  # one mode + one rep
│   ├── run-all.sh                  # full sweep (4 modes × N reps × M init levels)
│   ├── aggregate.py                # JSONs → summary table + over-time CSVs
│   └── plot.py                     # over-time CSVs → branches-vs-time PNG
└── results/                        # per-campaign output (gitignored except summaries)
```

## Quick start

```bash
# 1. Build the harness (needs crochet-agent built and instrumented JDK at /tmp/jdk-inst).
bash scripts/build.sh

# 2. Smoke-test (60s wall, 4 modes × 1 rep at WIDGET_INIT_ITERS=50).
BUDGET_SEC=15 REPS=1 ITER_LEVELS="50" RUN_TAG=smoke \
    bash scripts/run-all.sh

# 3. Summarise.
python3 scripts/aggregate.py results/smoke

# 4. Plot.
python3 scripts/plot.py results/smoke 50
```

For the full benchmark used in the case study:

```bash
BUDGET_SEC=600 REPS=3 ITER_LEVELS="50" RUN_TAG=primary \
    bash scripts/run-all.sh
python3 scripts/aggregate.py results/primary > results/primary/SUMMARY.md
python3 scripts/plot.py results/primary 50
```

## The four modes

| Mode | Per-iter cost | Per-iter teardown | Correctness |
|---|---|---|---|
| `baseline_perIter` | `new PoolFleet(); setup(); execute(); teardown()` | full | gold reference |
| `baseline_shared`  | `execute()` against one persistent target | none | accumulates state |
| `crochet_scoped`   | `execute(); rollback(target); reCheckpoint(target)` | scoped rollback | partial (see §correctness) |
| `crochet_rollback` | `execute(); rollbackAll(); reCheckpointAll()` | global rollback | partial (see §correctness) |

## WIDGET_INIT_ITERS dial

`PoolFleet`'s factory hashes its 4KB buffer this many times in
`makeObject`. Default `1` → ~3 ms fleet setup. `50` → ~50 ms. Bumping this
shifts the setup-vs-rollback crossover and is the main knob for
characterising "when does Crochet help".

```bash
ITER_LEVELS="1 10 50" RUN_TAG=crossover bash scripts/run-all.sh
```

## Inputs and outputs

Per-run outputs in `results/<RUN_TAG>/`:
- `<mode>-w<iters>-s<seed>.csv`    — periodic samples (1s) of
  `iter,wallMs,totalBranches,corpusSize,iterTimeUs,opsExecuted`
- `<mode>-w<iters>-s<seed>.json`   — final summary
- `<mode>-w<iters>-s<seed>.log`    — Java stderr (warnings, etc.)

After `aggregate.py`:
- `branches-over-time-<mode>-w<iters>.csv` — mean ± stddev branches at 1-sec resolution

After `plot.py`:
- `branches-over-time-w<iters>.png`        — overlaid curves

## Reproduction

The harness is fully seeded — `seed` is the input-generator's RNG seed. We
use `seed = 100*rep + 7` for reps 1..N so reruns under the same `REPS`
hit the same input streams.

Per-iter coverage is held in the static `Coverage.BUCKETS` array
intentionally OUTSIDE the rollback surface so the fuzzer's accumulated
knowledge survives across `rollbackAll`. See `Coverage.java` and the
"how does the fuzzer remember?" section in the case study.
