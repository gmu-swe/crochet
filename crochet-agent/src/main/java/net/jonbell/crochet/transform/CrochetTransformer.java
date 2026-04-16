package net.jonbell.crochet.transform;

public class CrochetTransformer {

    public static final String RUNTIME_PACKAGE_PREFIX = "net/jonbell/crochet/runtime/";

    public static final String TRANSFORM_PACKAGE_PREFIX = "net/jonbell/crochet/transform/";

    public byte[] transform(byte[] classFileBuffer, boolean hostedAnonymous) {
        return classFileBuffer;
    }
}
