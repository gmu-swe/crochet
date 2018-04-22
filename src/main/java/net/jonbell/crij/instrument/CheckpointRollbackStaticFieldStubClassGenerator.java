package net.jonbell.crij.instrument;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldNode;

import net.jonbell.crij.runtime.CheckpointRollbackAgent.RollbackState;
import static org.objectweb.asm.Opcodes.*;

public class CheckpointRollbackStaticFieldStubClassGenerator extends CheckpointRollbackStubClassGenerator {

	List<Field> sFields;
	String origName;
	private boolean isIgnoredButPropogate;
	private Class superClass;
	private Class[] interfaces;
	public CheckpointRollbackStaticFieldStubClassGenerator(ClassVisitor cv, String origName, Class superClass, Class[] interfaces, List<Field> sFields) {
		super(cv, false);
		this.sFields = sFields;
		this.origName = origName;
		this.isIgnoredButPropogate = Instrumenter.isIgnoredClassButStillPropogate(origName);
		if(superClass == null)
			superClass = Object.class;
		this.superClass = superClass;
		this.interfaces = interfaces;
	}

	public void generate() {
		String superType = "net/jonbell/crij/runtime/CRIJSFHelper";
//		String[] newInterfaces = new String[1];
//		newInterfaces[0] = "net/jonbell/crij/runtime/CRIJSFHelper";

		visit(V1_8, ACC_PUBLIC, origName + "$$crijSFHelper", null, superType, null);
		this.classToLock = origName;

		super.visitField(ACC_PUBLIC, "$$crijOld", "Lnet/jonbell/crij/runtime/CRIJSFHelper;", null, null);
		super.visitField(ACC_PUBLIC, "$$crijLock", "Ljava/lang/Object;", null, null);

		super.visitField(ACC_PUBLIC, CheckpointRollbackFieldCV.VERSION_FIELD_NAME, "I", null, 0);

		super.visitField(ACC_STATIC | ACC_VOLATILE, "$$crijInited", "Z", null, 0);
		for(RollbackState s : RollbackState.values())
		{
			super.visitField(ACC_PUBLIC | ACC_STATIC, "$$crij_class_"+s.name(), "Ljava/lang/Class;", null, null);
		}

		MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKESPECIAL, superType, "<init>", "()V", false);
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		generateEmptyGetSetRollbackCheckpointMethods(false);
		mv = super.visitMethod(ACC_PUBLIC, "$$crijAccess", "()V", null, null);
		mv.visitCode();
//		for(Class c : interfaces)
//		{
//			if(c != null && !Instrumenter.isIgnoredClass(c))
//			{
//				mv.visitMethodInsn(INVOKESTATIC, c.getName().replace('.', '/'), "$$CRIJGetSFHelper", "()Lnet/jonbell/crij/runtime/CRIJSFHelper;", false);
//				mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$crijAccess", "()V", true);
//			}
//		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
//		mv = super.visitMethod(ACC_PUBLIC, "$$crijSetCalled", "()V", null, null);
//		mv.visitCode();
////		for(Class c : interfaces)
////		{
////			if(c != null && !Instrumenter.isIgnoredClass(c))
////			{
////				mv.visitMethodInsn(INVOKESTATIC, c.getName().replace('.', '/'), "$$CRIJGetSFHelper", "()Lnet/jonbell/crij/runtime/CRIJSFHelper;", false);
////				mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$crijSetCalled", "()V", true);
////			}
////		}
//		mv.visitInsn(RETURN);
//		mv.visitMaxs(0, 0);
//		mv.visitEnd();

