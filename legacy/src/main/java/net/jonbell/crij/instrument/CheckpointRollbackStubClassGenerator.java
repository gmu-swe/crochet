package net.jonbell.crij.instrument;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.util.CheckClassAdapter;

import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.CheckpointRollbackAgent.RollbackState;
import net.jonbell.crij.runtime.internalstruct.FieldInfoNode;
import static org.objectweb.asm.Opcodes.*;

public class CheckpointRollbackStubClassGenerator extends ClassVisitor {


	boolean ignoreFrames;
	public CheckpointRollbackStubClassGenerator(ClassVisitor cv, boolean ignoreFrames) {
		super(Opcodes.ASM5, cv);//new CheckClassAdapter(cv, false)
		this.ignoreFrames = ignoreFrames;
	}
	protected String className;
	protected String classToLock;
	
	private CheckpointRollbackAgent.RollbackState startingState;
	private boolean isIgnoredClassButStillPropogate;
	protected boolean fixLdcClass;
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		// TODO Auto-generated method stub
		super.visit(version, access, name, signature, superName, interfaces);
		this.fixLdcClass = (version & 0xFFFF) < Opcodes.V1_5;

		isIgnoredClassButStillPropogate = Instrumenter.isIgnoredClassButStillPropogate(name);
		if(isIgnoredClassButStillPropogate)
			startingState = RollbackState.Eager;
		else
			startingState = RollbackState.Fast;
		
