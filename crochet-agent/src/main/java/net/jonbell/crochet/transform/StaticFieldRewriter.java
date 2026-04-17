package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Gap 3 (bytecode): inserts a runtime registration pre-hook before every
 * GETSTATIC / PUTSTATIC targeting an instrumented user class.
 *
 * <p>Pre-hook emits {@code sfHelperFor(owner.class).$$crochetAccess()} — net
 * stack delta of zero (1-slot helper ref pushed then consumed). Works without
 * any scratch local, so this visitor doesn't need the
 * {@link SharedLocalsProvider}. PUTSTATIC's existing 1- or 2-slot value sits
 * below the transient helper-ref and stays intact for the wrapped PUTSTATIC.
 *
 * <p>{@code <clinit>} takes the full skip to avoid recursion into the static
 * rewriter via our own $$crochet* field initialisers. {@code <init>}
 * forwards pre-super instructions unchanged via {@link CtorAwareMv} — the
 * pre-hook only fires post-super so instance construction doesn't pay
 * noteStaticAccess for statics referenced from super-call arguments.
 */
public final class StaticFieldRewriter extends ClassVisitor {

    private static final String AGENT_INTERNAL = "net/jonbell/crochet/runtime/CheckpointRollbackAgent";
    /**
     * Descriptor of the fused pre-hook. The agent-side implementation does the
     * {@code sfHelperFor(C).$$crochetAccess()} job in a single static call so
     * the JIT can inline the fast path; previously the emitted two-call
     * pattern ({@code INVOKESTATIC sfHelperFor} + {@code INVOKEINTERFACE
     * $$crochetAccess}) forced the JIT to itable-lookup through an open
     * polymorphic world of generated SF-helper classes, which dominated
     * WildFly startup (see Logger$Level @ 8.3M hits in
     * {@code /tmp/crochet-runtime-counts.log}).
     */
    private static final String NOTE_STATIC_ACCESS_DESC = "(Ljava/lang/Class;)V";

    private String className;
    private String superName;

    public StaticFieldRewriter(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name;
        this.superName = superName;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) {
            return null;
        }
        if (name.startsWith("$$crochet") || "<clinit>".equals(name)) {
            return base;
        }
        boolean isCtor = "<init>".equals(name);
        return new WrapStaticsMV(api, base, className, superName, isCtor);
    }

    private static final class WrapStaticsMV extends CtorAwareMv {

        WrapStaticsMV(int api, MethodVisitor delegate, String owner, String superName, boolean isCtor) {
            super(api, delegate, owner, superName, isCtor);
        }

        /**
         * GETSTATIC/PUTSTATIC both handled without any scratch local —
         * invokestatic pushes a 1-slot helper reference, invokevirtual
         * consumes it. The original stack shape underneath is preserved,
         * so any existing 2-slot value for PUTSTATIC stays intact.
         */
        @Override
        protected void visitFieldInsnPostSuper(int opcode, String owner, String name, String descriptor) {
            if (!shouldWrap(opcode, owner, name)) {
                super.visitFieldInsnPostSuper(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.GETSTATIC) {
                // stack: [...]
                emitPreHook(owner);
                // stack: [...]
                mv.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.PUTSTATIC) {
                // stack: [..., value]  (value is 1 or 2 slots, doesn't matter)
                // Each emitted call pushes then pops exactly one slot, so
                // value is untouched at the bottom when we hit PUTSTATIC.
                emitPreHook(owner);
                // stack: [..., value]
                mv.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            super.visitFieldInsnPostSuper(opcode, owner, name, descriptor);
        }

        private void emitPreHook(String owner) {
            mv.visitLdcInsn(Type.getObjectType(owner));
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL,
                    "noteStaticAccess", NOTE_STATIC_ACCESS_DESC, false);
        }

        private static boolean shouldWrap(int opcode, String owner, String name) {
            if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.PUTSTATIC) {
                return false;
            }
            if (owner == null || name == null) {
                return false;
            }
            if (owner.startsWith("java/") || owner.startsWith("jdk/")
                    || owner.startsWith("sun/") || owner.startsWith("com/sun/")) {
                return false;
            }
            if (owner.startsWith("net/jonbell/crochet/")) {
                return false;
            }
            if (name.startsWith("$$crochet")) {
                return false;
            }
            return true;
        }
    }
}
