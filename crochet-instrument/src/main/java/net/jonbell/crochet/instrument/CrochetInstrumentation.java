/*
 * Derived from Galette (https://github.com/neu-se/galette),
 * Copyright (c) 2024, Katherine Hough and Jonathan Bell — BSD 3-Clause.
 * Port adaptations for Crochet.
 */
package net.jonbell.crochet.instrument;

import net.jonbell.crochet.patch.Patcher;
import net.jonbell.crochet.runtime.Tag;
import net.jonbell.crochet.transform.CrochetTransformer;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Instances of this class are created via reflection.
 */
@SuppressWarnings("unused")
public class CrochetInstrumentation implements Instrumentation {
    private CrochetTransformer transformer;
    private Set<File> classPathElements;

    @Override
    public void configure(Properties options) {
        transformer = new CrochetTransformer();
        classPathElements = new HashSet<>();
        classPathElements.add(InstrumentUtil.getClassPathElement(Tag.class));
    }

    @Override
    public Properties getOptions() {
        return new Properties();
    }

    @Override
    public java.util.Set<File> getClassPathElements() {
        return classPathElements;
    }

    @Override
    public byte[] apply(byte[] classFileBuffer) {
        try {
            return transformer.transform(classFileBuffer, false);
        } catch (Throwable t) {
            // Transform failure at build time (jlink). Most commonly this is
            // ASM's COMPUTE_FRAMES reflecting on a superclass that is not on
            // the jlink classpath, or a class file too large to emit after
            // adding the crochet surface. Returning null preserves the
            // original bytes so the image still builds; the class just won't
            // gain the crochet surface.
            System.err.println("[crochet] transform failed; keeping original bytes: " + t);
            return null;
        }
    }

    @Override
    public BiFunction<String, byte[], byte[]> createPatcher(Function<String, byte[]> entryLocator) {
        Patcher patcher = new Patcher(entryLocator);
        return patcher::patch;
    }

    @Override
    public Set<String> getRequiredModules() {
        return new HashSet<>(Arrays.asList("java.base", "jdk.jdwp.agent", "java.instrument", "jdk.unsupported"));
    }

    @Override
    public boolean shouldPack(String resourceName) {
        // Pack only the classes that instrumented user/JCL code actually
        // references at runtime: the runtime support (CheckpointRollbackAgent,
        // CRIJInstrumented, ClassMeta, ...), the transform support the runtime
        // calls back into (ProxyTemplate, Specializer), the @CrochetInstrumented
        // marker, the patch helpers, and the agent's shaded ASM package
        // (which Specializer / ProxyTemplate reach into to emit hidden proxy
        // bytes). Agent entry points (CrochetAgent, TransformerWrapper)
        // implement java.lang.instrument.ClassFileTransformer and must stay
        // out of java.base — java.base does not read java.instrument. The
        // agent jar is attached via -javaagent from outside java.base.
        return resourceName.startsWith(CrochetTransformer.RUNTIME_PACKAGE_PREFIX)
                || resourceName.startsWith(CrochetTransformer.TRANSFORM_PACKAGE_PREFIX)
                || resourceName.startsWith("net/jonbell/crochet/annotation/")
                || resourceName.startsWith("net/jonbell/crochet/patch/")
                // The shaded ASM package relocated by maven-shade-plugin.
                // The shadow pattern is org.objectweb.asm → edu.neu.ccs.prl.crochet.agent.shaded.asm,
                // so the internal-name prefix is edu/neu/ccs/prl/crochet/agent/shaded/.
                // (The old comment said "net/jonbell/crochet/agent/shaded/" but that path
                // does not exist in the shaded jar — the correct prefix is below.)
                || resourceName.startsWith("edu/neu/ccs/prl/crochet/agent/shaded/");
    }

    @Override
    public Set<File> getElementsToPack() {
        return classPathElements;
    }
}
