package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.JSRInlinerAdapter;

/**
 * Replaces JSR/RET subroutines with equivalent straight-line code so
 * {@code COMPUTE_FRAMES} can process pre-Java-6 class files.
 *
 * <p>Classes at major version &lt; 50 (Java 1.5 and earlier) may use the
 * {@code jsr} / {@code ret} instructions for {@code try/finally} subroutines.
 * Their old inference-based verifier doesn't compose with the
 * {@code StackMapTable}-carrying methods the rest of the transform chain
 * emits, and ASM's {@code COMPUTE_FRAMES} refuses to process JSR/RET at all.
 * {@code commons-logging 1.x} ships at major=45 and trips this on DaCapo.
 *
 * <p>{@link JSRInlinerAdapter} (provided by ASM) buffers each method into a
 * {@code MethodNode}, inlines every subroutine body at each call site, and
 * emits the JSR/RET-free result downstream. It is a no-op on modern bytecode
 * (major &ge; 50), so this visitor can — and should — sit at the top of the
 * chain and apply unconditionally. Once FieldAdder has bumped the output
 * class-file version to V1_7, the inlined result is valid modern bytecode.
 */
public final class JsrInliner extends ClassVisitor {

    public JsrInliner(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) {
            return null;
        }
        return new JSRInlinerAdapter(base, access, name, descriptor, signature, exceptions);
    }
}
