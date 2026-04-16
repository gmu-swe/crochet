package net.jonbell.crochet.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import net.jonbell.crochet.transform.CrochetTransformer;

final class TransformerWrapper implements ClassFileTransformer {

    private final CrochetTransformer delegate = new CrochetTransformer();

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        try {
            return delegate.transform(classfileBuffer, false);
        } catch (Throwable t) {
            return null;
        }
    }
}
