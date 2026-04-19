# CROCHET — Checkpoint ROllbaCk via lightweight HEap Traversal

CROCHET is a checkpoint/rollback system for the JVM. It takes a snapshot of
some objects in a running program and lets you restore them later, driven
entirely by bytecode rewriting — no fork, no core-dump, no persistent heap.
Snapshots are *lazy*: nothing is copied at checkpoint time. CROCHET swaps
the caller's klass pointer for a small proxy, and the proxy copies each
field on the first access *after* the checkpoint, then swaps itself back.
A rollback runs the same protocol in reverse.

This branch (`java24-port`) is a port to Java 21+ Temurin, built on the
Galette (FSE 2025) jlink/Maven plugin infrastructure. The original Java 8
implementation from the 2018 paper is preserved under `legacy/` for
reference and is not wired into the Maven reactor.

Published work: J. Bell and L. Pina. *CROCHET: Checkpoint and Rollback via
Lightweight Heap Traversal on Stock JVMs.* ECOOP 2018. (See `crochet.pdf`.)

## Requirements

- **JDK 21** — Temurin 21 or OpenJDK 21. Set `JAVA_HOME` before building.
- **Maven 3.8+**.
- **(optional) JDK 17** — only needed if you want to run DaCapo's `h2o`
  benchmark, which caps at Java 17.
- **(optional) `g++` + JDK headers** — to build the native JVMTI agent that
  collects stack-frame roots during `checkpointAll`. Without it, CROCHET
  still runs; it just can't see references that live only on the stack (see
  §"Stack roots" below).

## Build

```bash
# from the repo root, with JAVA_HOME pointing at a JDK 21
mvn install -DskipTests

# (optional) build the native JVMTI agent
(cd crochet-agent/src/main/native && make)
```

`mvn install` produces:

- `crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar` — the runtime
  agent and the class-file transformer in a single shaded uber-jar.
- `crochet-instrument/target/crochet-instrument-1.0.0-SNAPSHOT.jar` — the
  jlink plugin wrapper that bakes the transformer into a JDK image.
- `crochet-maven-plugin/target/...` — the same instrumenter as a Maven
  plugin, for projects that want it at build time.

### One-time: build an instrumented JDK image

CROCHET rewrites user classes at load time via the `-javaagent` hook, but it
also needs the `$$crochet*` surface on JDK classes (to follow heap
references that cross into `java.base`). The `crochet-instrument` jlink
plugin runs the transformer over every class in a stock JDK and packs the
runtime classes into `java.base`:

```bash
rm -rf /tmp/jdk-inst
java -jar crochet-instrument/target/crochet-instrument-1.0.0-SNAPSHOT.jar \
    "$JAVA_HOME" /tmp/jdk-inst
```

You need a fresh instrumented JDK whenever you rebuild `crochet-agent` with
a change to the transform pipeline or the runtime surface.

## Using CROCHET

Call the agent's static API:

```java
import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

int v = CheckpointRollbackAgent.getNewVersionForCheckpoint();
CheckpointRollbackAgent.checkpoint(rootObject, v);   // snapshot reachable objects lazily
// ... mutate ...
CheckpointRollbackAgent.rollback(rootObject, v);     // restore from the snapshot

// Or take a global snapshot over all threads + system roots:
int v = CheckpointRollbackAgent.checkpointAll();
// ... mutate ...
CheckpointRollbackAgent.rollbackAll(v);
```

Run your program with the agent attached on the instrumented JDK:

```bash
/tmp/jdk-inst/bin/java \
    --add-reads java.base=jdk.unsupported \
    -javaagent:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    -cp .:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    YourProgram
```

`--add-reads java.base=jdk.unsupported` is required because the packed
runtime references `sun.misc.Unsafe` and `java.base` cannot statically
declare `requires jdk.unsupported`.

### Stack roots (optional JVMTI agent)

`checkpointAll` sweeps the heap starting from static fields and thread
locals it can enumerate from Java. It does *not* see references that live
only in JVM stack frames (method locals and operand stack slots). The
optional native agent fills this gap:

```bash
/tmp/jdk-inst/bin/java \
    --add-reads java.base=jdk.unsupported \
    -agentpath:crochet-agent/src/main/native/libcrochet-jvmti.so \
    -javaagent:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    -cp .:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    YourProgram
```

When loaded, `StackRoots.engaged` flips to true, and `checkpointAll` walks
every active frame's local references on each Java thread via JVMTI. The
overhead is negligible when no checkpoint is active.

