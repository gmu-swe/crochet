/*
 * Module descriptor for crochet-instrument. Mirrors
 * galette-instrument/src/main/java/module-info.java plus `requires static`
 * for third-party artifacts that the shade plugin relocates into this jar.
 *
 * The `static` qualifier lets javac resolve the names at compile time while
 * moditect drops the runtime `requires` entries — after shade, ASM, JaCoCo,
 * and the crochet-agent classes live under net.jonbell.crochet.instrument.shaded
 * (or directly under the agent's own shaded package), not as external modules.
 */
module net.jonbell.crochet.instrument {

    exports net.jonbell.crochet.instrument;

    requires jdk.jlink;       // jdk.tools.jlink.plugin.*
    requires java.instrument; // JLinkRegistrationAgent.premain(Instrumentation)

    requires static org.objectweb.asm;
    requires static org.objectweb.asm.tree;
    requires static org.objectweb.asm.commons;
    requires static org.jacoco.core;
    requires static crochet.agent;
}
