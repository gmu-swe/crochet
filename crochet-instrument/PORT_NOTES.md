# Phase 1 Port Notes: Galette -> Crochet

Mechanical port of Galette's jlink plugins and Maven plugin.

## File mapping

From `galette/galette-instrument/src/main/java/edu/neu/ccs/prl/galette/instrument/`
to `crochet-instrument/src/main/java/net/jonbell/crochet/instrument/`:

| Source | Destination |
|---|---|
| `DeletingFileVisitor.java` | `DeletingFileVisitor.java` |
| `GaletteInstrumentation.java` | `CrochetInstrumentation.java` |
| `GaletteInstrumenter.java` | `CrochetInstrumenter.java` |
| `GaletteJLinkPlugin.java` | `CrochetJLinkPlugin.java` |
| `GenericInstrumenter.java` | `GenericInstrumenter.java` |
| `InstrumentJLinkPlugin.java` | `InstrumentJLinkPlugin.java` |
| `InstrumentUtil.java` | `InstrumentUtil.java` |
| `Instrumentation.java` | `Instrumentation.java` |
| `JLinkInvoker.java` | `JLinkInvoker.java` |
| `JLinkRegistrationAgent.java` | `JLinkRegistrationAgent.java` |
| `PackJLinkPlugin.java` | `PackJLinkPlugin.java` |
| `Packer.java` | `Packer.java` |
| `ResourcePoolPacker.java` | `ResourcePoolPacker.java` |

From `galette/galette-maven-plugin/src/main/java/edu/neu/ccs/prl/galette/plugin/`
to `crochet-maven-plugin/src/main/java/net/jonbell/crochet/plugin/`:

| Source | Destination |
|---|---|
| `InstrumentMojo.java` | `InstrumentMojo.java` |

No `META-INF/services` files existed in the galette sources; jlink plugin
registration is handled at runtime by `JLinkRegistrationAgent#premain`.

## Renames applied

- Package `edu.neu.ccs.prl.galette.instrument` -> `net.jonbell.crochet.instrument`
- Package `edu.neu.ccs.prl.galette.plugin` -> `net.jonbell.crochet.plugin`
- Import `edu.neu.ccs.prl.galette.internal.runtime.Tag` -> `net.jonbell.crochet.runtime.Tag`
- Import `edu.neu.ccs.prl.galette.internal.transform.GaletteTransformer` -> `net.jonbell.crochet.transform.CrochetTransformer`
- Import `edu.neu.ccs.prl.galette.internal.transform.FileUtil` -> `net.jonbell.crochet.transform.FileUtil`
- Import `edu.neu.ccs.prl.galette.internal.patch.Patcher` -> `net.jonbell.crochet.patch.Patcher`
- Class `GaletteInstrumentation` -> `CrochetInstrumentation`
- Class `GaletteInstrumenter` -> `CrochetInstrumenter`
- Class `GaletteJLinkPlugin` -> `CrochetJLinkPlugin`
- Class `GaletteTransformer` -> `CrochetTransformer` (reference only; definition comes later)
- Constant `JLinkRegistrationAgent.MODULE_NAME`: `"edu.neu.ccs.prl.galette.instrument"` -> `"net.jonbell.crochet.instrument"`
- System properties in `CrochetInstrumenter`: `galette.instrument.verbose`/`galette.instrument.modules` -> `crochet.instrument.verbose`/`crochet.instrument.modules`
- Mojo `@Parameter property=` prefixes: `galette.` -> `crochet.`
- Default output directory: `${project.build.directory}/galette/java/` -> `${project.build.directory}/crochet/java/`
- Match-info directory name: `galette-instrument-match` -> `crochet-instrument-match`

The `RUNTIME_PACKAGE_PREFIX`/`TRANSFORM_PACKAGE_PREFIX` constants are only
*referenced* from `CrochetInstrumentation.shouldPack`; their string values
(the `net/jonbell/crochet/runtime/` and `net/jonbell/crochet/transform/`
prefixes) will live on `CrochetTransformer` when Phase 2 ports it.

## License headers

- `JLinkRegistrationAgent.java` retains its original Apache 2.0 header
  verbatim (it is derived from hibernate-demos). Package name updated.
- All other files received the BSD 3-Clause attribution header from the
  task instructions; none of the galette sources carried an inline header.

## Remaining unresolved references (deferred to Phase 2)

These are expected and match the "cannot find symbol" shape the task allows.
They will be resolved when the transform/runtime/patch modules are ported:

- `net.jonbell.crochet.transform.CrochetTransformer`
  (referenced by `CrochetInstrumentation`; provides `RUNTIME_PACKAGE_PREFIX`,
  `TRANSFORM_PACKAGE_PREFIX`, and `transform(byte[], boolean)`)
- `net.jonbell.crochet.transform.FileUtil`
  (referenced by `JLinkInvoker.storeOptions` and `InstrumentMojo.computeChecksum`;
  needs `createTemporaryFile(String, String)` and `checksum(byte[])`)
- `net.jonbell.crochet.runtime.Tag`
  (referenced by `CrochetInstrumentation.configure` as a class-path anchor)
