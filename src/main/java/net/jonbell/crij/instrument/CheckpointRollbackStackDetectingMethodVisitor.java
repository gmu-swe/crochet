package net.jonbell.crij.instrument;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

public class CheckpointRollbackStackDetectingMethodVisitor extends MethodNode {

	private final MethodVisitor cmv;
	private final SimpleStackCapturingMV stackMV;
	
	public CheckpointRollbackStackDetectingMethodVisitor(final int access, final String name, final String desc, final String signature, final String[] exceptions, final MethodVisitor cmv, final SimpleStackCapturingMV stackMV) {
		super(Opcodes.ASM5, access, name, desc, signature, exceptions);
		this.cmv = cmv;
		this.stackMV = stackMV;
	}
	private int nMethodCalls = 0;
	private boolean hasCheckpoint = false;
	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
		super.visitMethodInsn(opcode, owner, name, desc, itf);
		hasCheckpoint |= SimpleStackCapturingMV.isCheckpointMethod(owner, name, desc);
		nMethodCalls++;
	}
	private int nFinallyBlocks =0;
	@Override
	public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
		super.visitTryCatchBlock(start, end, handler, type);
		if(type == null)
			nFinallyBlocks++;
	}
	@Override
	public void visitEnd() {
		super.visitEnd();
		if(hasCheckpoint)
			this.stackMV.enable();
		this.stackMV.setNFinallyBlocks(nFinallyBlocks);
		this.stackMV.setnMethodCalls(nMethodCalls);
		this.accept(cmv);
	}
	
}
