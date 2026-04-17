package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

/**
 * Gap 4 (bytecode): wraps every typed xASTORE with a pre-hook that calls
 * {@link net.jonbell.crochet.runtime.ArrayRegistry#beforeStore(Object)}.
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
        LocalVariablesSorter lvs = new LocalVariablesSorter(access, descriptor, base);
        return new WrapStoresMV(api, lvs, lvs);
    }

    private static final class WrapStoresMV extends MethodVisitor {
        private final LocalVariablesSorter lvs;

        WrapStoresMV(int api, MethodVisitor delegate, LocalVariablesSorter lvs) {
            super(api, delegate);
            this.lvs = lvs;
        }

        @Override
        public void visitInsn(int opcode) {
            Type valueType = valueTypeOf(opcode);
            if (valueType == null) {
                super.visitInsn(opcode);
                return;
            }
            int valueSlot = lvs.newLocal(valueType);
            int idxSlot = lvs.newLocal(Type.INT_TYPE);
            int valueStoreOp = valueType.getOpcode(Opcodes.ISTORE);
            int valueLoadOp = valueType.getOpcode(Opcodes.ILOAD);

            super.visitVarInsn(valueStoreOp, valueSlot);
            super.visitVarInsn(Opcodes.ISTORE, idxSlot);
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY_INTERNAL,
                    "beforeStore", BEFORE_STORE_DESC, false);
            super.visitVarInsn(Opcodes.ILOAD, idxSlot);
            super.visitVarInsn(valueLoadOp, valueSlot);
            super.visitInsn(opcode);
        }

        private static Type valueTypeOf(int opcode) {
            return switch (opcode) {
                case Opcodes.IASTORE -> Type.INT_TYPE;
                case Opcodes.LASTORE -> Type.LONG_TYPE;
                case Opcodes.FASTORE -> Type.FLOAT_TYPE;
                case Opcodes.DASTORE -> Type.DOUBLE_TYPE;
                case Opcodes.AASTORE -> Type.getObjectType("java/lang/Object");
                case Opcodes.BASTORE -> Type.INT_TYPE;
                case Opcodes.CASTORE -> Type.INT_TYPE;
                case Opcodes.SASTORE -> Type.INT_TYPE;
                default -> null;
            };
        }
    }
}