- `net.jonbell.crochet.patch.Patcher`
  (referenced by `CrochetInstrumentation.createPatcher`; constructor takes a
  `Function<String, byte[]>` and exposes `patch(String, byte[]) -> byte[]`)

## Non-mechanical judgment calls

Flagging these for Phase 2 review - no invented equivalents:

1. **Module system / `jdk.tools.jlink.plugin`.** Galette declares
   `requires jdk.jlink;` in `galette-instrument/src/main/java/module-info.java`
   and uses moditect to attach it post-shade. The task did not list
   `module-info.java` among the 13 files to port, and forbids pom edits, so
   it was not added. Compilation therefore cannot resolve the
   `jdk.tools.jlink.plugin.*` imports on Java 21 unnamed-module output.
   Resolving this will require either adding `src/main/java/module-info.java`
   or configuring the compiler plugin with `--add-exports`/`--add-modules`.

2. **JaCoCo dependency missing.** `GenericInstrumenter`, `Packer`, and
   `ResourcePoolPacker` import from `org.jacoco.core.instr.Instrumenter`
   and `org.jacoco.core.internal.{ContentTypeDetector, InputStreams}`.
   Galette pulled in `org.jacoco:org.jacoco.core:0.8.12` and shade-relocated
   it. The task forbids pom edits, so the crochet-instrument pom does not
   declare JaCoCo - these references will not resolve until that dependency
   is added (or replaced with in-tree utilities).

3. **Moditect / shade configuration.** Galette's build attaches a
   `module-info.java` via moditect and shades `org.jacoco.core` and
   `org.objectweb.asm` under its own package. The crochet-instrument
   pom does neither. This affects runtime packaging (the agent jar must
   be a named module for `JLinkRegistrationAgent.premain`'s module
   redefinition to work), but is a packaging concern, not a source-port
   concern.

4. **Galette excluded transitive deps.** The galette-instrument pom
   excludes all transitives of `galette-agent`. The crochet-instrument
   pom does not, but since `crochet-agent` currently has no declared
   dependencies this is a no-op today - worth revisiting after Phase 2.

No galette config constant, property key, or resource name was dropped
without a direct crochet equivalent.

## Verification

Command run (per task):

```
export PATH=~/.local/bin:$PATH
cd /home/jon/crochet
mvn -q -pl :crochet-instrument,:crochet-maven-plugin -am compile 2>&1 | tail -40
```

Last 40 lines of output:

```
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/ResourcePoolPacker.java:[79,20] cannot find symbol
[ERROR]   symbol:   variable InputStreams
[ERROR]   location: class net.jonbell.crochet.instrument.ResourcePoolPacker
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/GenericInstrumenter.java:[33,5] method does not override or implement a method from a supertype
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/GenericInstrumenter.java:[56,17] cannot find symbol
[ERROR]   symbol:   method instrumentAll(java.io.InputStream,java.io.OutputStream,java.lang.String)
[ERROR]   location: class net.jonbell.crochet.instrument.GenericInstrumenter
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/GenericInstrumenter.java:[93,24] cannot find symbol
[ERROR]   symbol:   class ContentTypeDetector
[ERROR]   location: class net.jonbell.crochet.instrument.GenericInstrumenter
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/GenericInstrumenter.java:[99,57] cannot find symbol
[ERROR]   symbol:   variable ContentTypeDetector
[ERROR]   location: class net.jonbell.crochet.instrument.GenericInstrumenter
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/InstrumentJLinkPlugin.java:[11,5] method does not override or implement a method from a supertype
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/InstrumentJLinkPlugin.java:[16,5] method does not override or implement a method from a supertype
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/InstrumentJLinkPlugin.java:[21,5] method does not override or implement a method from a supertype
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/InstrumentJLinkPlugin.java:[23,16] cannot find symbol
[ERROR]   symbol:   variable Category
[ERROR]   location: class net.jonbell.crochet.instrument.InstrumentJLinkPlugin
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/InstrumentJLinkPlugin.java:[28,50] package ResourcePoolEntry does not exist
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/JLinkInvoker.java:[47,21] cannot find symbol
[ERROR]   symbol:   variable FileUtil
[ERROR]   location: class net.jonbell.crochet.instrument.JLinkInvoker
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/PackJLinkPlugin.java:[11,5] method does not override or implement a method from a supertype
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/PackJLinkPlugin.java:[16,5] method does not override or implement a method from a supertype
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/PackJLinkPlugin.java:[21,5] method does not override or implement a method from a supertype
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/PackJLinkPlugin.java:[23,16] cannot find symbol
[ERROR]   symbol:   variable Category
[ERROR]   location: class net.jonbell.crochet.instrument.PackJLinkPlugin
[ERROR] /home/jon/crochet/crochet-instrument/src/main/java/net/jonbell/crochet/instrument/PackJLinkPlugin.java:[28,50] package ResourcePoolEntry does not exist
[ERROR] -> [Help 1]
[ERROR]
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR]
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
[ERROR]
[ERROR] After correcting the problems, you can resume the build with the command
[ERROR]   mvn <args> -rf :crochet-instrument
```

All errors fall into the expected categories above: missing Phase 2
classes, absent `jdk.jlink` module, or absent `org.jacoco.core`. No
port-local errors (typos, bad imports within ported files, broken
renames) remain.
