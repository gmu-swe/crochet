package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

/**
 * SKETCH — not compiled. Design-only artifact for Gap 4 (array
 * checkpoint/rollback).
 *
 * <p>ClassVisitor that wraps typed array STORE opcodes with a preceding call
 * to {@code ArrayRegistry.beforeStore(arrayref)}. Sibling of
 * {@link FieldAccessWrapper}; mounts into the same transformer chain.
 *
 * <p>Instrumented opcodes:
 * <ul>
 *   <li>1-slot value stores: AASTORE, IASTORE, FASTORE, BASTORE, CASTORE,
 *       SASTORE — stack is {@code ..., arrayref, index, value}.
 *   <li>2-slot value stores: LASTORE, DASTORE — stack is
 *       {@code ..., arrayref, index, valueLo, valueHi} (one 64-bit value).
 * </ul>
 *
 * <p>Loads (AALOAD/IALOAD/...) are intentionally NOT wrapped in V1: they do
 * not change array state, and propagation runs separately.
 *
 * <p>Skipped method bodies: synthetic {@code $$crochet*}, {@code <init>},
 * {@code <clinit>}. Skipped owners: same prefix set as
 * {@link FieldAccessWrapper}.
 */
public final class ArrayAccessWrapper extends ClassVisitor {

    private static final String REGISTRY = "net/jonbell/crochet/runtime/ArrayRegistry";

    public ArrayAccessWrapper(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) return null;
        if (name.startsWith("$$crochet") || "<init>".equals(name) || "<clinit>".equals(name)) {
            return base;
        }
        // LocalVariablesSorter lets us reserve a scratch slot without clashing
        // with the method's declared locals.
        return new WrapStoresMV(api, access, descriptor, base);
    }

    /**
     * Per-method visitor. Uses {@link LocalVariablesSorter#newLocal(Type)} to
     * acquire a scratch arrayref slot per store site.
     */
    private static final class WrapStoresMV extends LocalVariablesSorter {

        WrapStoresMV(int api, int access, String descriptor, MethodVisitor delegate) {
            super(api, access, descriptor, delegate);
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
                // 1-slot value stores
                case Opcodes.AASTORE:
                case Opcodes.IASTORE:
                case Opcodes.FASTORE:
                case Opcodes.BASTORE:
                case Opcodes.CASTORE:
                case Opcodes.SASTORE:
                    emitNarrowStoreBarrier();
                    super.visitInsn(opcode);
                    return;

                // 2-slot value stores (long, double)
                case Opcodes.LASTORE:
                case Opcodes.DASTORE:
                    emitWideStoreBarrier();
                    super.visitInsn(opcode);
                    return;

                default:
                    super.visitInsn(opcode);
            }
        }

        /**
         * Stack in:  ..., arrayref, index, value
         * Stack out: ..., arrayref, index, value   (after we've called
         * {@code ArrayRegistry.beforeStore(arrayref)})
         *
         * <p>Strategy: spill index+value to scratch locals, call the hook with
         * the now-exposed arrayref at top of stack, restore index+value.
         * We use {@code newLocal} to avoid colliding with existing locals.
         */
        private void emitNarrowStoreBarrier() {
            // Reserve scratch locals. We spill the value's type precisely so the
            // verifier is happy; for AASTORE we use an Object slot, for IASTORE
            // an int slot, etc. For simplicity this sketch uses one
            // int/Object-sized slot — the integration MV will pick per opcode.
            int vSlot = newLocal(Type.INT_TYPE);       // suitable for I/F/B/C/S
            int idxSlot = newLocal(Type.INT_TYPE);

            // stack: ..., arr, idx, v
            super.visitVarInsn(Opcodes.ISTORE, vSlot);   // ..., arr, idx
            super.visitVarInsn(Opcodes.ISTORE, idxSlot); // ..., arr
            super.visitInsn(Opcodes.DUP);                // ..., arr, arr
            super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY,
                    "beforeStore", "(Ljava/lang/Object;)V", false);
            // ..., arr
            super.visitVarInsn(Opcodes.ILOAD, idxSlot);  // ..., arr, idx
            super.visitVarInsn(Opcodes.ILOAD, vSlot);    // ..., arr, idx, v
            // caller's super.visitInsn(STORE) lands next.
            //
            // NOTE for integration: for AASTORE the spill uses ASTORE/ALOAD and
            // Type.getObjectType("java/lang/Object"); for FASTORE use
            // FSTORE/FLOAD + Type.FLOAT_TYPE; etc. The sketch shows the int
            // case; the production visitor dispatches on the opcode.
        }

        /**
         * Stack in:  ..., arrayref, index, longOrDouble
         * Strategy: spill the 2-slot value + index, DUP arrayref, call hook,
         * unspill.
         */
        private void emitWideStoreBarrier() {
            int wideSlot = newLocal(Type.LONG_TYPE); // LSTORE/LLOAD works for D too in practice
            int idxSlot = newLocal(Type.INT_TYPE);

            // stack: ..., arr, idx, v_lo, v_hi
            super.visitVarInsn(Opcodes.LSTORE, wideSlot); // ..., arr, idx
            super.visitVarInsn(Opcodes.ISTORE, idxSlot);  // ..., arr
            super.visitInsn(Opcodes.DUP);                 // ..., arr, arr
            super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY,
                    "beforeStoreWide", "(Ljava/lang/Object;)V", false);
            // ..., arr
            super.visitVarInsn(Opcodes.ILOAD, idxSlot);   // ..., arr, idx
            super.visitVarInsn(Opcodes.LLOAD, wideSlot);  // ..., arr, idx, v
            // caller's super.visitInsn(LASTORE/DASTORE) lands next.
        }
    }
}
