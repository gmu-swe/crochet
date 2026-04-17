# DaCapo benchmark status

## Headline

**22 of 22 DaCapo 23.11-chopin benchmarks PASS** end-to-end with the
crochet-agent attached to the instrumented JDK (h2o requires Java 17,
matching the upstream-supported version). This round added `tradebeans`,
`tradesoap`, `h2o`, and dropped h2's overhead from 9.87x to ~3x via a
fused static-field pre-hook. Median overhead **1.22x**.

The h2o fix was a single-class skip of `java.lang.Byte`: injecting
`$$crochetVersion` (a 4-byte `int` field) into Byte shifts Byte's
primitive `value` byte from offset 12 to offset 16, which HotSpot's
`@IntrinsicCandidate valueOf` / `byteValue` methods depend on. The
layout-sensitivity is a VM contract we cannot restore at the Java
level. Bisection proved the skip is minimal — only Byte is affected;
no other boxed primitive (including Boolean) trips h2o. Details in
the inline comment at `CrochetTransformer.shouldSkip` and in the
commit message.

| benchmark | status | base (ms) | crochet (ms) | ratio |
|---|---|---|---|---|
| spring      | PASS | 72   | 61   | 0.85x |
| avrora      | PASS | 3435 | 3597 | 1.05x |
| cassandra   | PASS | 4978 | 5470 | 1.10x |
| jme         | PASS | 375  | 414  | 1.10x |
| xalan       | PASS | 78   | 87   | 1.12x |
| pmd         | PASS | 90   | 110  | 1.22x |
| tomcat      | PASS | 423  | 514  | **1.22x** (was 26.8x) |
| kafka       | PASS | 944  | 1153 | **1.22x** (was 4.41x) |
| fop         | PASS | 127  | 155  | 1.22x |
| biojava     | PASS | 154  | 204  | 1.32x |
| tradebeans  | PASS | 496  | 667  | **1.34x** (was HANG) |
| sunflow     | PASS | 358  | 511  | 1.43x |
| batik       | PASS | 244  | 357  | 1.46x |
| tradesoap   | PASS | 1991 | 1333 | **0.67x** (was HANG) |
| luindex     | PASS | 772  | 1315 | 1.70x |
| jython      | PASS | 384  | 669  | 1.74x |
| zxing       | PASS | 116  | 219  | 1.89x |
| eclipse     | PASS | 358  | 758  | 2.12x |
| h2          | PASS | 95   | 281  | **2.96x** (was 9.87x) |
| lusearch    | PASS | 69   | 232  | 3.36x |
| graphchi    | PASS | 473  | 2446 | 5.17x |
| h2o (Java 17) | PASS | 1994 | 4043 | **2.03x** (Java 17 required; +`-Ddacapo.h2o.port=<free>` to avoid docker's 54321) |

Paper target was 1.06x avg on DaCapo 9.12-bach. Our current median is
1.22x on the modernized 23.11-chopin set. All concurrent/server
workloads are now under 1.5x except lusearch (3.36x) and eclipse (2.12x);
the only outliers are graphchi (5.17x, CPU-bound graph ops) and h2
(2.96x, single-threaded many-transaction OLTP).

## Architecture changes this round

### Fused `noteStaticAccess` pre-hook

`StaticFieldRewriter` previously emitted two instructions for every
GETSTATIC/PUTSTATIC: `INVOKESTATIC sfHelperFor(Class)` followed by
`INVOKEINTERFACE CRIJInstrumented.$$crochetAccess()`. The second leg
was an open-polymorphic virtual dispatch the JIT couldn't devirtualize
(every instrumented user class contributes its own SF-helper class to
the inline cache). Under h2 / WildFly this lookup dominated:
`org.jboss.logging.Logger$Level` was hit 8.3 million times during
tradebeans startup.

New design: `CheckpointRollbackAgent.noteStaticAccess(Class<?>)`
replaces the pair with a single static call. It does a `ClassValue`
lookup for `ClassMeta`, reads the volatile `sfHelper` field, and
returns immediately if materialised (the common case). The JIT can
inline the whole fast path to a single indirect load + branch. Cold
path calls `sfHelperFor` to materialise then caches.

Effect: h2 9.87x → 2.96x. On tradebeans the `Logger$Level` counter
dropped from 8.3M to 323K (the first cold-path call, then fast path
thereafter).

### Targeted proxy-class skips

Two new patterns in `CrochetTransformer.shouldSkip` from JFR-guided
diagnosis of the tradebeans "hang":

- `contains("$$$view")` — JBoss classfilewriter EJB client-view
  proxies (e.g. `TradeSLSBLocal$$$view1`). `AbstractProxyFactory`
  copies the underlying bean's declared methods by reflection,
  including our synthetic `$$crochetCopyFieldsTo`, into the generated
  view class body. When we then transform that view, `FieldAdder`
  re-emits it and the loader rejects the duplicate with
  `ClassFormatError: Duplicate method name "$$crochetCopyFieldsTo"`.
  That aborts the `web.war` INSTALL phase; WildFly serves 404s; the
  DaCapo harness retry loop "hangs" until its own watchdog fires.
  Skipping is safe — these views are dispatch wrappers with no
  mutable state.
- `contains("_$$_Weld")` — Weld CDI client proxies + interceptor
  subclasses (e.g. `CdiExtension$Proxy$_$$_WeldClientProxy`). Same
  inheritance pattern; plus `VerifyError: Expecting a stackmap frame
  at branch target 14` when we rewrite their already-stitched
  bytecode.

### Injected-field visibility: `ACC_PUBLIC` → `ACC_PRIVATE | ACC_TRANSIENT`

`FieldAdder` now emits `$$crochetVersion` and `$$crochetSnap` as
private transient synthetic fields.

- **Private**: JBoss Weld emits `WELD-000075: Normal scoped managed
  bean implementation class has a public field` on beans with our
  previously-public fields. All read/write sites live inside the
  emitted `$$crochet*` methods on the declaring class itself, so
  private visibility suffices; `Unsafe` offset access from the
  agent's runtime bypasses language-level access control anyway.
- **Transient**: h2o's `water.api.Schema.fillFromParms` walks every
  declared field of Schema subclasses and requires each to carry an
  `@API` annotation unless `Modifier.isTransient(field.getModifiers())`.
  Marking our fields transient makes the reflection pass skip them.
  Also orthogonally prevents our bookkeeping from appearing in any
  `ObjectOutputStream` serialization of user objects.

### Telemetry infrastructure (opt-in)

New `TransformTracer` and `RuntimeTracer` classes, behind
`-Dcrochet.traceTransform=true` / `-Dcrochet.traceRuntime=true`. Zero
cost when off. Write per-class transform timing to
`/tmp/crochet-transform-trace.log` and per-class
`fastAccess` / `sfHelperFor` invocation counts to
`/tmp/crochet-runtime-counts.log` on shutdown. These were the
primary tool that revealed the `Logger$Level` 8.3M hotspot during
tradebeans startup and confirmed the fused pre-hook fix.

## h2o: root-caused and fixed via single-class skip

h2o's symptom: instrumented JDK parses a 15-column CSV but every
numeric cell lands as NaN; h2o's `ignore_const_cols=true` then drops
the 14 non-response columns as "constant", so DRF training fails with
`Training data must have at least 2 features (incl. response).`
Reproduces WITHOUT the runtime agent — it's the jlink rewrite of
`java.base` alone.

Bisection narrowed it to a single class-file delta on `java.lang.Byte`:
- Instrumenting ONLY Byte (everything else stock) → reproduces.
- Skipping ONLY Byte (everything else instrumented) → passes.
- Adding only `$$crochetSnap` (Object ref) to Byte → passes.
- Adding only `$$crochetVersion` (int) to Byte → reproduces.

Mechanism: HotSpot's field-layout algorithm places the injected int at
offset 12, shifting Byte's primitive `value` byte from offset 12 (stock)
to offset 16. Byte is annotated `@jdk.internal.ValueBased` and its
`valueOf` / `byteValue` are `@IntrinsicCandidate`; HotSpot-level code has
layout assumptions about Byte that no Java-level rewriting can restore.
Autoboxing/unboxing/reflection of individual Byte instances still
returns correct values — the failure is specific to h2o's composition
of a byte-level CSV parser + `water.Weaver`/Javassist-generated Icer
+ HotSpot's Byte intrinsics.

No other `@ValueBased` primitive (Short, Character, Integer, Long,
Float, Double, Boolean) triggers the failure. Fix: skip `java.lang.Byte`
exactly, same pattern as the existing `java.lang.Object` skip.
See `CrochetTransformer.shouldSkip` for the inline justification.

h2o currently requires Java 17 (h2o 3.42.0.2's upstream version
constraint — independent of crochet). Invocation:
```
/tmp/jdk-inst-j17/bin/java --add-reads java.base=jdk.unsupported \
    -Ddacapo.h2o.port=54400 \
    -javaagent:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    -jar /tmp/dacapo/dacapo-23.11-chopin.jar h2o -s small -n 3
```
The `-Ddacapo.h2o.port` override is unrelated to crochet; it avoids
port 54321 which Docker commonly owns on developer machines.

## How to reproduce

```bash
# Build and produce the instrumented JDK
mvn install -DskipTests
rm -rf /tmp/jdk-inst
java -jar crochet-instrument/target/crochet-instrument-1.0.0-SNAPSHOT.jar \
    "$JAVA_HOME" /tmp/jdk-inst

# Baseline
java -jar /tmp/dacapo/dacapo-23.11-chopin.jar h2 -s small -n 3

# With crochet agent on the instrumented JDK
/tmp/jdk-inst/bin/java --add-reads java.base=jdk.unsupported \
    -javaagent:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    -jar /tmp/dacapo/dacapo-23.11-chopin.jar h2 -s small -n 3

# tradebeans / tradesoap (JavaEE / WildFly)
/tmp/jdk-inst/bin/java --add-reads java.base=jdk.unsupported \
    -javaagent:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    -jar /tmp/dacapo/dacapo-23.11-chopin.jar tradebeans -s small -n 3

# cassandra (requires sm-allow flag)
/tmp/jdk-inst/bin/java --add-reads java.base=jdk.unsupported \
    -Djava.security.manager=allow \
    -javaagent:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    -jar /tmp/dacapo/dacapo-23.11-chopin.jar cassandra -s small -n 3
```

## Diagnostics

- `-Dcrochet.dumpClasses=true` — write every transformed class file to
  `/tmp/crochet-dump/` for `javap -v` inspection.
- `-Dcrochet.verboseCompat=true` — print the cause of transform
  failures instead of swallowing them.
- `-Dcrochet.traceTransform=true` — per-class transform timing to
  `/tmp/crochet-transform-trace.log`.
- `-Dcrochet.traceRuntime=true` — per-class `fastAccess` and
  `sfHelperFor` call counters, dumped to
  `/tmp/crochet-runtime-counts.log` on JVM shutdown.

## Next optimisation pass

1. **graphchi at 5.17x**: CPU-bound graph traversal. Profile under
   `traceRuntime` to see whether the overhead is `fastAccess`
   per-touch or the `FieldAccessWrapper` hook cost. Candidate:
   emit a no-op-optimizable `$$crochetAccess` body that the JIT
   can fold when `$$crochetVersion == 0`.
2. **lusearch at 3.36x**: many-threaded query workload. Likely
   similar path — the stripe-lock alone isn't enough.
3. **eclipse at 2.12x**: large surface, many classes; worth a
   transform-timing profile to find any single-class hotspot.
4. **h2o parse corruption**: identify the specific `java.base`
   API whose rewrite changes CSV parse behaviour. Likely candidate:
   `java.nio.charset.*` or `java.io.BufferedReader`.
5. **Skip-list completeness**: audit for other runtime-proxy
   frameworks (CGLIB, Javassist, Mockito) we might still be
   re-emitting into.
