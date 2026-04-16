package net.jonbell.crij.instrument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LocalVariableNode;



public class StackElementCapturingMV extends MethodVisitor implements Opcodes {

	private AnalyzerAdapter analyzer;
	private boolean ignoreFrames;
	private String className;
	private String methodName;
	private String methodDesc;
	private LocalVariableManager lvm;
	
	public StackElementCapturingMV(MethodVisitor mv, boolean ignoreFrames, String className, String methodName, String methodDesc) {
		super(Opcodes.ASM5,mv);
		this.ignoreFrames = ignoreFrames;
		this.className = className;
		this.methodDesc = methodDesc;
		this.methodName = methodName;
	}
	public void setAnalyzer(AnalyzerAdapter an) {
		this.analyzer = an;
	}

	int shouldStoreLV;
	@Override
	public void visitCode() {
		super.visitCode();
		super.visitInsn(NOP);
		shouldStoreLV = lvm.newLocal(Type.BOOLEAN_TYPE);
		super.visitInsn(ICONST_0);
		super.visitVarInsn(ISTORE, shouldStoreLV);
	}
	private FrameNode getCurrentFrameNode() {
		if (analyzer.locals == null || analyzer.stack == null)
			throw new IllegalArgumentException();
		Object[] locals = removeLongsDoubleTopVal(analyzer.locals);
		Object[] stack = removeLongsDoubleTopVal(analyzer.stack);
		FrameNode ret = new FrameNode(Opcodes.F_NEW, locals.length, locals, stack.length, stack);

		return ret;
	}

	public static Object[] removeLongsDoubleTopVal(List<Object> in) {
		ArrayList<Object> ret = new ArrayList<Object>();
		boolean lastWas2Word = false;
		for (Object n : in) {
			if ((n == Opcodes.TOP) && lastWas2Word) {
				// nop
			} else
				ret.add(n);
			if (n == Opcodes.DOUBLE || n == Opcodes.LONG)
				lastWas2Word = true;
			else
				lastWas2Word = false;
		}
		return ret.toArray();
	}
	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
		Type returnType = Type.getReturnType(desc);
		super.visitMethodInsn(opcode, owner, name, desc, itf);
		if(!name.startsWith("crijGET") && !name.startsWith("crijSET") && !owner.startsWith("edu/columbia/cs/psl/phosphor/"))
		{
//			if(returnType.getSort() == Type.VOID || analyzer.stack.size() > 1)
			{
//				Label ok = new Label();
				FrameNode fn = getCurrentFrameNode();
				super.visitInsn(NOP);
				super.visitVarInsn(ILOAD, shouldStoreLV);
				Label ok = new Label();
				super.visitJumpInsn(IFEQ, ok);
				LocalVariableNode[] lvs = new LocalVariableNode[analyzer.stack.size()];
				int i = 0;
				for(i = 0; i < analyzer.locals.size(); i++)
				{
					if(analyzer.locals.get(i) instanceof String)
					{
						super.visitVarInsn(ALOAD, i);
						super.visitVarInsn(ILOAD, shouldStoreLV);
						super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateCheckpoint", "(Ljava/lang/Object;I)V", false);
					}
				}
//				for (i = 0; analyzer.stack.size() > 0; i++) {
//					Type elType = null;
////					System.out.println("."+analyzer.stack);
//					if (analyzer.stack.get(analyzer.stack.size() - 1) == Opcodes.TOP)
//						elType = getTypeForStackType(analyzer.stack.get(analyzer.stack.size() - 2));
//					else
//						elType = getTypeForStackType(analyzer.stack.get(analyzer.stack.size() - 1));
//					if(analyzer.stack.get(analyzer.stack.size() - 1) instanceof String)
//					{
//						super.visitInsn(DUP);
//						super.visitVarInsn(ILOAD, shouldStoreLV);
//						super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateCheckpoint", "(Ljava/lang/Object;I)V", false);
//					}
//					
//					lvs[i] = new LocalVariableNode(null, elType.getDescriptor(), "stacklocal"+i, null, null, lvm.getTmpLV());
//					super.visitVarInsn(elType.getOpcode(ISTORE), lvs[i].index);
//				}
				super.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/Tagger", "captureStack", "()V", false);
				
				for (int j = 1; j <= i; j++) {
					loadLV(i - j, lvs);
				}
				freeLVs(lvs);
				super.visitInsn(ICONST_0);
				super.visitVarInsn(ISTORE, shouldStoreLV);
				super.visitLabel(ok);
				if (!ignoreFrames) {
					fn.accept(mv);
					super.visitInsn(NOP);
				}

			}
		}
	}
	protected void freeLVs(LocalVariableNode[] lvArray)
	{
		for(LocalVariableNode n : lvArray)
			if(n != null)
				lvm.freeTmpLV(n.index);
	}
	protected void loadLV(int n, LocalVariableNode[] lvArray) {
//		System.out.println("N"+n+">"+lvArray[n].desc);
//		System.out.println(lvArray[n].index+">"+analyzer.locals.get(lvArray[n].index));
		super.visitVarInsn(Type.getType(lvArray[n].desc).getOpcode(ILOAD), lvArray[n].index);
	}

	public static Type getTypeForStackType(Object obj) {
		if (obj == Opcodes.INTEGER)
			return Type.INT_TYPE;
		if (obj == Opcodes.FLOAT)
			return Type.FLOAT_TYPE;
		if (obj == Opcodes.DOUBLE)
			return Type.DOUBLE_TYPE;
		if (obj == Opcodes.LONG)
			return Type.LONG_TYPE;
		if (obj instanceof String)
			if (!(((String) obj).charAt(0) == '[') && ((String) obj).length() > 1)
				return Type.getType("L" + obj + ";");
			else
				return Type.getObjectType((String) obj);
		if (obj == Opcodes.NULL)
			return Type.getType("Ljava/lang/Object;");
		if (obj instanceof Label || obj == Opcodes.UNINITIALIZED_THIS)
			return Type.getType("Luninitialized;");
		throw new IllegalArgumentException("got " + obj + " zzz" + obj.getClass());
	}
	public void setLVM(LocalVariableManager lvm) {
		this.lvm = lvm;
	}

}
