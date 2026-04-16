package net.jonbell.crochet.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

final class TransformerWrapper implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        return null;
    }
}