		this.className = name;
		this.classToLock = className;
	}
	public void generate()
	{
		String origName = "_ORIG_CLASS_NAME_";
		String newName = "_ANON_CLASS_NAME_";
		String ifaceName = "_ANON_IFACE_NAME_";
		visit(V1_8, ACC_PUBLIC, newName, null, origName, new String[]{ ifaceName });
		MethodVisitor
//		mv = super.visitMethod(ACC_PUBLIC, "$$crijSetCalled", "()V", null, null);
//		mv.visitCode();
//		mv.visitVarInsn(ALOAD, 0);
//		mv.visitMethodInsn(INVOKESTATIC, origName, "__transition_to_on_set__", "()Ljava/lang/Class;", false);
//		mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", "_SET_PLACEHOLDER", "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;)V", false);
//		mv.visitInsn(RETURN);
//		mv.visitMaxs(0, 0);
//		mv.visitEnd();
		
		mv = super.visitMethod(ACC_PUBLIC, "$$crijAccess", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKESTATIC, origName, "__transition_to_on_get__", "()Ljava/lang/Class;", false);
		mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", "_GET_PLACEHOLDER", "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;)V", false);
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		
		mv = super.visitMethod(ACC_PUBLIC, "$$crijCheckpoint", "(I)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKESTATIC, origName, "__transition_to_on_checkpoint__", "()Ljava/lang/Class;", false);
		mv.visitVarInsn(ILOAD, 1);
		mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", "_CHECKPOINT_PLACEHOLDER", "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;I)V", false);

		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		
		mv = super.visitMethod(ACC_PUBLIC, "$$crijRollback", "(I)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKESTATIC, origName, "__transition_to_on_rollback__", "()Ljava/lang/Class;", false);
		mv.visitVarInsn(ILOAD, 1);
		mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", "_ROLLBACK_PLACEHOLDER", "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;I)V", false);

		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		
		visitEnd();
	}
	protected void generateStaticGetterAndSetter(String owner)
	{
		int opcode = GETSTATIC;
		int acc = ACC_PUBLIC | ACC_STATIC;

		String name = owner.replace('/', '$');

		MethodVisitor mv = super.visitMethod(acc, "crijGET" + "$$" + name, "()V", null, null);
		mv.visitCode();
		if (!Instrumenter.isIgnoredClassWithDummyMethods(owner)) {
//			if (!Instrumenter.isIgnoredFromClassCoverage(owner)) {
//				mv.visitFieldInsn(GETSTATIC, owner, "$$GMU$$ClassCov", "Lnet/jonbell/crij/runtime/ClassCoverageProbe;");
//				mv.visitMethodInsn(INVOKEVIRTUAL, "net/jonbell/crij/runtime/ClassCoverageProbe", "hit", "()V", false);
//			}
			mv.visitFieldInsn(GETSTATIC, owner, "$$crijSFHelper", "Lnet/jonbell/crij/runtime/CRIJSFHelper;");
			Label ok = new Label();
			mv.visitJumpInsn(IFNULL, ok);
			mv.visitFieldInsn(GETSTATIC, owner, "$$crijSFHelper", "Lnet/jonbell/crij/runtime/CRIJSFHelper;");
			mv.visitMethodInsn(INVOKEVIRTUAL, "net/jonbell/crij/runtime/CRIJSFHelper", "$$crijAccess", "()V", false);
			mv.visitLabel(ok);
			if (!ignoreFrames)
				mv.visitFrame(Opcodes.F_NEW, 0, new Object[0], 0, new Object[0]);
		}
//		mv.visitFieldInsn(opcode, fn.owner, fn.name, fn.desc);
//		mv.visitInsn(origType.getOpcode(Opcodes.IRETURN));
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

//		mv = super.visitMethod(acc, "crijSET" + "$$" + name, "()V", null, null);
//		mv.visitCode();
//		if (!Instrumenter.isIgnoredClassWithDummyMethods(owner)) {
////			if (!Instrumenter.isIgnoredFromClassCoverage(owner)) {
////				mv.visitFieldInsn(GETSTATIC, owner, "$$GMU$$ClassCov", "Lnet/jonbell/crij/runtime/ClassCoverageProbe;");
////				mv.visitMethodInsn(INVOKEVIRTUAL, "net/jonbell/crij/runtime/ClassCoverageProbe", "hit", "()V", false);
////			}
//			mv.visitFieldInsn(GETSTATIC, owner, "$$crijSFHelper", "Lnet/jonbell/crij/runtime/CRIJSFHelper;");
//			Label ok = new Label();
//			mv.visitJumpInsn(IFNULL, ok);
//			mv.visitFieldInsn(GETSTATIC, owner, "$$crijSFHelper", "Lnet/jonbell/crij/runtime/CRIJSFHelper;");
//			mv.visitMethodInsn(INVOKEVIRTUAL, "net/jonbell/crij/runtime/CRIJSFHelper", "$$crijSetCalled", "()V", false);
//			mv.visitLabel(ok);
//			if (!ignoreFrames) {
//				mv.visitFrame(Opcodes.F_NEW, 0, new Object[0], 0, new Object[0]);
//			}
//		}
////		mv.visitVarInsn(retType.getOpcode(Opcodes.ILOAD), 0);
////		mv.visitFieldInsn(PUTSTATIC, fn.owner, fn.name, retType.getDescriptor());
//		mv.visitInsn(RETURN);
//		mv.visitMaxs(0, 0);
//		mv.visitEnd();
	}
	
	
	protected void ldcThisClass(MethodVisitor mv) {
		if (!fixLdcClass)// java 5+
			mv.visitLdcInsn(Type.getType("L" + className + ";"));
		else {
			mv.visitLdcInsn(className.replace("/", "."));
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
		}
	}



	protected void ldcClass(MethodVisitor mv, Type t) {
		if (!fixLdcClass)// java 5+
			mv.visitLdcInsn(Type.getType(t.getDescriptor()));
		else {
			mv.visitLdcInsn(t.getInternalName().replace("/", "."));
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
		}
	}

	protected Object getStackFrameType(Type t) {
		switch (t.getSort()) {
		case Type.ARRAY:
		case Type.OBJECT:
			return t.getInternalName();
		case Type.BOOLEAN:
		case Type.BYTE:
		case Type.CHAR:
		case Type.INT:
		case Type.SHORT:
			return INTEGER;
		case Type.LONG:
			return LONG;
		case Type.DOUBLE:
			return DOUBLE;
		case Type.FLOAT:
			return FLOAT;
		default:
			throw new UnsupportedOperationException();
		}
	}

	
	protected void generateEmptyGetSetRollbackCheckpointMethods(boolean getSet) {
		MethodVisitor mv;

		if (getSet) {
			mv = super.visitMethod(ACC_PUBLIC, "$$crijAccess", "()V", null, null);
			mv.visitCode();
//			mv.visitVarInsn(ALOAD, 0);
//			mv.visitInsn(ACONST_NULL); //For bootstrapping issues, we should probably not do *anything* let alone look for a class at this point
//			mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", RollbackState.Fast.getterCalledMethod, "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;)V", false);
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
//			mv = super.visitMethod(ACC_PUBLIC, "$$crijSetCalled", "()V", null, null);
//			mv.visitCode();
////			mv.visitVarInsn(ALOAD, 0);
////			mv.visitInsn(ACONST_NULL); //For bootstrapping issues, we should probably not do *anything* let alone look for a class at this point
////			mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", RollbackState.Fast.setterCalledMethod, "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;)V", false);
//
//			mv.visitInsn(RETURN);
//			mv.visitMaxs(0, 0);
//			mv.visitEnd();
		}
		
		mv = super.visitMethod(ACC_PUBLIC, "$$crijCheckpoint", "(I)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		if(startingState.onCheckpoint == CheckpointRollbackAgent.UNDEFINED_OFFSET)
		{
			mv.visitVarInsn(ALOAD, 0);
			mv.visitInsn(ICONST_0);
			mv.visitFieldInsn(PUTFIELD, className, "$$crijIsRollbackState", "Z");
			mv.visitInsn(ACONST_NULL);
		}
		else
			mv.visitMethodInsn(INVOKESTATIC, className, "$$crijGetClass"+RollbackState.values()[startingState.onCheckpoint].name(), "()Ljava/lang/Class;", false);
		mv.visitVarInsn(ILOAD, 1);
		mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", startingState.checkpointCalledMethod, "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;I)V", false);

		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		
		mv = super.visitMethod(ACC_PUBLIC, "$$crijRollback", "(I)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		if(startingState.onRollback == CheckpointRollbackAgent.UNDEFINED_OFFSET)
		{
			mv.visitVarInsn(ALOAD, 0);
			mv.visitInsn(ICONST_1);
			mv.visitFieldInsn(PUTFIELD, className, "$$crijIsRollbackState", "Z");
			mv.visitInsn(ACONST_NULL);
		}
		else
			mv.visitMethodInsn(INVOKESTATIC, className, "$$crijGetClass"+RollbackState.values()[startingState.onRollback].name(), "()Ljava/lang/Class;", false);
		mv.visitVarInsn(ILOAD, 1);
		mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", startingState.rollbackCalledMethod, "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;I)V", false);

		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}
	
}
