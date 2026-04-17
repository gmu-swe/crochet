package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
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
 */
public final class ArrayAccessWrapper extends ClassVisitor {

    private static final String REGISTRY_INTERNAL = "net/jonbell/crochet/runtime/ArrayRegistry";
    private static final String BEFORE_STORE_DESC = "(Ljava/lang/Object;)V";

    private final SharedLocalsProvider locals;

    public ArrayAccessWrapper(int api, ClassVisitor delegate, SharedLocalsProvider locals) {
        super(api, delegate);
        this.locals = locals;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) {
            return null;
        }
        if (name.startsWith("$$crochet")
                || "<init>".equals(name)
                || "<clinit>".equals(name)) {
            return base;
        }
        return new WrapStoresMV(api, base, locals);
    }

    private static final class WrapStoresMV extends MethodVisitor {
        private final SharedLocalsProvider locals;

        WrapStoresMV(int api, MethodVisitor delegate, SharedLocalsProvider locals) {
            super(api, delegate);
            this.locals = locals;
        }

        @Override
        public void visitInsn(int opcode) {
            Type vt = valueTypeFor(opcode);
            if (vt == null) {
                super.visitInsn(opcode);
                return;
            }
            int slot = locals.sharedScratch(vt);
            int storeOp = vt.getOpcode(Opcodes.ISTORE);
            int loadOp = vt.getOpcode(Opcodes.ILOAD);
            // Scratch store/load emitted via locals.emitVarInsn — bypasses
            // the LVS remap table so the slot lands at its allocated index
            // rather than being aliased with an original local of the same
            // numeric index (LVS keys remap by var+size, not by type).
            // stack: [..., arr, idx, val]    (val is 1 or 2 slots)
            locals.emitVarInsn(storeOp, slot);
            // stack: [..., arr, idx]
            super.visitInsn(Opcodes.SWAP);
            // stack: [..., idx, arr]
            super.visitInsn(Opcodes.DUP);
            // stack: [..., idx, arr, arr]
            super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY_INTERNAL,
                    "beforeStore", BEFORE_STORE_DESC, false);
            // stack: [..., idx, arr]
            super.visitInsn(Opcodes.SWAP);
            // stack: [..., arr, idx]
            locals.emitVarInsn(loadOp, slot);
            // stack: [..., arr, idx, val]
            super.visitInsn(opcode);
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
