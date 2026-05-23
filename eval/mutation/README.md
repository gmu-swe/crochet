# eval/mutation — IV.1 mutation-testing speedup benchmark

Three-mode mutation-testing harness over Apache Commons Lang 3.12.0.
Drives the IV.1 case study (`CASE_STUDY-MUTATION.md`).

## Quick run

```bash
# 1. Build the runner (one-time, ~30s)
(cd runner && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q package)

# 2. Clone + compile commons-lang as the target (~1 min)
bash scripts/setup-target.sh

# 3. Make sure the agent jar + instrumented JDK are in place (see env.sh)
ls /home/jon/crochet/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar
ls /tmp/jdk-inst/bin/java

# 4. Replicated 3-run sweep across all three modes (~15 min total)
bash scripts/run-all.sh 3

# 5. Aggregate + parity audit
python3 scripts/aggregate.py
python3 scripts/parity-check.py
```

## Files

- `runner/`       — custom Java runner (Maven, `mvn package` produces `target/mutation-runner.jar`).
- `scripts/env.sh` — shared environment; override `JAVA_HOME`, `JDK_INST`,
  `AGENT_JAR`, `TARGET_CLASS`, `MUTANT_LIMIT` to retarget.
- `scripts/run-baseline-fork.sh`    — Mode 1: PIT default fork-per-mutant.
- `scripts/run-baseline-nofork.sh`  — Mode 2: same JVM, `redefineClasses` per mutant.
- `scripts/run-crochet.sh`          — Mode 3: same JVM + `checkpointAll/rollbackAll`.
- `scripts/run-all.sh`              — driver for replicated sweeps.
- `scripts/aggregate.py`            — emit the markdown table from `results/*.json`.
- `scripts/parity-check.py`         — verify kill-set match between modes.
- `results/`                        — JSON outputs (one summary line per run, one line per mutant).
- `CASE_STUDY-MUTATION.md`          — full writeup.

## Headline numbers (Fraction × FractionTest, 272 mutants, 3 runs each)

| mode             | median sweep | per-mutant |  peak RSS |
|------------------|-------------:|-----------:|----------:|
| baseline-fork    |     124.43s  |   466.0ms  |    n/a    |
| baseline-nofork  |      50.72s  |   186.5ms  |  1451 MB  |
| crochet          |      61.89s  |   227.5ms  |  1188 MB  |

**Speedup ratios:**

| comparison                       | ratio |
|----------------------------------|------:|
| baseline-fork / crochet          |  2.01×|
| baseline-fork / baseline-nofork  |  2.45×|
| baseline-nofork / crochet        |  0.82× (Crochet 22% slower) |

**Kill-set parity:** 267 / 267 mutants agree between PIT fork-mode and our runner
across all 6 single-JVM runs.
