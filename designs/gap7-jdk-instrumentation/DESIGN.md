# Gap 7: JDK Class Instrumentation Wiring

## Problem

The agent currently refuses to transform any class whose name begins with
`java/`, `jdk/`, `sun/`, or `com/sun/` (see
`crochet-agent/src/main/java/net/jonbell/crochet/transform/CrochetTransformer.java`,
lines 45-49). That means `HashMap`, `ArrayList`, `Integer`, etc. never get
`$$crochetLookup`, never gain a `CRIJInstrumented` surface, and never take part
in checkpoint/rollback — a correctness hole for anything the user's code
indirectly checkpoints through a JCL container.

Phase 1 landed the machinery to fix this (commit `661e12f`): ported Galette's
jlink plugins into `crochet-instrument/` plus the `crochet-maven-plugin` mojo
that drives them. What's missing is the wiring and packaging that turns those
ported files into a runnable pipeline.

## End-to-end plan (ordered)

1. **`src/main/java/module-info.java`** in `crochet-instrument` — lets javac
   resolve `jdk.tools.jlink.plugin.*` without per-compiler `--add-exports` and,
   more importantly, gives the shaded jar a module identity so
   `JLinkRegistrationAgent.premain` can `redefineModule(MODULE_NAME)` to add a
   `provides jdk.tools.jlink.plugin.Plugin with ...` clause at agent start.
2. **Shade + moditect in `crochet-instrument/pom.xml`** — bundle ASM and
   JaCoCo into the instrument jar under a relocated package, then run
   `moditect:add-module-info` on the shaded jar. Shade alone strips the
   module descriptor; moditect re-attaches it after relocation so the named
   module matches `net.jonbell.crochet.instrument`.
3. **Fix `CrochetInstrumentation.configure` wiring** — the current body
   instantiates a `CrochetTransformer` but never registers it as the transform
   the jlink pipeline uses. `apply(byte[])` already calls
   `transformer.transform(...)`, so the class-path work is the remaining
   issue: `classPathElements` must carry both the agent jar (where
   `CrochetTransformer` and the runtime live) and the instrument jar itself
   (where `Instrumentation.create` reflects). Galette does this implicitly
   via `InstrumentUtil.getClassPathElement(Tag.class)` plus the post-shade
   single-jar layout where runtime + transform classes share one artifact; we
   either mirror that (shade `crochet-agent` into `crochet-instrument`) or add
   `InstrumentUtil.getClassPathElement(CrochetTransformer.class)` to the set.
   The design recommends the second option because it avoids a second shade.
4. **`@CrochetInstrumented` marker annotation** in `crochet-agent/runtime`
   (descriptor `Lnet/jonbell/crochet/runtime/CrochetInstrumented;`). The
   transformer emits it on every class it touches; the transformer also reads
   it up front and returns `null` to skip classes that already bear it. This
   is what lets the runtime transformer run over a pre-instrumented JDK
   without double-instrumenting `java.util.ArrayList`.
5. **`CrochetTransformer.shouldSkip` becomes dual-mode** — see §4 below.
6. **Mojo + CLI paths** — `java -jar crochet-instrument.jar $JAVA_HOME
   target/jdk-inst` and `target/jdk-inst/bin/java -javaagent:... MyApp`.
7. **Wire the new paths into a smoke test** so we can visibly confirm
   `java.util.ArrayList` in `target/jdk-inst` has the injected members.

## 2. module-info.java

See `module-info.java` in this directory. The module `requires jdk.jlink`
(for `jdk.tools.jlink.plugin.Plugin` and the resource-pool API), `requires
java.instrument` (for `JLinkRegistrationAgent.premain`), `requires static
org.jacoco.core` and `org.objectweb.asm{,.tree,.commons}` (static because
after shade they are relocated inside the jar and no longer present as
external modules at runtime — moditect needs the `requires` lines stripped
before it rewrites the descriptor; `static` is the conventional way to keep
javac happy while letting the post-shade descriptor omit them). The module
`exports net.jonbell.crochet.instrument` so the maven plugin can reach
`CrochetInstrumentation` and `CrochetInstrumenter`.

## 3. pom.xml diff (shade + moditect)

See `pom.xml.diff` in this directory for the full delta. Highlights:

- Remove the `maven.compiler.release`/`source`/`target = 17` override.
  Once the module-info file exists, `--add-exports` is not needed; the
  module path carries the exports. Keep the parent's `release = 17`.
