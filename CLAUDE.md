# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Java-24 / Temurin port of CROCHET (ECOOP 2018) — checkpoint/rollback for the JVM via bytecode rewriting and klass-swap-based lazy heap traversal. Active integration branch is `java24-tdd` (PR #7 → `java24-port`). The original Java 8 code is preserved under `legacy/` for reference and is not wired into the Maven reactor.

Infrastructure pattern (jlink plugins, packer, Maven plugin) is a mechanical port from Galette (FSE 2025, BSD 3-Clause) — see `crochet-instrument/PORT_NOTES.md`.

## Build / run / test

`$JAVA_HOME` must point to a JDK whose version is ≥ `maven.compiler.release` (17). The project runs on Java 21 Temurin; some tasks (e.g. h2o compatibility) want Java 17.

```bash
# Build everything (installs all modules to ~/.m2)
mvn install -DskipTests

# Run unit tests (134 in crochet-agent + 142 in crochet-ttd + others = 287 total)
mvn -pl crochet-agent test
mvn test                                      # full reactor

# Run a single unit test
mvn -pl crochet-agent test -Dtest=CrochetTransformerTest#injectsLookupMethodIntoOrdinaryClass

# Build (or rebuild) an instrumented JDK — required for any end-to-end work
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
rm -rf /tmp/jdk-inst
java -jar crochet-instrument/target/crochet-instrument-2.0.0-SNAPSHOT.jar \
    "$JAVA_HOME" /tmp/jdk-inst

# Demo scenarios (25 numbered scenarios under demo/scenarios/*)
cd demo && bash run-all.sh                   # baseline JDK
cd demo && bash run-all.sh --instrumented    # uses /tmp/jdk-inst by default
INST_JDK=/path/to/other-jdk bash run-all.sh --instrumented

# Run the agent on an arbitrary program under the instrumented JDK
/tmp/jdk-inst/bin/java --add-reads java.base=jdk.unsupported \
    --add-reads java.base=java.logging \
    -javaagent:crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar \
    -jar whatever.jar

# Optional: build the JVMTI native agent for stack-frame root collection
(cd crochet-agent/src/main/native && make)
# Then attach via -agentpath alongside -javaagent:
/tmp/jdk-inst/bin/java --add-reads java.base=jdk.unsupported \
    --add-reads java.base=java.logging \
    -agentpath:crochet-agent/src/main/native/libcrochet-jvmti.so \
    -javaagent:crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar \
    -jar whatever.jar
```

Two `--add-reads` flags are required on the instrumented JDK:
- `java.base=jdk.unsupported` for `sun.misc.Unsafe` used throughout the runtime; `java.base` cannot declare `requires jdk.unsupported` itself.
- `java.base=java.logging` for `ExternalStateRegistry`'s `java.util.logging.Logger` access on the `checkpointAll` path. Without this, `IllegalAccessError` fires from `ExternalStateRegistry.<clinit>` once the runtime is packed into `java.base`.

For test frameworks or hosting containers, also set `-Dcrochet.checkpointAll.skipSystem=true` to skip the system-classloader walk in `checkpointAll`/`rollbackAll` (otherwise the walk recurses into instrumented `Class.getDeclaredMethod` machinery and overflows the stack).

**Optional JVMTI native agent (`libcrochet-jvmti.so`)**: closes the legacy parity gap on stack-frame root collection. When attached, `checkpointAll` / `rollbackAll` walk every active stack frame's local references and propagate to `CRIJInstrumented` ones — this is what lets `checkpointAll` capture an object held only by a method local. Without the native agent, `StackRoots.engaged` stays `false` and the stack walk is a no-op (heap-rooted checkpointing works exactly as before). Demo scenario 21-stack-roots tests both modes and degrades gracefully when the native isn't loaded.

## Runtime diagnostics (system properties)

