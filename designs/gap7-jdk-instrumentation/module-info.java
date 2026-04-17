/*
 * Proposed module-info.java for crochet-instrument.
 *
 * Mirrors galette-instrument/src/main/java/module-info.java (which is just
 * `requires jdk.jlink; requires java.instrument;`), plus a `requires static`
 * for each third-party artifact that the shade plugin relocates into this
 * jar. The `static` form keeps javac resolving the names at compile time but
 * tells moditect's emitted descriptor not to require them at runtime — the
 * classes are present inside the shaded jar under a relocated package, not as
 * separate modules.
 *
 * Intended location:
 *   crochet-instrument/src/main/java/module-info.java
 *
 * Do NOT copy this file into crochet-instrument/ as part of this gap's
 * design work — it belongs to the implementation commit that wires the
 * pipeline up.
 */
module net.jonbell.crochet.instrument {

    exports net.jonbell.crochet.instrument;

    // JDK modules actually needed at both compile and run time.
    requires jdk.jlink;          // jdk.tools.jlink.plugin.*
    requires java.instrument;    // JLinkRegistrationAgent.premain(Instrumentation)

    // The agent jar is a plain (unnamed/automatic) module. At runtime it is
    // loaded via -J--class-path= from JLinkInvoker, not via --module-path, so
    // we do not list it as a `requires` here. JLinkInvoker adds
    // `--add-reads=net.jonbell.crochet.instrument=ALL-UNNAMED` so the
    // reflective handshake in CrochetInstrumentation.configure resolves
    // net.jonbell.crochet.transform.CrochetTransformer from the unnamed
    // module at runtime.
    //
    // Third-party classes (ASM, JaCoCo) are shaded + relocated into this jar
    // by maven-shade-plugin, so no `requires` clause is needed for them.

    // Services provided dynamically by JLinkRegistrationAgent#premain.
    // We intentionally do NOT declare `provides jdk.tools.jlink.plugin.Plugin
    // with ...` here: jdk.jlink does not export jdk.tools.jlink.plugin to
    // unnamed or arbitrary named modules, so the `with` clause would fail
    // to resolve at module-system startup. JLinkRegistrationAgent uses
    // java.lang.instrument.Instrumentation#redefineModule to inject the
    // provides clause after both modules are in the boot layer. See
    // JLinkRegistrationAgent.premain, lines 226-246.
}
