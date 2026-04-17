// Proposed patch for FieldAdder — NOT YET APPLIED.
//
// Changes emitCheckpoint() and emitRollback() to:
//   (a) read the old version BEFORE overwriting it, stash in a local.
//   (b) pass (this, ClassLiteral, oldVersion) to swapToFastProxy.
//   (c) wrap the swapToFastProxy call in a JVM try/catch that rolls back
//       $$crochetVersion to the saved local on throw, then re-raises.
//
// The updated swapToFastProxy signature is:
//   swapToFastProxy(Object target, Class<?> userClass, int priorVersion)
//
// Everything else in FieldAdder is unchanged.

package net.jonbell.crochet.transform;

// ------ emitCheckpoint --------------------------------------------------

private void emitCheckpoint() {
    MethodVisitor mv = super.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            "$$crochetCheckpoint", "(I)V", null, null);
    mv.visitCode();

    Label tryStart = new Label();
    Label tryEnd   = new Label();
    Label handler  = new Label();
    Label afterHandler = new Label();

    // local 2: int priorVersion  — saved before we overwrite $$crochetVersion
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitFieldInsn(Opcodes.GETFIELD, className, VERSION_FIELD, "I");
    mv.visitVarInsn(Opcodes.ISTORE, 2);

    // this.$$crochetVersion = v
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitVarInsn(Opcodes.ILOAD, 1);
    mv.visitFieldInsn(Opcodes.PUTFIELD, className, VERSION_FIELD, "I");

    // try { swapToFastProxy(this, ThisClass.class, priorVersion); }
    mv.visitLabel(tryStart);
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitLdcInsn(Type.getObjectType(className));
    mv.visitVarInsn(Opcodes.ILOAD, 2);
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "swapToFastProxy",
            "(Ljava/lang/Object;Ljava/lang/Class;I)V", false);
    mv.visitLabel(tryEnd);
    mv.visitJumpInsn(Opcodes.GOTO, afterHandler);

    // catch (Throwable t): restore $$crochetVersion = priorVersion; throw t;
    mv.visitLabel(handler);
    // stack: [Throwable]
    mv.visitVarInsn(Opcodes.ASTORE, 3);                 // local 3: Throwable t
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitVarInsn(Opcodes.ILOAD, 2);
    mv.visitFieldInsn(Opcodes.PUTFIELD, className, VERSION_FIELD, "I");
    mv.visitVarInsn(Opcodes.ALOAD, 3);
    mv.visitInsn(Opcodes.ATHROW);

    mv.visitLabel(afterHandler);
    mv.visitInsn(Opcodes.RETURN);

    mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
    mv.visitMaxs(0, 0);
    mv.visitEnd();
}

// ------ emitRollback ----------------------------------------------------

private void emitRollback() {
    MethodVisitor mv = super.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            "$$crochetRollback", "(I)V", null, null);
    mv.visitCode();

    Label tryStart = new Label();
    Label tryEnd   = new Label();
    Label handler  = new Label();
    Label afterHandler = new Label();

    // local 2: int priorVersion
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitFieldInsn(Opcodes.GETFIELD, className, VERSION_FIELD, "I");
    mv.visitVarInsn(Opcodes.ISTORE, 2);

    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitVarInsn(Opcodes.ILOAD, 1);
    mv.visitFieldInsn(Opcodes.PUTFIELD, className, VERSION_FIELD, "I");

    mv.visitLabel(tryStart);
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitLdcInsn(Type.getObjectType(className));
    mv.visitVarInsn(Opcodes.ILOAD, 2);
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "swapToFastProxy",
            "(Ljava/lang/Object;Ljava/lang/Class;I)V", false);
    mv.visitLabel(tryEnd);
    mv.visitJumpInsn(Opcodes.GOTO, afterHandler);

    mv.visitLabel(handler);
    mv.visitVarInsn(Opcodes.ASTORE, 3);
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitVarInsn(Opcodes.ILOAD, 2);
    mv.visitFieldInsn(Opcodes.PUTFIELD, className, VERSION_FIELD, "I");
    mv.visitVarInsn(Opcodes.ALOAD, 3);
    mv.visitInsn(Opcodes.ATHROW);

    mv.visitLabel(afterHandler);
    mv.visitInsn(Opcodes.RETURN);

    mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
    mv.visitMaxs(0, 0);
    mv.visitEnd();
}

// Note: emitCopyFieldsTo / emitCopyFieldsFrom are NOT wrapped in a
// try/finally. The GETFIELD/PUTFIELD pairs they emit cannot throw unless
// the JVM throws OOM, in which case the wrapper in fastAccess catches
// it. Wrapping them here would bloat every user class with a
// try/catch-Throwable around every field assignment for no gain, since
// the only observable state they mutate is the shadow (not `this`) on
// the checkpoint path, or `this` on the rollback path — and the rollback
// path is unrecoverable mid-copy anyway.
