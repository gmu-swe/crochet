package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Gap 4 (bytecode): wraps every typed xASTORE with a pre-hook that calls
 * {@link net.jonbell.crochet.runtime.ArrayRegistry#beforeStore(Object)} so
 * lazy snapshot of checkpointed arrays is triggered on the first write.
 *
 * <p>Implementation uses a scratch local pulled from the chain-wide
 * {@link SharedLocalsProvider} to stash the value (1-slot or 2-slot) while
 * the array reference is duplicated for the hook. Uniform across all typed
 * stores (IASTORE/BASTORE/CASTORE/SASTORE/FASTORE/AASTORE/LASTORE/DASTORE) —
 * prior pure-stack implementation left LASTORE/DASTORE unwrapped because the
 * [arr, idx, v_hi, v_lo] shape has no clean DUP_X/POP permutation.
 *
 * <p>{@code <clinit>} takes the full skip; {@code <init>} forwards pre-super
 * xASTOREs unchanged via {@link CtorAwareMv} (they can't reach an array
 * field of {@code this} and any array built for the super-call is its own
 * transient value). Post-super xASTOREs wrap the same as in any method.
 */
public final class ArrayAccessWrapper extends ClassVisitor {

    /**
     * Bootstrap-safe forwarder. See {@code RuntimeReady} javadoc for
     * why we route through it instead of calling
     * {@code ArrayRegistry.beforeStore} directly: early-JVM
     * invocations from instrumented JDK classes observe
     * {@code READY == false} and return immediately, avoiding
     * re-entry into {@code ArrayRegistry}'s class-init chain.
     */
    private static final String REGISTRY_INTERNAL = "net/jonbell/crochet/runtime/RuntimeReady";
    private static final String BEFORE_STORE_DESC = "(Ljava/lang/Object;)V";

    private final SharedLocalsProvider locals;
    private String className;
    private String superName;

    public ArrayAccessWrapper(int api, ClassVisitor delegate, SharedLocalsProvider locals) {
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
        return new WrapStoresMV(api, base, locals, className, superName, isCtor);
    }

    private static final class WrapStoresMV extends CtorAwareMv {
        private final SharedLocalsProvider locals;

        WrapStoresMV(int api, MethodVisitor delegate, SharedLocalsProvider locals,
                     String owner, String superName, boolean isCtor) {
            super(api, delegate, owner, superName, isCtor);
            this.locals = locals;
        }

        @Override
        protected void visitInsnPostSuper(int opcode) {
            Type vt = valueTypeFor(opcode);
            if (vt == null) {
                super.visitInsnPostSuper(opcode);
                return;
            }
            int slot = locals.sharedScratch(vt);
            int storeOp = vt.getOpcode(Opcodes.ISTORE);
            int loadOp = vt.getOpcode(Opcodes.ILOAD);
            // Site-level VERSION_GATE check: skip the entire stash + dup +
            // hook + restore dance when no checkpoint has fired. The hook
            // shape would still produce a no-op via {@link RuntimeReady#beforeStore}
            // but inlining the gate at every xASTORE site lets the
            // interpreter / C1 tier avoid the dispatch entirely.
            // stack: [..., arr, idx, val]
            Label skip = new Label();
            mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "net/jonbell/crochet/runtime/RuntimeReady",
                    "VERSION_GATE", "I");
            mv.visitJumpInsn(Opcodes.IFEQ, skip);
            // Scratch store/load emitted via locals.emitVarInsn — bypasses
            // the LVS remap table so the slot lands at its allocated index
            // rather than being aliased with an original local of the same
            // numeric index (LVS keys remap by var+size, not by type).
            // stack: [..., arr, idx, val]    (val is 1 or 2 slots)
            locals.emitVarInsn(storeOp, slot);
            // stack: [..., arr, idx]
            mv.visitInsn(Opcodes.SWAP);
            // stack: [..., idx, arr]
            mv.visitInsn(Opcodes.DUP);
            // stack: [..., idx, arr, arr]
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY_INTERNAL,
                    "beforeStore", BEFORE_STORE_DESC, false);
            // stack: [..., idx, arr]
            mv.visitInsn(Opcodes.SWAP);
            // stack: [..., arr, idx]
            locals.emitVarInsn(loadOp, slot);
            // stack: [..., arr, idx, val]
            mv.visitLabel(skip);
            // Both paths converge with stack [..., arr, idx, val] — the hook
            // path roundtripped val through the scratch local; the skip path
            // never disturbed it.
            mv.visitInsn(opcode);
        }

        private static Type valueTypeFor(int opcode) {
            switch (opcode) {
                case Opcodes.IASTORE: return Type.INT_TYPE;
                case Opcodes.FASTORE: return Type.FLOAT_TYPE;
                case Opcodes.AASTORE: return OBJECT_TYPE;
                case Opcodes.BASTORE: return Type.BYTE_TYPE;
                case Opcodes.CASTORE: return Type.CHAR_TYPE;
                case Opcodes.SASTORE: return Type.SHORT_TYPE;
                case Opcodes.LASTORE: return Type.LONG_TYPE;
                case Opcodes.DASTORE: return Type.DOUBLE_TYPE;
                default: return null;
            }
        }

        private static final Type OBJECT_TYPE = Type.getObjectType("java/lang/Object");
    }
}