- `-Dcrochet.dumpClasses=true` — write every transformed class file to `/tmp/crochet-dump/` for `javap -v` inspection.
- `-Dcrochet.dumpClassDir=/some/path` — override `/tmp/crochet-dump/` as the target directory.
- `-Dcrochet.verboseCompat=true` — print the cause of transform / SF-helper failures instead of swallowing (the `TransformerWrapper` catches `Throwable` silently by default so DaCapo digest-of-stderr checks stay clean).
- `-Dcrochet.verifyInstrumented=true` — opt in to the post-transform surface verifier (off by default; registered but gated on this flag for zero-cost when disabled).
- `-Dcrochet.traceTransform=true` — per-class transform timing to `/tmp/crochet-transform-trace.log`.
- `-Dcrochet.traceRuntime=true` — per-class `fastAccess` and `sfHelperFor` call counts to `/tmp/crochet-runtime-counts.log` on JVM shutdown. Both tracers are zero-cost when off.
- `-Dcrochet.reflectiveGraphFallback=true` — enable `ArrayRegistry.propagate*`'s reflective graph walk for uninstrumented referents (arrays buried inside JDK objects Gap 7 left alone). Default OFF; enabling it makes propagation more complete but substantially slower.
- `-Dcrochet.checkpointAll.skipSystem=true` — opt-out of thread-list / system classloader walks in `checkpointAll` / `rollbackAll`, for test frameworks or hosting containers that assume those roots are stable. Also recommended in instrumented-JDK mode to avoid system-classloader walk overflowing the stack via instrumented `Class.getDeclaredMethod`.
- `-Dcrochet.eagerClasses=foo.Bar,baz.Qux` — opt-in list of classes that should be eagerly added to `checkpointAll`'s root set (instead of waiting for the first `ClassMeta.of`). Composes with the `@CrochetEager` annotation.
- `-Dcrochet.ttd.debug=true` — verbose output from the TTD agent's transformers (off by default).
- `-Dcrochet.reflectionRewriter=true` — opt-in: rewrite reflective `Method.invoke` callsites so they participate in TTD record/replay (default OFF — Weld regression).

## Module layout

Eight reactor modules (see top-level `pom.xml`):

- **`crochet-agent`** — runtime support (`net.jonbell.crochet.runtime.*`) + bytecode pipeline (`net.jonbell.crochet.transform.*`) + `java.lang.instrument` agent (`net.jonbell.crochet.agent.*`). Shaded uber-jar relocates ASM into `edu.neu.ccs.prl.crochet.agent.shaded.asm`. The SAME jar is attached via `-javaagent` at runtime and packed into `java.base` at jlink time.
- **`crochet-instrument`** — jlink-plugin wrapper that invokes the agent's transformer on every `.class` in the base JDK image, then packs the runtime classes into `java.base`. Runnable via `java -jar crochet-instrument-*.jar $JAVA_HOME <out>`. Ports `InstrumentJLinkPlugin` + `PackJLinkPlugin` from Galette (credits in `PORT_NOTES.md`).
- **`crochet-maven-plugin`** — Maven wrapper around the same instrumenter, for projects that want it as a build step.
- **`crochet-junit5`** — JUnit-5 extension (`CrochetSetupExtension` + `@CrochetTrack`) that amortises agent setup across tests.
- **`crochet-compose-kit`** — Opt-in composition kit (`CrochetCompositionExtension` + `CrochetCompositionTest`) for layering Crochet under other Java agents. A.4 deliverable.
- **`crochet-ttd`** — Time-travel debugger primitive: `@TimeTravelBody`, CPS transform (`LineMarkerTransformer`), `ResumeFrame`, `NondetTransformer`, REPL. The substrate added in Phase B.
- **`crochet-debug`** — JDI bridge for an external debugger UI on top of `crochet-ttd`. The substrate added in Phase I.
- **`crochet-integration-tests`** — Failsafe / Surefire integration tests.

Additional read-only directories:

- **`demo/scenarios/`** — 25 small checkpoint/rollback programs, numbered 01-basic through 25-backstep-crochet-skip. Compiled and run by `demo/run-all.sh`. These are the fastest feedback loop. Scenarios 22–25 use the TTD substrate and only attach the `crochet-ttd` agent at runtime.
- **`eval/`** — reproduction harnesses for every number in `BENCHMARK.md`: `microbench/` (paper §5.1 Table 1), `dacapo/` (full 22-bench perf sweep), `dacapo-func/` (functional-only sweep). Each harness is self-contained and respects env-var overrides (`AGENT_JAR`, `JDK_INST`, `DACAPO_JAR`, ...).
- **`designs/gap*/`** — per-gap design docs from the port (Gaps 2–8 each cover one work-item addressed during the Java-21 migration).
- **`spikes/`** — standalone probes that validated specific mechanisms (hidden-class CP patching, JVMTI single-step).
- **`legacy/`** — original Java-8 CROCHET. Not built by Maven.

## The transform pipeline (what happens to every class)

`CrochetTransformer.transform(byte[])` builds this chain, top = reader-side, bottom = writer-side (writer is a `SafeClassWriter` that avoids `Class.forName` during frame computation):

```
JsrInliner                    (only if major < 50)
  CheckpointWrapper           — user classes only; @CrochetCheckpoint scope
  ReflectionRewriter          — user classes only; OFF by default (Weld regression)
  ByteBuddyClassLoaderPatcher — only for ByteBuddy's BACL/MPCL targets
  FieldAccessWrapper          — wraps GETFIELD/PUTFIELD (user + JDK post-Gap-7)
  ArrayCopyInterceptor        — redirects System.arraycopy
  StaticFieldRewriter         — fused noteStaticAccess pre-hook on GETSTATIC/PUTSTATIC
  ArrayAccessWrapper          — wraps xASTORE
  SharedLocalsProvider        — single LVS in the chain; lone owner
  FieldAdder                  — emits $$crochet* surface + CRIJInstrumented interface
                                + clinit registration (user classes only)
  LookupInjector              — emits public static $$crochetLookup() on every class
  AnnotationStamper           — stamps @CrochetInstrumented
  SafeClassWriter             — avoids Class.forName during frame computation
```

Key invariants that took multiple iterations to get right and must be preserved:

- **One LVS in the chain, delegate-only**. Visitors that need scratch locals hold a reference to `SharedLocalsProvider` and call `newLocal(Type)` / `sharedScratch(Type)` / `emitVarInsn(op, slot)`. No wrapper extends `LocalVariablesSorter`. Stacking multiple LVS produces cumulative local-index rewrites that break `COMPUTE_FRAMES`. Details: `SharedLocalsProvider.java` javadoc.
- **Scratch stores/loads bypass LVS remap**. `emitVarInsn` writes to the LVS's inherited `mv` directly because LVS keys its remap table on `(var, size)` (not type), so emitting through LVS would alias our OBJECT scratch with an original INT local sharing the same numeric index — exactly how h2's `Parser.parseCreate` broke before the fix.
- **JDK classes go through the full wrapper chain post-Gap-7**. The pre-Gap-7 minimal pipeline (no field/array/static wrappers) was abandoned because it left arrays buried inside JDK objects unreachable from `checkpointAll`. JDK classes still skip `CheckpointWrapper` / `ReflectionRewriter` (no `@CrochetCheckpoint` annotations on JDK methods; reflection rewriting inside java.base is out of scope) and skip the `<clinit>` registration emit (their `<clinit>` fires during JVM bootstrap when `CheckpointRollbackAgent` may not be fully wired; wrappers no-op until `RuntimeReady.markReady()` lifts the gate). JDK classes still get the `$$crochet*` surface + `CRIJInstrumented` interface.
- **`noteDirty` is best-effort**. F.1's dirty-bit is an optimization, not a correctness requirement: `fastAccess` treats a missing or unresolvable dirty handle as always-dirty (safe fallback — one extra shadow allocation per checkpoint, never a missed snapshot). The body of `noteDirty` is therefore wrapped in a broad `catch (Throwable)` so that reflective resolution failures on the packed JDK path (e.g. `ClassMeta.resolveLookup` reaching into `DirectMethodHandleAccessor.<init>`'s own instrumented PUTFIELD chain) cannot propagate into the calling code. User-class clinit publishes a Lookup directly to `CheckpointRollbackAgent.PUBLISHED_LOOKUP_MAP` via the 2-arg `registerInitializedClass(Class, Lookup)`; `ClassMeta.resolveLookup` reads that side table first and only falls back to reflection when nothing was published.
- **Skip-list in `CrochetTransformer.shouldSkip`** grew over time for good reason. Every entry is justified with the specific failure it prevents; read the comments before removing one. In particular: `$py`, `$ByteBuddy$`, `$HibernateProxy$`, `$$$view`, `_$$_Weld`, `jdk/internal/event/`, `jdk/jfr/`, `$$Lambda`, `/$Proxy`, `$$crochet`.