		if(isIgnoredButPropogate)
		{
			super.visitField(Opcodes.ACC_PUBLIC, "$$crijIsRollbackState", "Z", null, 0);
			mv = super.visitMethod(Opcodes.ACC_PUBLIC, "$$crijIsRollbackState", "()Z", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, className, "$$crijIsRollbackState", "Z");
			mv.visitInsn(IRETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		mv = super.visitMethod(ACC_PUBLIC | ACC_STATIC, "$$crijInit", "()V", null, null);
		mv.visitCode();
		mv.visitFieldInsn(GETSTATIC, className, "$$crijInited", "Z");
		Label ok = new Label();
		mv.visitJumpInsn(IFNE, ok);
		ldcClass(mv,Type.getObjectType("Lnet/jonbell/PlaceHolder"));

		mv.visitInsn(DUP);
		mv.visitInsn(MONITORENTER);
		mv.visitFieldInsn(GETSTATIC, className, "$$crijInited", "Z");
		Label ok2 = new Label();
		mv.visitJumpInsn(IFNE, ok2);
		for(RollbackState s : RollbackState.values())
		{
			ldcThisClass(mv);
			if(s != RollbackState.Fast && s != RollbackState.Eager){
				mv.visitIntInsn(BIPUSH, s.ordinal());
				mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent",
						"generateClass", "(Ljava/lang/Class;I)Ljava/lang/Class;", false);
			}
			mv.visitFieldInsn(PUTSTATIC, className, "$$crij_class_"+s.name(), "Ljava/lang/Class;");
		}
		mv.visitInsn(ICONST_1);
		mv.visitFieldInsn(PUTSTATIC, className, "$$crijInited", "Z");
		mv.visitLabel(ok2);
		mv.visitFrame(F_FULL, 0, new Object[0], 1, new Object[]{"java/lang/Class"});
		mv.visitInsn(MONITOREXIT);
		mv.visitLabel(ok);
		mv.visitFrame(F_FULL, 0, new Object[0], 0, new Object[0]);
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		
		for(RollbackState s : RollbackState.values())
		{
			mv = super.visitMethod(ACC_PUBLIC | ACC_STATIC, "$$crijGetClass"+s.name(), "()Ljava/lang/Class;", null, null);
			mv.visitCode();
			mv.visitFieldInsn(GETSTATIC, className, "$$crijInited", "Z");
			ok = new Label();
			mv.visitJumpInsn(IFNE, ok);
			mv.visitMethodInsn(INVOKESTATIC, className, "$$crijInit", "()V", false);
			mv.visitLabel(ok);
			mv.visitFrame(F_SAME, 0, new Object[0], 0, new Object[0]);
			mv.visitFieldInsn(GETSTATIC, className, "$$crij_class_"+s.name(), "Ljava/lang/Class;");
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

		}
		for (Field f : sFields) {
			String name = f.getName();
			super.visitField(ACC_PRIVATE, name, Type.getDescriptor(f.getType()), null, null);
		}
		//CopyFields
		mv = super.visitMethod(ACC_PUBLIC, "$$CRIJcopyFieldsTo", "(Ljava/lang/Object;)V", null, null);
		mv.visitCode();
		if (!isIgnoredButPropogate) {
			mv.visitVarInsn(ALOAD, 1);
			mv.visitTypeInsn(CHECKCAST, className);
			for (Field f : sFields) {
				if (Modifier.isFinal(f.getModifiers()))
					continue;
				String desc = Type.getDescriptor(f.getType());
				mv.visitInsn(DUP);
				mv.visitFieldInsn(GETSTATIC, origName, f.getName(), desc);
				mv.visitFieldInsn(PUTFIELD, className, f.getName(), desc);
			}
			mv.visitInsn(POP);
		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		
		mv = super.visitMethod(ACC_PUBLIC, "$$CRIJcopyFieldsFrom", "(Ljava/lang/Object;)V", null, null);
		mv.visitCode();
		if (!isIgnoredButPropogate) {
			mv.visitVarInsn(ALOAD, 1);
			mv.visitTypeInsn(CHECKCAST, className);
			for (Field f : sFields) {
				if (Modifier.isFinal(f.getModifiers()))
					continue;
				String desc = Type.getDescriptor(f.getType());
				mv.visitInsn(DUP);
				mv.visitFieldInsn(GETFIELD, className, f.getName(), desc);
				mv.visitFieldInsn(PUTSTATIC, origName, f.getName(), desc);
			}
			mv.visitInsn(POP);
		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		// propogateCheckpoint
		mv = super.visitMethod(ACC_PUBLIC, "$$CRIJpropagateCheckpoint", "(I)V", null, null);
		mv.visitCode();
		if(superClass != null && !Instrumenter.isIgnoredClass(superClass) && !Instrumenter.isIgnoredClassWithDummyMethods(superClass))
		{
			mv.visitMethodInsn(INVOKESTATIC, superClass.getName().replace('.', '/'), "$$CRIJGetSFHelper", "()Lnet/jonbell/crij/runtime/CRIJSFHelper;", false);
			mv.visitVarInsn(ILOAD, 1);
			mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$CRIJpropagateCheckpoint", "(I)V", true);
		}
		for(Class c : interfaces)
		{
			if(c != null && !Instrumenter.isIgnoredClass(c))
			{
				mv.visitMethodInsn(INVOKESTATIC, c.getName().replace('.', '/'), "$$CRIJGetSFHelper", "()Lnet/jonbell/crij/runtime/CRIJSFHelper;", false);
				mv.visitVarInsn(ILOAD, 1);
				mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$CRIJpropagateCheckpoint", "(I)V", true);
			}
		}
		for (Field fn : sFields) {
			Type t = Type.getType(fn.getType());
			if (t.getSort() == Type.OBJECT) {
				mv.visitFieldInsn(GETSTATIC, origName, fn.getName(), t.getDescriptor());
				if (Instrumenter.isIgnoredClass(t.getInternalName())) {
					mv.visitVarInsn(ILOAD, 1);
					mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateCheckpoint", "(Ljava/lang/Object;I)V", false);
				} else {
					Label isNull = new Label();
					mv.visitJumpInsn(IFNULL, isNull);
					mv.visitFieldInsn(GETSTATIC, origName, fn.getName(), t.getDescriptor());
					mv.visitVarInsn(ILOAD, 1);
					mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$crijCheckpoint", "(I)V", true);
					mv.visitLabel(isNull);
					mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
				}
			} else if(t.getSort() == Type.ARRAY)
			{
				mv.visitFieldInsn(GETSTATIC, origName, fn.getName(), t.getDescriptor());
				mv.visitVarInsn(ILOAD, 1);
				mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ArrayWrapper", "propagateCheckpoint", "(Ljava/lang/Object;I)V", false);
			}
		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		// propogateRollback
		mv = super.visitMethod(ACC_PUBLIC, "$$CRIJpropagateRollback", "(I)V", null, null);
		mv.visitCode();
		
		if(superClass != null && !Instrumenter.isIgnoredClass(superClass) && !Instrumenter.isIgnoredClassWithDummyMethods(superClass))
		{
			mv.visitMethodInsn(INVOKESTATIC, superClass.getName().replace('.', '/'), "$$CRIJGetSFHelper", "()Lnet/jonbell/crij/runtime/CRIJSFHelper;", false);
			mv.visitVarInsn(ILOAD, 1);
			mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$CRIJpropagateRollback", "(I)V", true);
		}
		for(Class c : interfaces)
		{
			if(c != null && !Instrumenter.isIgnoredClass(c))
			{
				mv.visitMethodInsn(INVOKESTATIC, c.getName().replace('.', '/'), "$$CRIJGetSFHelper", "()Lnet/jonbell/crij/runtime/CRIJSFHelper;", false);
				mv.visitVarInsn(ILOAD, 1);
				mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$CRIJpropagateRollback", "(I)V", true);
			}
		}
		for (Field fn : sFields) {
			Type t = Type.getType(fn.getType());
			if (t.getSort() == Type.OBJECT) {
				mv.visitFieldInsn(GETSTATIC, origName, fn.getName(), t.getDescriptor());
				if (Instrumenter.isIgnoredClass(t.getInternalName())) {
					mv.visitVarInsn(ILOAD, 1);
					mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateRollback", "(Ljava/lang/Object;I)V", false);
				} else {
					Label isNull = new Label();
					mv.visitJumpInsn(IFNULL, isNull);
					mv.visitFieldInsn(GETSTATIC, origName, fn.getName(), t.getDescriptor());
					mv.visitVarInsn(ILOAD, 1);
					mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$crijRollback", "(I)V", true);
					mv.visitLabel(isNull);
					mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
				}
			} else if(t.getSort() == Type.ARRAY)
			{
				mv.visitFieldInsn(GETSTATIC, origName, fn.getName(), t.getDescriptor());
				mv.visitVarInsn(ILOAD, 1);
				mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ArrayWrapper", "propagateRollback", "(Ljava/lang/Object;I)V", false);
			}
		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		
		mv = super.visitMethod(ACC_PUBLIC, "$$CRIJgetVersion", "()I", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, CheckpointRollbackFieldCV.VERSION_FIELD_NAME, "I");
		mv.visitInsn(IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		mv = super.visitMethod(ACC_PUBLIC, "$$CRIJsetVersion", "(I)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ILOAD, 1);
		mv.visitFieldInsn(PUTFIELD, className, CheckpointRollbackFieldCV.VERSION_FIELD_NAME, "I");
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		
		visitEnd();

	}

}
