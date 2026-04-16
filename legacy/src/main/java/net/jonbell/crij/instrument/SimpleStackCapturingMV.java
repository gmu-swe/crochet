package net.jonbell.crij.instrument;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.RollbackException;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.tree.FrameNode;

public class SimpleStackCapturingMV extends MethodVisitor {
	private AnalyzerAdapter analyzer;
	private LocalVariableManager lvs;
	
	private GeneratorAdapter ga;
	
	public void setLvs(LocalVariableManager lvs) {
		this.lvs = lvs;
	}
	boolean isStatic;
	String className;
	Type[] localVars;
	String name;
	
	public SimpleStackCapturingMV(MethodVisitor mv, int access, String className, String name, String desc, AnalyzerAdapter analyzer) {
		super(ASM5, mv);
		ga = new GeneratorAdapter(mv, access, name, desc);
		this.analyzer = analyzer;
		this.isStatic = ((access & ACC_STATIC) != 0);
		this.localVars = Type.getArgumentTypes(desc);
		this.className = className;
		this.name = name;
	}
	int localVarsArray = -1;
	int checkpointIDxVar = -1;
	ArrayList<Label> checkpointLabels = new ArrayList<Label>();
	ArrayList<Type[]> checkpointLVs = new ArrayList<Type[]>();
	public static Type getWrappedTypeForStackType(Object obj) {
		if (obj == Opcodes.INTEGER)
			return Type.getType(Integer.class);
		if (obj == Opcodes.FLOAT)
			return Type.getType(Float.class);
		if (obj == Opcodes.DOUBLE)
			return Type.getType(Double.class);
		if (obj == Opcodes.LONG)
			return Type.getType(Long.class);
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
	
	Label lastLabel = new Label();
	int cpCount = 0;
	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
		
		super.visitMethodInsn(opcode, owner, name, desc, itf);
		if(!enabled)
			return;
		methodsSeen++;
		if(nMethodCalls == methodsSeen)
			super.visitLabel(lastLabel);
		if(isCheckpointMethod(owner,name,desc))
		{
			int nLocals = analyzer.locals.size();
			//First call CP on each LV, then store into an array
			if(!name.equals("checkpointFrameNoSFieldsNoFields"))
				for(int i = 0; i < nLocals; i++)
				{
					if(!(analyzer.locals.get(i) instanceof String) || i == checkpointIDxVar || i == localVarsArray)
						continue;
					if(!superInited && i == 0)
						continue;
					String type = (String) analyzer.locals.get(i);
					super.visitInsn(DUP);
					super.visitVarInsn(ALOAD, i);
					super.visitInsn(SWAP);
					super.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateCheckpoint", "(Ljava/lang/Object;I)V", false);
				}
			
			super.visitIntInsn(BIPUSH, nLocals);
			super.visitTypeInsn(ANEWARRAY, "java/lang/Object");
			Type[] locals = new Type[analyzer.locals.size()];
			for(int i = 0; i < nLocals; i++)
			{
				if(analyzer.locals.get(i) == TOP || i == checkpointIDxVar || i == localVarsArray)
					continue;
				if(!superInited && i == 0)
					continue;

				super.visitInsn(DUP);
				super.visitIntInsn(BIPUSH, i);
				Type t = StackElementCapturingMV.getTypeForStackType(analyzer.locals.get(i));
				locals[i] = t;
				super.visitVarInsn(t.getOpcode(ILOAD), i);
				
				ga.box(t);
				super.visitInsn(AASTORE);
			}
//			System.out.println(analyzer.locals);
			super.visitVarInsn(ASTORE, localVarsArray);
			Label lbl = new Label();
			super.visitLabel(lbl);
			//need stack frame here
			getCurrentFrameNode().accept(this);
//			System.out.println("At CP" + getCurrentFrameNode().stack);

			checkpointLabels.add(lbl);
			checkpointLVs.add(locals);
			cpCount++;
		}
		
		if (!superInited && opcode == INVOKESPECIAL && name.equals("<init>")) {
			superInited = true;
			super.visitLabel(startLabel);
		}
	}
	private FrameNode getCurrentFrameNode()
	{
		if(analyzer.locals == null || analyzer.stack == null)
			throw new IllegalArgumentException();
		Object[] locals = StackElementCapturingMV.removeLongsDoubleTopVal(analyzer.locals);
		Object[] stack = StackElementCapturingMV.removeLongsDoubleTopVal(analyzer.stack);
		FrameNode ret = new FrameNode(Opcodes.F_NEW, locals.length, locals, stack.length, stack);
		return ret;
	}
	
	Label startLabel = new Label();
	Label rollbackLabel = new Label();
	
	boolean superInited = false;
	@Override
	public void visitCode() {
		super.visitCode();
		if(!enabled)
			return;
		
		localVarsArray = lvs.newLocal(Type.getType(Object[].class));
		super.visitInsn(ACONST_NULL);
		super.visitVarInsn(ASTORE, localVarsArray);

		checkpointIDxVar = lvs.newLocal(Type.INT_TYPE);
		super.visitInsn(ICONST_0);
		super.visitVarInsn(ISTORE, checkpointIDxVar);
		if(nFinallyBlocks == 0)
			super.visitTryCatchBlock(startLabel, lastLabel, rollbackLabel, "net/jonbell/crij/runtime/RollbackException");
		if(!this.name.equals("<init>"))
		{
			superInited = true;
			super.visitLabel(startLabel);
		}
	}