### Diagnostics (system properties)

- `-Dcrochet.dumpClasses=true` — dump every transformed class to
  `/tmp/crochet-dump/` for `javap -v` inspection.
- `-Dcrochet.verboseCompat=true` — print the cause of transform / SF-helper
  failures instead of swallowing them.
- `-Dcrochet.traceTransform=true` — per-class transform timing to
  `/tmp/crochet-transform-trace.log`.
- `-Dcrochet.traceRuntime=true` — per-class `fastAccess` / `sfHelperFor`
  call counts to `/tmp/crochet-runtime-counts.log` on JVM shutdown.

## Reproducing the experimental results

Every number in `BENCHMARK.md` is produced by one of the scripts under
`eval/`. Prerequisites: built agent + instrumented JDK as above. The DaCapo
harnesses expect the benchmark jar at `/tmp/dacapo/dacapo-23.11-chopin.jar`
(download from https://dacapobench.org/); override with `DACAPO_JAR=...`.

| What | Command | Expected | Wall clock |
|---|---|---|---|
| 21 checkpoint/rollback demo scenarios (baseline) | `cd demo && bash run-all.sh` | `21 passed, 0 failed` | ~1 min |
| 21 checkpoint/rollback demo scenarios (instrumented) | `cd demo && bash run-all.sh --instrumented` | `21 passed, 0 failed` | ~1 min |
| 35 agent unit tests | `mvn -pl crochet-agent test` | `Tests run: 35, Failures: 0, Errors: 0` | ~30 s |
| Paper §5.1 microbench (4 structures × 4 sizes × 3 configs, 320 iters) | `bash eval/microbench/run.sh && eval/microbench/aggregate.py` | 320/320 checksums OK; geomean CROCHET ≈ paper's 1.07x | ~2 min |
| DaCapo 23.11-chopin functional sweep (22 benches, -n 1 -s small) | `bash eval/dacapo-func/run.sh` | `22 passed, 0 failed` | ~5 min |
| DaCapo 23.11-chopin performance sweep (22 × {base, inst} × 3 runs) | `bash eval/dacapo/driver.sh && eval/dacapo/parse_results.py eval/dacapo/results/results.csv` | Median overhead **1.04x** | ~36 min |

Each harness defaults to the repo-local agent jar and `/tmp/jdk-inst`; each
respects environment-variable overrides (`AGENT_JAR`, `JDK_INST`, `BASE_JDK`,
`DACAPO_JAR`, `RUNS`). See the header of each script for the full list.

For h2o (DaCapo), also produce a Java-17 instrumented JDK:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  java -jar crochet-instrument/target/crochet-instrument-1.0.0-SNAPSHOT.jar \
       /usr/lib/jvm/java-17-openjdk-amd64 /tmp/jdk-inst-j17
```

The full analysis — per-benchmark overhead table, optimization-round
deltas, bottleneck traces, threats to validity — is in `BENCHMARK.md`.

## Repository layout

- `crochet-agent/` — runtime + bytecode pipeline + `java.lang.instrument`
  agent. One shaded jar, used both as `-javaagent` and as the jlink-baked
  runtime.
- `crochet-instrument/` — jlink-plugin wrapper that instruments a stock
  JDK image. Ported from Galette; see `crochet-instrument/PORT_NOTES.md`.
- `crochet-maven-plugin/` — same instrumenter as a Maven build step.
- `crochet-integration-tests/` — Failsafe/Surefire integration tests.
- `demo/scenarios/` — 21 small checkpoint/rollback programs, runnable via
  `demo/run-all.sh`. The fastest feedback loop.
- `eval/` — reproduction harnesses (microbench + DaCapo perf + functional).
- `designs/gap*/` — per-gap design docs from the port.
- `legacy/` — original Java-8 CROCHET (not built).
- `BENCHMARK.md` — full performance evaluation and bottleneck analysis.
- `CLAUDE.md` — orientation notes for future maintainers (architectural
  invariants, hot-path structure, pipeline ordering).

## Authors and credits

Original: [Jonathan Bell](https://jonbell.net) and
[Luís Pina](https://luispina.me). Java-21 port: Jonathan Bell.

Galette (FSE 2025) provided the jlink-plugin / packer infrastructure this
port is built on. See `fse25-galette.pdf` and the attribution in
`crochet-instrument/PORT_NOTES.md`.

## License

MIT License. Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including without
limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to
whom the Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
