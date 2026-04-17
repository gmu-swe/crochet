package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Gap 4 (bytecode): wraps every typed xASTORE with a pre-hook that calls
 * {@link net.jonbell.crochet.runtime.ArrayRegistry#beforeStore(Object)} so
 * lazy snapshot of checkpointed arrays is triggered on the first write.
 *
 * <p>Implementation is LVS-free — stacking multiple LocalVariablesSorters
 * (one per visitor that wanted scratch locals) produced cumulative index
 * rewrites that confused COMPUTE_FRAMES on large methods. Pure stack
 * gymnastics work for 1-slot value stores; 2-slot stores (LASTORE, DASTORE)
 * fall through unwrapped for now, matching the FieldAccessWrapper policy.
 */
public final class ArrayAccessWrapper extends ClassVisitor {

    private static final String REGISTRY_INTERNAL = "net/jonbell/crochet/runtime/ArrayRegistry";
    private static final String BEFORE_STORE_DESC = "(Ljava/lang/Object;)V";

    public ArrayAccessWrapper(int api, ClassVisitor delegate) {
        super(api, delegate);
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
        return new WrapStoresMV(api, base);
    }

    private static final class WrapStoresMV extends MethodVisitor {

        WrapStoresMV(int api, MethodVisitor delegate) {
            super(api, delegate);
        }

        @Override
        public void visitInsn(int opcode) {
            if (!isOneSlotArrayStore(opcode)) {
                super.visitInsn(opcode);
                return;
            }
            // stack: [..., arr, idx, val]   (val is 1-slot)
            super.visitInsn(Opcodes.DUP2_X1);
            // stack: [..., idx, val, arr, idx, val]
            super.visitInsn(Opcodes.POP2);
            // stack: [..., idx, val, arr]
            super.visitInsn(Opcodes.DUP);
            // stack: [..., idx, val, arr, arr]
            super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY_INTERNAL,
                    "beforeStore", BEFORE_STORE_DESC, false);
            // stack: [..., idx, val, arr]
            super.visitInsn(Opcodes.DUP_X2);
            // stack: [..., arr, idx, val, arr]
            super.visitInsn(Opcodes.POP);
            // stack: [..., arr, idx, val]
            super.visitInsn(opcode);
        }

        private static boolean isOneSlotArrayStore(int opcode) {
            return opcode == Opcodes.IASTORE || opcode == Opcodes.FASTORE
                    || opcode == Opcodes.AASTORE || opcode == Opcodes.BASTORE
                    || opcode == Opcodes.CASTORE || opcode == Opcodes.SASTORE;
        }
    }
}