	public static Object getStackTypeForType(Type t) {
		if (t == null)
			return Opcodes.NULL;
		switch (t.getSort()) {
		case Type.ARRAY:
		case Type.OBJECT:
			return t.getInternalName();
		case Type.BYTE:
		case Type.BOOLEAN:
		case Type.CHAR:
		case Type.SHORT:
		case Type.INT:
			return Opcodes.INTEGER;
		case Type.DOUBLE:
			return Opcodes.DOUBLE;
		case Type.FLOAT:
			return Opcodes.FLOAT;
		case Type.LONG:
			return Opcodes.LONG;

		default:
			throw new IllegalArgumentException("Got: " + t);
		}
	}
	void foo(){
		try{
			try{
				FileInputStream fis = new FileInputStream("a");
			}catch(RollbackException ex)
			{
				System.out.println("IOEX");
			}
		}catch(IOException ex)
		{
			System.out.println("Rex");
		}
	}
	@Override
	public void visitMaxs(int maxStack, int maxLocals) {
		if(!enabled || nFinallyBlocks > 0)
		{
			super.visitMaxs(maxStack, maxLocals);
			return;
		}
		super.visitLabel(rollbackLabel);
		Object[] baseLVs = new Object[Math.max(localVarsArray, checkpointIDxVar) + 1];
		for(int i = 0; i < baseLVs.length; i++)
			baseLVs[i] = TOP;
		int j = 0;
		if(!isStatic)
		{
			baseLVs[0] = className;
			j++;
		}
		for(Type t : localVars)
		{
			baseLVs[j] = getStackTypeForType(t);
			j+=t.getSize();
		}
		baseLVs[localVarsArray] = "[Ljava/lang/Object;";
		baseLVs[checkpointIDxVar] = INTEGER;
		super.visitFrame(F_NEW, baseLVs.length, baseLVs, 1, new Object[]{"net/jonbell/crij/runtime/RollbackException"});

		if (cpCount > 0) {
			super.visitFieldInsn(GETFIELD, "net/jonbell/crij/runtime/RollbackException", "version", "I");
			int tmp = lvs.getTmpLV(Type.INT_TYPE);

			Object[] onlyOurLVs = new Object[tmp + 1];
			System.arraycopy(baseLVs, 0, onlyOurLVs, 0, baseLVs.length);
			for(int i = baseLVs.length; i < onlyOurLVs.length; i++)
				onlyOurLVs[i] = TOP;
			onlyOurLVs[tmp] = INTEGER;

			super.visitVarInsn(ISTORE, tmp);
			
			super.visitVarInsn(ILOAD, checkpointIDxVar);
			Label[] labels = new Label[checkpointLabels.size()+1];
			for(int i = 0; i < labels.length; i++)
				labels[i] = new Label();
			super.visitTableSwitchInsn(0, checkpointLabels.size(), labels[checkpointLabels.size()], labels);
			for(int i =0; i < checkpointLabels.size(); i++)
			{
				super.visitLabel(labels[i]);
				super.visitFrame(F_NEW, onlyOurLVs.length, onlyOurLVs.clone(), 0, new Object[0]);
				//fix stack
				Type[] locals = checkpointLVs.get(i);
				for(j = 0; j < locals.length; j++)
				{
					if(locals[j] == null) // TOP etc
						continue;
					super.visitVarInsn(ALOAD, localVarsArray);
					super.visitIntInsn(BIPUSH, j);
					super.visitInsn(AALOAD);
					switch(locals[j].getSort())
					{
					case Type.OBJECT:
					case Type.ARRAY:
						super.visitTypeInsn(CHECKCAST, locals[j].getInternalName());
						super.visitInsn(DUP);
						super.visitVarInsn(ILOAD, tmp);
//						super.visitInsn(SWAP);
						super.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateRollback", "(Ljava/lang/Object;I)V", false);
						break;
					case Type.INT:
					case Type.LONG:
					case Type.DOUBLE:
					case Type.FLOAT:
						ga.unbox(locals[j]);
						break;
					}
					if(isStatic || j > 0)
						super.visitVarInsn(locals[j].getOpcode(ISTORE), j);
					else
						super.visitInsn(POP);
				}
				super.visitVarInsn(ILOAD, tmp);
//				System.out.println("Done RB" + analyzer.stack);
				super.visitJumpInsn(GOTO, checkpointLabels.get(i));
			}
			super.visitLabel(labels[checkpointLabels.size()]);
			super.visitFrame(F_NEW, onlyOurLVs.length, onlyOurLVs.clone(), 0, new Object[0]);
			lvs.freeTmpLV(tmp);
			super.visitInsn(ACONST_NULL);
			super.visitInsn(ATHROW);
			
		}
		else
		{
////			super.visitFrame(F_NEW, 0, new Object[0], 1, new Object[]{"net/jonbell/crij/runtime/RollbackException"});

			super.visitInsn(ATHROW);
		}
		
		super.visitMaxs(maxStack, maxLocals);
	}
	public static boolean isCheckpointMethod(String owner, String name, String desc) {
		if(owner.startsWith("net/jonbell/crij/runtime/CheckpointRollbackAgent") && name.equals("checkpointFrame") && desc.equals("()I"))
		{
			return true;
		}
		else if(owner.startsWith("net/jonbell/crij/runtime/CheckpointRollbackAgent") && name.equals("checkpointFrameNoSFields") && desc.equals("()I"))
		{
			return true;
		}
		else if(owner.startsWith("net/jonbell/crij/runtime/CheckpointRollbackAgent") && name.equals("checkpointFrameNoSFieldsNoFields") && desc.equals("()I"))
		{
			return true;
		}
		return false;
	}

	private int nMethodCalls = 0;
	private int methodsSeen = 0;
	public void setnMethodCalls(int n){
		this.nMethodCalls = n;
	}
	private boolean enabled = false;
	public void enable() {
		this.enabled = true;
	}
	private int nFinallyBlocks;
	public void setNFinallyBlocks(int nFinallyBlocks) {
		this.nFinallyBlocks = nFinallyBlocks;
	}
}