- Add `maven-shade-plugin` with `<includes>` for
  `net.jonbell.crochet:*`, `org.jacoco:*`, `org.ow2.asm:*` (mirroring
  galette's artifactSet). Relocate `org.objectweb.asm ->
  net.jonbell.crochet.instrument.shaded.asm` and `org.jacoco.core ->
  net.jonbell.crochet.instrument.shaded.jacoco`.
- Add `moditect-maven-plugin` with `overwriteExistingFiles=true`, feeding
  the `module-info.java` generated in step 2. Phase is `package` after
  shade.
- Keep the `maven-jar-plugin` manifest entries (`Premain-Class`,
  `Main-Class`) so the shaded jar remains self-executing.

## 4. `CrochetTransformer.shouldSkip` (dual-mode)

Today `shouldSkip` returns true for anything under `java/`, `jdk/`,
`sun/`, or `com/sun/`. After this change the transformer has to handle
three cases that differ by *why* the class arrived:

| Context | Triggered via | Expected action |
|---|---|---|
| Build-time (jlink) | `CrochetInstrumentation.apply(byte[])` | Transform JCL + user classes; emit `@CrochetInstrumented` |
| Runtime, pre-instr. | `-javaagent` against instrumented JDK | Skip if `@CrochetInstrumented` present; otherwise transform |
| Runtime, vanilla JDK | `-javaagent` against stock JDK | Skip JCL (today's behavior); transform user classes |

A context flag on the transformer is noisy. The cleaner invariant is: the
annotation *is* the context marker. Proposed rule:

```
if (internalName is null)                   -> skip
if (name endsWith "module-info")            -> skip
if (name equals "java/lang/Object")         -> skip (shadow field on Object is a V2 concern)
if (name startsWith our own packages)       -> skip
if (classfile already has @CrochetInstrumented) -> skip (dedup)
else                                        -> transform
```

No JDK-prefix skip. At runtime, the first time the agent sees
`java.util.ArrayList`, it checks the annotation on the incoming bytes. If
the user ran the instrumented JDK, `@CrochetInstrumented` is present and
the agent returns `null` (JVM keeps the jlink-baked version). If the user
ran a vanilla JDK, the annotation is absent and we do transform — which is
fine for a best-effort warning mode, but will likely still fail on
`java.lang.Object`. We document that pairing the agent with a vanilla JDK
is unsupported for V1 and emit a log warning on the first JCL class seen
without the annotation.

The annotation check uses a cheap pre-scan (`ClassReader.readInt` / skip to
attribute table) before constructing the `ClassNode`. See
`shouldSkip-proposal.md` in this directory for the exact code.

## 5. Two contexts, same transformer

The jlink plugin calls `transformer.transform(bytes, false)` on every
resource pool entry; the runtime agent does the same via
`TransformerWrapper`. The differences are:

- **Classpath of the transformer itself.** In the jlink context, the agent
  jar is on `-J--class-path=<agent-jar>` (see `JLinkInvoker` line 28). At
  runtime the agent jar is loaded by the system class loader from
  `-javaagent:`. Both cases give the transformer access to ASM from the
  same place, so nothing code-side cares.
- **Where `java.lang.Object` lives.** At jlink time, `java/lang/Object`
  appears as a resource pool entry and we are invited to rewrite it. Galette
  rewrites Object (adds `$$GALETTE_offsetCache` / thread-local slot); we do
  not, for V1. The skiplist therefore retains the single literal
  `"java/lang/Object"` exclusion.
- **Synthetic / lambda classes.** At runtime the agent sees
  `java.util.HashMap$$Lambda/0x...` objects the JVM fabricated. Those
  classes won't carry the annotation (they inherit nothing about it from
  their target method). We skip them in `shouldSkip` via a
  `name.contains("$$Lambda")` clause.

Everything else — ASM version, ClassWriter flags, LookupInjector behavior —
is context-free. No dual code path is needed.

## 6. Test procedure

See `verify.sh` in this directory. Summary:

1. `mvn -pl :crochet-agent,:crochet-instrument -am package`
2. Confirm `crochet-instrument/target/crochet-instrument-*.jar` is a
   named module: `jar --describe-module
   --file=crochet-instrument/target/crochet-instrument-*.jar`.
3. `java -jar crochet-instrument/target/crochet-instrument-*.jar
   $JAVA_HOME /tmp/jdk-inst`
4. `/tmp/jdk-inst/bin/jimage extract --dir=/tmp/jimage
   /tmp/jdk-inst/lib/modules`
5. `javap -p /tmp/jimage/java.base/java/util/ArrayList.class | grep crochet`
   — must show `$$crochetLookup`, `$$crochetCheckpoint`, etc.
6. `javap -v /tmp/jimage/java.base/java/util/ArrayList.class | grep
   CrochetInstrumented` — must show the annotation.
7. Run the end-to-end integration test: demo app that checkpoints an
   `ArrayList`, mutates it, rolls back, asserts restoration.

## Caveats

- Oracle JDK's `jce.jar` is signed; instrumenting it invalidates the
  signature, so Oracle JDKs cannot be the input. Temurin only — matches
  Galette's documented constraint; called out in the project README once
  this lands.
- Classpath resource ordering: `JLinkInvoker` passes the instrument jar
  both as `-J--class-path` and `-J--module-path`. Because shade turns the
  instrument jar into a self-contained bundle, that's fine — there is no
  split between runtime and instrument classes at build time.