## Runtime hot-path architecture

Two call paths dominate:

- **`fastAccess(CRIJInstrumented)`** in `CheckpointRollbackAgent` — called from every `$$crochetAccess()` on an object whose klass is a Fast proxy. Three layers: (1) uncontended fast path if klass is already user (zero atomics / locks); (2) zero-version CAS short-circuit; (3) stripe-lock cold path for the actual snap install/restore, keyed by `System.identityHashCode(obj) & 0xff` via `FastAccessCoordinator` (256 stripes). Paper invariants I1 (unique v), I2 (monotone), sentinel `-v` handling are all preserved. See `FastAccessCoordinator.java` javadoc for the correctness argument.
- **`noteStaticAccess(Class)`** — fused static pre-hook emitted by `StaticFieldRewriter` for every GETSTATIC/PUTSTATIC of a user class. Early-returns when `VERSION_COUNTER.get() == 0` (no checkpoint ever taken); otherwise does a `ClassMeta` ClassValue lookup + volatile sfHelper read and materialises lazily via `sfHelperFor`. The fusion replaced an earlier `sfHelperFor(C).$$crochetAccess()` pair whose `INVOKEINTERFACE` defeated JIT devirtualisation.

The `VERSION_COUNTER` is a global `AtomicInteger` — the single source of truth for "has any checkpoint/rollback ever fired". Odd = checkpoint phase, even > 0 = rollback phase, 0 = pristine.

Injected instance fields on every non-skipped class: `private transient synthetic int $$crochetVersion` and `private transient synthetic Object $$crochetSnap`. The transient flag is load-bearing — h2o's `Schema.fillFromParms` and Java serialisation both respect it.

## When to reach for which doc

- `README.md` — user-facing overview + reproduction instructions for every experimental number.
- `BENCHMARK.md` — full performance evaluation: per-benchmark DaCapo table, optimization-round deltas, bottleneck traces, threats to validity. Read this before touching the transform pipeline — it captures which changes broke which workload and which ratios moved with which commit.
- `crochet-instrument/PORT_NOTES.md` — Galette→Crochet file mapping and renames.
- `designs/gap*/DESIGN.md` — per-gap design docs from the port.
- `crochet.pdf`, `fse25-galette.pdf` — the source papers (CROCHET 2018, Galette 2025).

## Committing

- Commits land on `java24-tdd` (PR #7 head) — Don't push without explicit user approval. `java24-port` is the stable merge target and only accepts merged PRs.
- `/scratch/` is gitignored — it's WildFly/Infinispan runtime state from running DaCapo benchmarks. Don't re-add it.
- Commit messages explain the *why*, tie changes to the specific failure they address, and cite measurements when performance is involved. Recent commits on this branch (`bf5bde6`, `4c7baff`, `f16b6d6`) are the current style template.
