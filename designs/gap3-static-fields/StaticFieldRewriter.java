// SKETCH — design-only. Not compiled. Lives in /designs/ to show the shape of
// the new visitor. Final code will go under crochet-agent/src/main/java/.
//
// Placement in the transform chain (see CrochetTransformer#transform):
//
//     chain = new LookupInjector(ASM9, chain);
//     chain = new StaticFieldRewriter(ASM9, chain);   // NEW — runs first on methods
//     chain = new FieldAccessWrapper(ASM9, chain);
//     chain = new FieldAdder(ASM9, chain);
//     reader.accept(chain, 0);
//
// StaticFieldRewriter must be AFTER FieldAdder conceptually (so the helper
// class FieldAdder will produce exists), but since FieldAdder operates on the
// user class and StaticFieldRewriter operates on bytecode *inside* the user
// class, the visitor order in the chain doesn't matter for correctness — what
// matters is that the helper class is generated lazily at *runtime*, not at
// user-class-load time.

package net.jonbell.crochet.transform;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

/**
 * Rewrites GETSTATIC/PUTSTATIC on instrumentable user classes to a call
 * through the StaticFieldHelper singleton. See designs/gap3-static-fields/DESIGN.md.
 *
 * <p>The rewrite replaces the instruction with:
 * <ol>
 *   <li>Push {@code owner.class}.
 *   <li>Invoke {@code CheckpointRollbackAgent.sfHelper(Class)} → CRIJInstrumented.
 *   <li>DUP; invoke {@code $$crochetAccess()} for the lazy klass-swap hook.
 *   <li>GETFIELD / (SWAP or spill-reload +) PUTFIELD on the helper with the
 *       original field's name and descriptor.
 * </ol>
 */
public final class StaticFieldRewriter extends ClassVisitor {

    private static final String AGENT = "net/jonbell/crochet/runtime/CheckpointRollbackAgent";
    private static final String CRIJ  = "net/jonbell/crochet/runtime/CRIJInstrumented";
    private static final String SF_HELPER_DESC = "(Ljava/lang/Class;)L" + CRIJ + ";";

    private String thisClassInternal;

    public StaticFieldRewriter(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.thisClassInternal = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        if (mv == null) return null;

        // Skip initializers (clinit writes through originals — see §3) and
        // Crochet's own methods.
        if (name.startsWith("$$crochet") || "<clinit>".equals(name)) return mv;

        // LocalVariablesSorter is needed for the 2-slot PUTSTATIC case so we
        // can spill the value into a fresh local. We always wrap in the
        // sorter — it's cheap when unused.
        return new Rewriter(api, access, desc, new LocalVariablesSorter(api, access, desc, mv),
                            thisClassInternal);
    }

    /** Per-method visitor that actually rewrites GETSTATIC/PUTSTATIC. */
    private static final class Rewriter extends MethodVisitor {

        private final LocalVariablesSorter lvs;
        private final String thisClassInternal;

        Rewriter(int api, int access, String desc, LocalVariablesSorter delegate,
                 String thisClassInternal) {
            super(api, delegate);
            this.lvs = delegate;
            this.thisClassInternal = thisClassInternal;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (!shouldRewrite(opcode, owner, name, desc)) {
                super.visitFieldInsn(opcode, owner, name, desc);
                return;
            }
            String helperInternal = helperInternalFor(owner);
            switch (opcode) {
                case Opcodes.GETSTATIC -> rewriteGet(owner, helperInternal, name, desc);
                case Opcodes.PUTSTATIC -> {
                    if (isTwoSlot(desc)) rewritePutTwoSlot(owner, helperInternal, name, desc);
                    else rewritePutOneSlot(owner, helperInternal, name, desc);
                }
                default -> super.visitFieldInsn(opcode, owner, name, desc);
            }
        }

        // ---------- the four rewrite shapes ----------

        /** GETSTATIC (1- or 2-slot; semantics identical — see DESIGN.md §2). */
        private void rewriteGet(String owner, String helperInternal, String name, String desc) {
            loadHelperAndTouch(owner);
            // stack: [..., helper]
            super.visitFieldInsn(Opcodes.GETFIELD, helperInternal, name, desc);
            // stack: [..., value(1 or 2 slots)]
        }

        /** PUTSTATIC on a 1-slot primitive/reference. */
        private void rewritePutOneSlot(String owner, String helperInternal, String name, String desc) {
            // stack: [..., value]
            loadHelperAndTouch(owner);
            // stack: [..., value, helper]
            super.visitInsn(Opcodes.SWAP);
            // stack: [..., helper, value]
            super.visitFieldInsn(Opcodes.PUTFIELD, helperInternal, name, desc);
        }

        /** PUTSTATIC on a 2-slot J/D. SWAP is not legal; spill through a local. */
        private void rewritePutTwoSlot(String owner, String helperInternal, String name, String desc) {
            Type t = Type.getType(desc);
            int storeOp = t.getOpcode(Opcodes.ISTORE);   // LSTORE or DSTORE
            int loadOp  = t.getOpcode(Opcodes.ILOAD);    // LLOAD  or DLOAD
            int slot    = lvs.newLocal(t);
            // stack: [..., value(2 slots)]
            super.visitVarInsn(storeOp, slot);
            // stack: [...]
            loadHelperAndTouch(owner);
            // stack: [..., helper]
            super.visitVarInsn(loadOp, slot);
            // stack: [..., helper, value(2 slots)]
            super.visitFieldInsn(Opcodes.PUTFIELD, helperInternal, name, desc);
        }

        // ---------- shared subsequences ----------

        /** LDC owner.class; INVOKESTATIC sfHelper; DUP; INVOKEINTERFACE $$crochetAccess. */
        private void loadHelperAndTouch(String owner) {
            super.visitLdcInsn(Type.getObjectType(owner));
            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "sfHelper",
                                  SF_HELPER_DESC, false);
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, CRIJ,
                                  "$$crochetAccess", "()V", true);
        }

        private static boolean isTwoSlot(String desc) {
            return "J".equals(desc) || "D".equals(desc);
        }

        private boolean shouldRewrite(int opcode, String owner, String name, String desc) {
            if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.PUTSTATIC) return false;
            if (owner == null) return false;
            if (owner.startsWith("java/") || owner.startsWith("jdk/")
                    || owner.startsWith("sun/") || owner.startsWith("com/sun/")) return false;
            if (owner.startsWith("net/jonbell/crochet/")) return false;
            // Skip synthetic/crochet slots that FieldAdder itself may add to this class.
            if (name.startsWith("$$crochet")) return false;
            // TODO(design Q): skip static final primitive / String — javac inlines reads,
            // so we'd never see the GETSTATIC anyway for a true compile-time constant,
            // but a literal PUTSTATIC in <clinit> for the constant still appears. We
            // already skip <clinit> above, so this shouldn't fire; sanity-check later.
            return true;
        }

        private static String helperInternalFor(String userInternal) {
            return userInternal + "$$crochetSFHelper";
        }
    }
}
</content>
</invoke>