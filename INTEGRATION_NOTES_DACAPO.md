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

## Suggested next steps

1. Re-run DaCapo against the **instrumented JDK** (`/tmp/jdk-inst`)
   produced by `crochet-instrument`, so the runtime lives in
   `java.base` and is visible from every classloader. The existing
   scenarios all pass on the instrumented JDK today; the hypothesis
   is DaCapo will too.
2. Instrument smaller DaCapo benchmarks first (`fop`, `xalan`,
   `avrora`) and iterate on compat until each passes.
3. Only then start measuring steady-state overhead with `-n 5` or
   `--converge`.
4. Optimize the hot path in `fastAccess` once we have a benchmark
   that exercises it.
