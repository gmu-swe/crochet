# DaCapo benchmark compat status (prelim)

**Verdict:** baseline JDK runs every DaCapo benchmark cleanly; attaching
crochet-agent to any benchmark currently trips linkage errors in user
code before the benchmark reaches its payload. This was the expected
outcome — the user explicitly acknowledged optimization and compat
work ahead — but it's captured here so the issues are tracked.

## Setup

- DaCapo 23.11-chopin (~6.3 GB zip, extracted to `/tmp/dacapo/`).
  Launcher at `/tmp/dacapo/dacapo-23.11-chopin.jar`; data in `dat/`.
- OpenJDK 21 (Ubuntu).
- `crochet-agent-1.0.0-SNAPSHOT.jar` attached via `-javaagent:`.

## Baseline (no agent)

Times are wall-clock from `java -jar dacapo … -n 1 -s small`. Numbers
include JVM startup.

| benchmark | baseline |
|---|---|
| fop     | 1.54 s |
| sunflow | 0.94 s |
| luindex | 17.07 s |
| pmd     | 0.88 s |
| xalan   | 1.01 s |
| batik   | 2.40 s |
| h2      | 4.91 s |
| avrora  | 4.19 s |

All benchmarks reported `PASSED`.

## With crochet-agent attached

After fixing three immediate blockers (below), benchmarks still fail
with linkage errors inside user code. No benchmark reaches its payload.

### Blockers fixed inline

1. **Package-private class's `$$crochetLookup` not reflectively
   invocable.** `commons-cli.Util` (and many other utility classes) are
   package-private; their public-static synthetic members still fail
   `Method.invoke` from a caller outside the package. Fix:
   `setAccessible(true)` in `ClassMeta.resolveLookup`.

2. **Classes without `$$crochetLookup` tripping SF-helper generation.**
   Enums, interfaces, and already-instrumented classes skip lookup
   injection; SF-helper generation was hard-failing on them. Fix:
   `sfHelperFor` returns a no-op `NoopSFHelper` when
   `$$crochetLookup` can't be resolved.

3. **Boot- and platform-loader classes instrumented despite being out
   of reach for the agent's runtime classes.** `javax.xml.parsers.*`
   (in `java.xml` platform module) got instrumented and emitted
   references to `net.jonbell.crochet.runtime.CheckpointRollbackAgent`
   that their loader couldn't resolve, producing `VerifyError` on
   first use. Fix: `TransformerWrapper` now returns `null` for any
   class whose loader is the boot loader or the platform loader.

### Remaining classes of failure

After those fixes, benchmarks still fail at linkage. The patterns
observed:

- **VerifyError "Illegal type in constant pool"** on classes that
  were instrumented and whose loader delegates to app correctly but
  still can't link to the agent runtime (example:
  `org.apache.commons.logging.LogFactory` under fop). Unclear whether
  this is a genuine bytecode corruption by our emitter or a subtle
  classloader-visibility case (e.g., commons-logging's dynamic
  `Class.forName` paths that bypass the agent's classloader).

- **ClassNotFoundException on benchmark-internal classes**
  (`cck.util.Option$Str` for avrora, `org.h2.value.Value` for h2,
  `org.python.core.PyException` for jython, etc.). These are all
  classes that live in the benchmark's own jars. Hypothesis: an
  earlier class-definition failure in the same classloader marks it
  as broken; subsequent loads fail to find any class through that
  loader. Or: DaCapo's benchmark runner installs a URLClassLoader
  with child-first semantics that doesn't delegate to the agent's
  loader for `net.jonbell.crochet.*`.

### Ad-hoc diagnostics added

- `-Dcrochet.dumpClasses=true` writes every transformed class file to
  `/tmp/crochet-dump/` (useful for `javap -v` inspection).
- `-Dcrochet.verboseCompat=true` prints the root cause of SF-helper
  generation failures instead of swallowing it.

## What this means

The 17 scenario tests in `demo/scenarios/` still all pass on both
baseline and instrumented JDK. The core semantics are right. What
we're hitting is the universe of real-world third-party classloader
setups DaCapo exercises: plugin loaders, service loaders, reflection-
heavy framework code, dynamic proxies.

Fixing each category of failure means iterating on the same pattern:
find the linkage path that bypasses our instrumentation's
classloader assumptions, widen the skip list OR thread agent
visibility through the right modules. Galette spent considerable
effort on this — their instrumented-JDK approach (the jlink path we
already built) is the path forward because packing the runtime into
`java.base` eliminates the cross-loader-visibility problem entirely.

## Instrumented-JDK run (hypothesis partially validated)

Rebuilt the instrumented JDK (`/tmp/jdk-inst`) after the three compat
fixes above landed, and ran DaCapo against it. Results are mixed and
non-deterministic — which itself is diagnostic.

### First pass (fresh jdk-inst + fresh agent jar, `-n 1`)

| benchmark | `PASSED`? |
|---|---|
| fop     | ✓ 1391 ms |
| sunflow | ✓ 637 ms |
| luindex | ✓ 1664 ms |
| pmd     | ✓ 474 ms |
| xalan   | ✓ 426 ms |
| avrora  | ✓ 3989 ms |

6/6 PASSED, with wall-clock ~1.1–1.2× baseline. The instrumented-JDK
path does clear the classloader-visibility block that killed everything
on the vanilla JDK — the runtime now lives in `java.base` and is
reachable from every loader.

### Subsequent passes (same jdk-inst, same agent, no changes)

Re-running the same commands 15 minutes later produced **0/5 PASSED**.
Each benchmark fails with either:

- `VerifyError: (class: org/apache/commons/logging/LogFactory, method:
  releaseAll signature: ()V) Illegal type in constant pool` for fop.
- `ClassNotFoundException` on benchmark-internal classes rendered in
  internal-name form (`cck/util/Option$Str`, `org/sunflow/system/ui/
  SilentInterface`, `net/sourceforge/pmd/processor/MultiThreadProcessor`).
  The `/` separator in the CNFE message is a red flag — something is
  passing an internal name to `Class.forName` which expects dotted
  binary names. A likely culprit is `AnnotationStamper` writing a class
  reference in the wrong form in the `@CrochetInstrumented` marker.

The non-determinism itself is meaningful: it says the bug depends on
cache state (DaCapo unpacks jars into a scratch directory and reuses
across runs) and/or on which classes the JIT has already seen. Our
instrumentation is the variable. I haven't yet isolated which of our
changes introduced the regression — possible suspects are the
`NoopSFHelper` fallback, the `setAccessible(true)` in `resolveLookup`,
or the platform-loader skip widening in `TransformerWrapper`.

## Concrete next steps

1. **Bisect the compat regression.** Scenarios 1-17 still all pass
   consistently on both JDKs, so the regression only surfaces with
   DaCapo-scale code. Turn on `-Dcrochet.dumpClasses=true`, dump
   commons-logging.LogFactory from a fail run, `javap -v` the
   instrumented class, and compare against a hand-written expectation
   of what our FieldAdder + AnnotationStamper should have emitted.

2. **Audit `AnnotationStamper`'s class-reference emission.** The
   `cck/util/Option$Str` ClassNotFoundException with slash-separator
   suggests we're writing a binary type name as a Utf8 constant where
   we should be using `Ljava/path/To/Class;` descriptor form. This
   would be a straightforward fix.

3. **Investigate DaCapo scratch/data handling.** DaCapo caches unpacked
   benchmark data across runs. If the agent's instrumentation is
   cached and reloaded, state divergence between runs could explain
   the non-determinism. `--scratch-directory` gives us a handle to
   isolate each run.

4. **Only after 1-3 stabilize**: add `-n 5`/`--converge` for real
   steady-state timing, and start optimizing the `fastAccess` hot
   path. Expect overhead in the 1.05-1.5× range once compat is clean
   (the original CROCHET paper reported 1.06× avg on DaCapo 9.12-bach
   — roughly what we should aim for on Java 21 + DaCapo 23.11).
