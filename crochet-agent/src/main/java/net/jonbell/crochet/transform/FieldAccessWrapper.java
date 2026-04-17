package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

/**
 * Wraps GETFIELD/PUTFIELD instructions whose owner is an instrumented user
 * class with a preceding call to {@code ownerRef.$$crochetAccess()}. When the
 * ownerRef is of a user-class type, this runs the no-op base implementation;
 * when the ownerRef's klass has been swapped to the Fast proxy, this triggers
 * the lazy snapshot/restore in {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#fastAccess}.
 *
 * <p>Handles both 1-slot (Z, B, C, S, I, F, L, [) and 2-slot (J, D) field
 * descriptors. 1-slot is handled with a pure SWAP/DUP dance; 2-slot uses a
 * fresh local variable (allocated by {@link LocalVariablesSorter}) to stash
 * the value while we poke the object reference.
 *
 * <p>Skips synthetic CROCHET methods ({@code $$crochet*}) and initializers
 * ({@code <init>}, {@code <clinit>}) to avoid recursion and pre-super access
 * issues respectively.
 */
public final class FieldAccessWrapper extends ClassVisitor {

    public FieldAccessWrapper(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) {
            return null;
        }
        if (name.startsWith("$$crochet") || "<init>".equals(name) || "<clinit>".equals(name)) {
            return base;
        }
        // We wrap 2-slot PUTFIELD by stashing the value in a synthesized local
        // variable. LocalVariablesSorter gives us a fresh slot and fixes up
        // maxLocals; wiring it between the base MV and our visitor ensures our
        // visitVarInsn(Opcodes.LSTORE/DSTORE, -1) calls are remapped correctly.
        LocalVariablesSorter lvs = new LocalVariablesSorter(access, descriptor, base);
        WrapAccessesMV wrap = new WrapAccessesMV(api, lvs, lvs);
        return wrap;
    }

    private static final class WrapAccessesMV extends MethodVisitor {
        private final LocalVariablesSorter lvs;

        WrapAccessesMV(int api, MethodVisitor delegate, LocalVariablesSorter lvs) {
            super(api, delegate);
            this.lvs = lvs;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!shouldWrap(opcode, owner, descriptor)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            boolean twoSlot = "J".equals(descriptor) || "D".equals(descriptor);
            if (opcode == Opcodes.GETFIELD) {
                // GETFIELD consumes [objref] and pushes [value]. The returned
                // value width (1 vs 2 slots) doesn't matter here because the
                // hook runs BEFORE we execute the actual GETFIELD — we only
                // duplicate the 1-slot objref on the stack.
                //
                // stack: [..., objref]
                super.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner,
                        "$$crochetAccess", "()V", false);
                // stack: [..., objref]
                super.visitFieldInsn(opcode, owner, name, descriptor);
                // stack: [..., value]  (value is 1 or 2 slots depending on descriptor)
                return;
            }
            // PUTFIELD: consumes [objref, value]. For 1-slot we keep the old
            // stack dance; for 2-slot we stash via a synthesized local.
            if (opcode == Opcodes.PUTFIELD) {
                if (!twoSlot) {
                    // --- 1-slot PUTFIELD (unchanged from V1) ---
                    // stack: [..., objref, value]
                    super.visitInsn(Opcodes.SWAP);
                    // stack: [..., value, objref]
                    super.visitInsn(Opcodes.DUP);
                    // stack: [..., value, objref, objref]
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner,
                            "$$crochetAccess", "()V", false);
                    // stack: [..., value, objref]
                    super.visitInsn(Opcodes.SWAP);
                    // stack: [..., objref, value]
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                    return;
                }

                // --- 2-slot PUTFIELD (new) ---
                // stack: [..., objref, value_hi, value_lo]    (J or D)
                Type t = Type.getType(descriptor);
                int slot = lvs.newLocal(t);
                int storeOp = t.getOpcode(Opcodes.ISTORE); // LSTORE or DSTORE
                int loadOp  = t.getOpcode(Opcodes.ILOAD);  // LLOAD or DLOAD

                // Stash the 2-slot value into our temp local. After this the
                // two value-slots are gone and objref is on top.
                super.visitVarInsn(storeOp, slot);
                // stack: [..., objref]
                super.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner,
                        "$$crochetAccess", "()V", false);
                // stack: [..., objref]
                super.visitVarInsn(loadOp, slot);
                // stack: [..., objref, value_hi, value_lo]
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            // Defensive: other field opcodes (GETSTATIC/PUTSTATIC) fall through
            // unchanged — shouldWrap() already filtered them.
            super.visitFieldInsn(opcode, owner, name, descriptor);
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
            // All valid field descriptors are now handled: Z/B/C/S/I/F/L/[ via
            // the 1-slot path, J/D via the 2-slot path.
            return true;
        }
    }
}
