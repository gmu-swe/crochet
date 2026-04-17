package net.jonbell.crochet.transform;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;

/**
 * Stamps {@code @CrochetInstrumented} onto every class that passes through the
 * transform chain. Both the jlink-time build and the runtime -javaagent path
 * run through this visitor, so the annotation is the single source of truth
 * for "this class has been rewritten".
 */
final class AnnotationStamper extends ClassVisitor {

    AnnotationStamper(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        AnnotationVisitor av = super.visitAnnotation(
                CrochetTransformer.CROCHET_INSTRUMENTED_DESC, false);
        if (av != null) {
            av.visitEnd();
        }
    }
}
