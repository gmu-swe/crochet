package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Wraps GETFIELD/PUTFIELD instructions whose owner is an instrumented user
 * class with a preceding call to {@code ownerRef.$$crochetAccess()}. When the
 * ownerRef is of a user-class type this runs the no-op base implementation;
 * when the ownerRef's klass has been swapped to the Fast proxy, this triggers
 * the lazy snapshot/restore in
 * {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#fastAccess}.
 *
 * <p>1-slot (Z, B, C, S, I, F, L, [) descriptors wrap via pure SWAP/DUP/SWAP.
 * 2-slot (J, D) descriptors stash the value in a scratch local allocated from
 * the chain-wide {@link SharedLocalsProvider} — the visitor never extends a
 * {@link org.objectweb.asm.commons.LocalVariablesSorter} of its own; multiple
 * stacked LVS instances produced cumulative index rewrites that confused
 * {@code COMPUTE_FRAMES} on large methods (fop's FObj, h2's Parser).
 *
 * <p>See {@link WrapAccessesMV#emitPreHook} for the emit-shape design
 * rationale (INVOKEVIRTUAL vs INVOKESTATIC-with-instanceof trade-off).
 *
 * <p>{@code <clinit>} takes the full skip — static initialisers are special
 * because our own {@code $$crochet*} field initialisers would recurse into
 * the static rewriter. {@code <init>} is handled via
 * {@link CtorAwareMv}: pre-super instructions forward unchanged (we cannot
 * read fields of {@code this} before the super-call), post-super instructions
 * are wrapped the same way as any ordinary method.
 */
public final class FieldAccessWrapper extends ClassVisitor {

    private final SharedLocalsProvider locals;
    private String className;
    private String superName;

    public FieldAccessWrapper(int api, ClassVisitor delegate, SharedLocalsProvider locals) {
        super(api, delegate);
        this.locals = locals;
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
        return new WrapAccessesMV(api, base, locals, className, superName, isCtor);
    }

    private static final class WrapAccessesMV extends CtorAwareMv {
        private final SharedLocalsProvider locals;

        WrapAccessesMV(int api, MethodVisitor delegate, SharedLocalsProvider locals,
                       String owner, String superName, boolean isCtor) {
            super(api, delegate, owner, superName, isCtor);
            this.locals = locals;
        }

        /**
         * Emit the pre-hook for a GETFIELD/PUTFIELD receiver currently on
         * top of stack: {@code INVOKEVIRTUAL owner.$$crochetAccess()V}.
         *
         * <p><b>Alternative evaluated and reverted:</b> a klass-guarded
         * static call of shape {@code INVOKESTATIC
         * CheckpointRollbackAgent.fastAccessIfProxy(Object)V}, whose body
         * would be {@code if (obj instanceof CRIJFast) fastAccess((CRIJInstrumented) obj);}.
         * The intent was to avoid vtable dispatch for the common case
         * where the receiver's klass has not been swapped to a Fast proxy.
         *
         * <p>Direct measurement (graphchi/lusearch/h2 @ -s small -n 3, 5-10
         * runs each) showed the static-call variant was, on this hardware,
         * either slightly faster (graphchi, lusearch, within ~5% noise
         * band) or a net regression (h2, ~20-40% slower across multiple
         * 5-run and 10-run trials). The INVOKEVIRTUAL path benefits from
         * the JIT's profile-guided devirtualization of
         * {@code $$crochetAccess} on monomorphic-to-user-class sites — the
         * user class's {@code $$crochetAccess} body is a single RETURN, so
         * after the JIT inlines it the site costs ~0 cycles. An INSTANCEOF
         * {@code CRIJFast} check inside a static callee always pays a
         * secondary-super-cache check, which in h2's many-class workload
         * is measurably more expensive than the inlined no-op. The
         * static-call variant also did not measurably help graphchi once
         * run-to-run variance was controlled for (10-iteration medians on
         * both approaches land within 1σ of each other).
         *
         * <p>The alternative "inline class-check" emit
         * ({@code DUP / INVOKEVIRTUAL Object.getClass() / INVOKESTATIC
         * CRIJFast.isProxy / IFEQ / ...}) was evaluated conceptually but
         * inflates per-site bytecode by ~6 instructions + a stackmap
         * frame — expensive for tradebeans-class-heavy workloads with
         * &gt;10k emit sites. Not implemented.
         */
        private static void emitPreHook(MethodVisitor mv, String fOwner) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, fOwner,
                    "$$crochetAccess", "()V", false);
        }

        @Override
        protected void visitFieldInsnPostSuper(int opcode, String fOwner, String name, String descriptor) {
            if (!shouldWrap(opcode, fOwner, descriptor)) {
                super.visitFieldInsnPostSuper(opcode, fOwner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.GETFIELD) {
                // stack: [..., objref]
                mv.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                emitPreHook(mv, fOwner);
                // stack: [..., objref]
                mv.visitFieldInsn(opcode, fOwner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.PUTFIELD) {
                boolean twoSlot = "J".equals(descriptor) || "D".equals(descriptor);
                if (!twoSlot) {
                    // stack: [..., objref, value]
                    mv.visitInsn(Opcodes.SWAP);
                    // stack: [..., value, objref]
                    mv.visitInsn(Opcodes.DUP);
                    // stack: [..., value, objref, objref]
                    emitPreHook(mv, fOwner);
                    // stack: [..., value, objref]
                    mv.visitInsn(Opcodes.SWAP);
                    // stack: [..., objref, value]
                    mv.visitFieldInsn(opcode, fOwner, name, descriptor);
                    return;
                }
                // 2-slot PUTFIELD: stash the wide value in a scratch local
                // pulled from the chain-wide LVS, leaving objref on top so we
                // can duplicate it for the hook. Scratch store/load are
                // emitted via locals.emitVarInsn so they bypass the LVS's
                // remap table — that table keys on (var, size) not type, and
                // would otherwise alias our scratch with an original slot
                // that happens to share the numeric index.
                Type vt = "J".equals(descriptor) ? Type.LONG_TYPE : Type.DOUBLE_TYPE;
                int slot = locals.sharedScratch(vt);
                int storeOp = vt.getOpcode(Opcodes.ISTORE);
                int loadOp = vt.getOpcode(Opcodes.ILOAD);
                // stack: [..., objref, v_hi, v_lo]
                locals.emitVarInsn(storeOp, slot);
                // stack: [..., objref]
                mv.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                emitPreHook(mv, fOwner);
                // stack: [..., objref]
                locals.emitVarInsn(loadOp, slot);
                // stack: [..., objref, v_hi, v_lo]
                mv.visitFieldInsn(opcode, fOwner, name, descriptor);
                return;
            }
            // GETSTATIC/PUTSTATIC filtered out by shouldWrap; defensive pass-through.
            super.visitFieldInsnPostSuper(opcode, fOwner, name, descriptor);
        }

        private static boolean shouldWrap(int opcode, String owner, String descriptor) {
            if (opcode != Opcodes.GETFIELD && opcode != Opcodes.PUTFIELD) {
                return false;
            }
            if (owner == null) {
                return false;
            }
            if (owner.startsWith("java/") || owner.startsWith("jdk/")
                    || owner.startsWith("sun/") || owner.startsWith("com/sun/")) {
                return false;
            }
            if (owner.startsWith("net/jonbell/crochet/")) {
                return false;
            }
            return true;
        }
    }
}
