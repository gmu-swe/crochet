package net.jonbell.crij.instrument;

import java.util.HashMap;
import java.util.HashSet;

import net.jonbell.crij.instrument.asm.OffsetPreservingLabel;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CheckpointRollbackAddingCV extends ClassVisitor {

	private HashSet<String> checkpointLocations;
	private HashSet<String> rollbackLocations;
	private boolean enabledCP = false;
	private boolean enabledRB = false;

	public CheckpointRollbackAddingCV(ClassVisitor cv, HashSet<String> checkpointLocations, HashSet<String> rollbackLocations) {
		super(Opcodes.ASM5, cv);
		this.checkpointLocations = checkpointLocations;
		this.rollbackLocations = rollbackLocations;
	}

	private static String getClassName(String key) {
		return key.substring(0, key.indexOf('.'));
	}
	private static String getMethodNameAndDesc(String key) {
		return key.substring(key.indexOf('.')+1,key.indexOf('#'));
	}
	private static int getOffset(String key)
	{ 
		return Integer.valueOf(key.substring(key.indexOf('#')+1));
	}

	HashMap<String, HashSet<Integer>> cpLocations = new HashMap<String, HashSet<Integer>>();
	HashMap<String, HashSet<Integer>> rbLocations = new HashMap<String, HashSet<Integer>>();
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		super.visit(version, access, name, signature, superName, interfaces);
		for (String s : checkpointLocations)
			if (getClassName(s).equals(name))
			{
				String meth = getMethodNameAndDesc(s);
				if(!meth.contains("("))
					meth = meth + "()V";
				if(!cpLocations.containsKey(meth))
					cpLocations.put(meth, new HashSet<Integer>());
				cpLocations.get(meth).add(getOffset(s));
			}
		for (String s : rollbackLocations)
			if (getClassName(s).equals(name))
			{
				String meth = getMethodNameAndDesc(s);
				if(!meth.contains("("))
					meth = meth + "()V";
				if(!rbLocations.containsKey(meth))
					rbLocations.put(meth, new HashSet<Integer>());
				rbLocations.get(meth).add(getOffset(s));
			}
	}
	
	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		if(rbLocations.containsKey(name+desc) || cpLocations.containsKey(name+desc))
		{
			System.out.println("CP found " +name+desc);
			mv = new CheckpointRollbackAddingMV(mv, cpLocations.get(name+desc), rbLocations.get(name+desc));
		}
		return mv;
	}
	class CheckpointRollbackAddingMV extends MethodVisitor{

		HashSet<Integer> offsetsCP;
		HashSet<Integer> offsetsRB;
		public CheckpointRollbackAddingMV(MethodVisitor mv, HashSet<Integer> offsetsCP, HashSet<Integer> offsetsRB) {
			super(Opcodes.ASM5, mv);
			this.offsetsCP = offsetsCP;
			this.offsetsRB = offsetsRB;
		}
		
		@Override
		public void visitLabel(Label label) {
			super.visitLabel(label);
			if(label instanceof OffsetPreservingLabel && offsetsCP != null && offsetsCP.contains(((OffsetPreservingLabel) label).getOriginalPosition()))
			{
				//do CP
				super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", "checkpointFrame", "()I", false);
				super.visitInsn(Opcodes.POP);
			}
			if(label instanceof OffsetPreservingLabel && offsetsRB != null && offsetsRB.contains(((OffsetPreservingLabel) label).getOriginalPosition()))
			{
				//do RB
				super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", "rollbackFrame", "()I", false);
				super.visitInsn(Opcodes.POP);
			}
		}
	}
}
