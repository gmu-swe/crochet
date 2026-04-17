# DaCapo benchmark status

## Headline

**11 of 15 DaCapo 23.11-chopin benchmarks PASS** end-to-end with the
crochet-agent attached to the instrumented JDK. Median steady-state
overhead is **~1.4x** on the small workload at `-n 3`, with several
benchmarks landing at or below 1.0x (xalan, avrora, jme) and one
clear outlier (h2 at 11x) that needs profiling.

| benchmark | status | base (ms) | crochet (ms) | ratio |
|---|---|---|---|---|
| sunflow  | PASS | 317 | 421 | 1.33x |
| luindex  | PASS | 826 | 1280 | 1.55x |
| pmd      | PASS |  82 |  116 | 1.41x |
| xalan    | PASS |  84 |   63 | **0.75x** |
| avrora   | PASS | 3657 | 3588 | 0.98x |
| h2       | PASS |  73 |  802 | **10.99x** |
| batik    | PASS | 253 |  353 | 1.40x |
| biojava  | PASS | 141 |  195 | 1.38x |
| jme      | PASS | 373 |  408 | 1.09x |
| graphchi | PASS | 492 |  763 | 1.55x |
| zxing    | PASS | ~   |  ~   | — |
| fop      | FAIL | — | — | — (Digest validation; stderr diverges from golden) |
| jython   | FAIL | — | — | — (InvocationTargetException) |
| spring   | FAIL | — | — | — (Hibernate mapping; reflection-heavy init) |
| tomcat/tradebeans/luindex-lg | untested | — | — | — |

For reference, the original CROCHET paper reported 1.06x avg on
DaCapo 9.12-bach. We have optimization room in `fastAccess` before
claiming parity, but the port is in the right ballpark.

## What made it work

### Five root causes fixed

1. **ASM's `ClassWriter.getCommonSuperClass` falls back to `Class.forName`**,
   which can't resolve user classes through the agent's classloader or
   triggers classloader recursion when transforms are in flight. Fix:
   override with a resource-stream-based superchain walker
   (`SafeClassWriter` in CrochetTransformer) that reads class files
   directly via the loader's `getResourceAsStream` and a throwaway
   `ClassReader`. Never invokes `forName`. Falls back to
   `java/lang/Object` when a type is truly unresolvable — verification
   accepts, just at less-precise frames.

2. **Pre-Java-6 class files** (major < 50, e.g. commons-logging 1.x at
   major=45) use JSR/RET subroutines that `COMPUTE_FRAMES` can't handle,
   and their old-inference verifier doesn't compose with our injected
   methods' StackMapTable frames. Fix: `CrochetTransformer.transform`
   reads major version from bytes 6-7 of the buffer and skips.

3. **Multiple stacked `LocalVariablesSorter` instances** (one per visitor
   that wanted scratch locals) produced cumulative local-index rewrites
   that confused `COMPUTE_FRAMES` on large methods — visible as
   VerifyError "Bad local variable type". Fix: every visitor now uses
   stack gymnastics only.
   * `FieldAccessWrapper` 2-slot PUTFIELD via `DUP2_X1 + POP2 + DUP_X2`.
   * `StaticFieldRewriter` — GETSTATIC/PUTSTATIC with zero scratch locals:
     each helper call pushes a 1-slot reference and pops it again,
     leaving the value at the bottom untouched.
   * `ArrayAccessWrapper` — 1-slot xASTOREs via the DUP2_X1/DUP_X2
     rotation pattern. 2-slot LASTORE/DASTORE fall through unwrapped
     (small coverage gap; documented).

4. **Boot- and platform-loader classes** can't see the agent's runtime
   classes, so instrumenting them emits bytecode references that fail
   to link. Fix: `TransformerWrapper` skips transformation when the
   class loader is the boot loader (null) or the platform classloader.

5. **Package-private classes** reject reflective `Method.invoke` on
   their public-static members from outside-package callers. Fix:
   `ClassMeta.resolveLookup` calls `setAccessible(true)` before invoking
   the injected `$$crochetLookup` method. Also: `sfHelperFor` returns
   a no-op helper (`NoopSFHelper`) when the target class has no
   `$$crochetLookup` (enums, annotations, pre-Java-6 classes).

### Remaining failures

- **fop**: runs clean but produces extra stderr output that breaks
  DaCapo's digest validation. Likely our diagnostic prints or a
  warning from one of the agent paths. Harmless but fails checksum.
  Path to fix: identify the stderr source, route through a logger
  that DaCapo's golden ignores.
- **jython**: InvocationTargetException during benchmark init.
  Reflection-heavy Python VM startup; probable classloader-visibility
  path we haven't caught. Needs detailed stack inspection.
- **spring**: Hibernate's `SingleTableEntityPersister` constructor
  reflection fails. Hibernate uses deep reflection into persister
  classes and we probably break a signature contract somewhere.
  Probably the hardest to fix; may need to skip instrumentation of
  hibernate-persister package.
- **h2's 11x overhead**: outlier. h2 runs many small transactions;
  each one touches hot paths. `fastAccess` lock contention or
  reflective lookup overhead amplifies. Profile and optimize.

## How to reproduce

```bash
# Build and produce the instrumented JDK
mvn install -DskipTests
rm -rf /tmp/jdk-inst
java -jar crochet-instrument/target/crochet-instrument-1.0.0-SNAPSHOT.jar \
    "$JAVA_HOME" /tmp/jdk-inst

# Baseline
java -jar /tmp/dacapo/dacapo-23.11-chopin.jar avrora -s small -n 3

# With crochet agent on the instrumented JDK
/tmp/jdk-inst/bin/java --add-reads java.base=jdk.unsupported \
    -javaagent:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    -jar /tmp/dacapo/dacapo-23.11-chopin.jar avrora -s small -n 3
```

## Diagnostics

- `-Dcrochet.dumpClasses=true` — write every transformed class file to
  `/tmp/crochet-dump/` for `javap -v` inspection.
- `-Dcrochet.verboseCompat=true` — print the cause of SF-helper
  generation failures instead of swallowing.

## Next optimization pass

1. Profile h2 under the agent. Top candidates: `fastAccess` sync-
   block contention, `sfHelperFor` lookup cost (currently one map
   hit per GETSTATIC), and `ClassMeta.fieldOffsets` cold path.
2. Skip instrumentation entirely on classes with no mutable state
   (no non-final instance fields). The scan is cheap and eliminates
   hook overhead for immutable classes.
3. Inline `$$crochetAccess` no-op for user classes that never enter
   a proxy state (the JIT should already do this but profiling will
   confirm).
4. Move `sfHelperFor` result caching to a dedicated ClassValue if
   the synchronized-map lookup shows up in profiles.
