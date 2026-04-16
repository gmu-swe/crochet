package net.jonbell.crij.instrument;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import net.jonbell.crij.runtime.CheckpointRollbackAgent.RollbackState;
import net.jonbell.crij.runtime.internalstruct.FieldInfoNode;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import static org.objectweb.asm.Opcodes.*;

public class CheckpointRollbackFieldCV extends CheckpointRollbackStubClassGenerator {
	
	public final static String VERSION_FIELD_NAME = "$$crijVersion";

	private HashSet<String> syntheticFields;
	public CheckpointRollbackFieldCV(ClassVisitor cv, boolean ignoreFrame, HashSet<String> syntheticFields) {
		super(new CheckClassAdapter(cv, false), ignoreFrame);
		this.syntheticFields = syntheticFields;
	}
	private boolean fixLdcClass;

	boolean isReferenceQueue;
	boolean hasMain;
	boolean hasRun;
	boolean hasClinit;
	
	boolean isInterface;
	String superName;
	HashSet<FieldNode> fields = new HashSet<FieldNode>();
	String[] interfaces;
	
	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		if(name.equals("main") && desc.equals("([Ljava/lang/String;)V") && ((access & ACC_STATIC) != 0))
		{
			hasMain = true;
			name = "$$crijMain";
		}
		else if(className.equals("java/lang/Thread") && name.equals("run") && desc.equals("()V") && (access & Opcodes.ACC_STATIC) == 0)
		{
			hasRun = true;
			name = "$$crijRun";
		}
		if (name.equals("<clinit>"))
			hasClinit = true;

		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		if(name.equals("reallyPoll") && isReferenceQueue)
			mv = new MethodVisitor(Opcodes.ASM5, mv) {
			@Override
			public void visitInsn(int opcode) {
				if(opcode == Opcodes.ARETURN)
				{
					super.visitInsn(DUP);
					super.visitVarInsn(ALOAD, 0);
					super.visitInsn(SWAP);
					super.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", "hackRefQueue", "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Object;)V", false);
				}
				super.visitInsn(opcode);
			}
			};
		if (ignoreFrames && !Premain.WITH_ARRAY_PER_EL)
			mv = new MethodVisitor(Opcodes.ASM5, mv) {
				@Override
				public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
				}
			};
//		ReflectionMaskingMethodVisitor rmmv = new ReflectionMaskingMethodVisitor(mv, className);
//		mv = rmmv;
		AnalyzerAdapter postAnalyzer = new AnalyzerAdapter(className, access, name, desc, mv);
//		StackElementCapturingMV smv = new StackElementCapturingMV(postAnalyzer, ignoreFrames, className, name, desc);
		SimpleStackCapturingMV smv = new SimpleStackCapturingMV(postAnalyzer, access, className, name, desc, postAnalyzer);
//		MethodVisitor smv = postAnalyzer;
		CheckpointRollbackFieldMV cmv = new CheckpointRollbackFieldMV(smv, null, fixLdcClass, name, className, superName, localStaticFields);
		mv = cmv;
		AnalyzerAdapter an = new AnalyzerAdapter(className, access, name, desc, mv);
		LocalVariableManager lvm = new LocalVariableManager(access, desc, desc, an, postAnalyzer, mv, 0);
		cmv.setAnalyzer(an);
//		smv.setAnalyzer(postAnalyzer);
		smv.setLvs(lvm);
//		rmmv.setLVM(lvm);
		if(Premain.WITH_ROLLBACK_FINALLY)
			mv = new CheckpointRollbackStackDetectingMethodVisitor(access, name, desc, signature, exceptions, lvm, smv);
		else
			mv = lvm;
		return mv;
	}

	boolean ignoredButStillPropogate = false;
	boolean superIsIgnored = false;
	private HashSet<String> localStaticFields = new HashSet<String>();
	
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		this.isInterface = (access & ACC_INTERFACE) != 0;
		this.fixLdcClass = (version & 0xFFFF) < V1_5;

		// Super big hack might work: force all interfaces to be java 8.
		if (isInterface) {
			version = Opcodes.V1_8;
			ignoreFrames = false;
		}
		access = access & ~ACC_FINAL;
		this.superName = superName;
		this.interfaces = interfaces;
		String[] newIntfcs = new String[interfaces.length + 1];
		System.arraycopy(interfaces, 0, newIntfcs, 0, interfaces.length);
		newIntfcs[interfaces.length] = "net/jonbell/crij/runtime/CRIJInstrumented";
		interfaces = newIntfcs;
		if (signature != null)
			signature = signature + "Lnet/jonbell/crij/runtime/CRIJInstrumented;";
		this.isReferenceQueue = name.equals("java/lang/ref/ReferenceQueue");
		superIsIgnored = Instrumenter.isIgnoredClass(superName);
		ignoredButStillPropogate = Instrumenter.isIgnoredClassButStillPropogate(name);
		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
		if (className.equals("java/lang/Thread") && name.equals("tid")) {
			access = access & ~Opcodes.ACC_PRIVATE;
			access = access | Opcodes.ACC_PUBLIC;
		}
		if((access & ACC_PRIVATE) != 0 && (access & ACC_STATIC) != 0)
		{
			access = access & ~Opcodes.ACC_PRIVATE;
			access = access | Opcodes.ACC_PUBLIC;			
		}
		if(!isInterface && Type.getType(desc).getSort() == Type.OBJECT)
			access = access & ~Opcodes.ACC_FINAL;
		FieldNode fn = new FieldNode(access, name, desc, signature, value);
		fields.add(fn);
		if((fn.access & ACC_SYNTHETIC) != 0)
			syntheticFields.add(name);
		return super.visitField(access, name, desc, signature, value);
	}

	@Override
	public void visitEnd() {

		for (String fn : localStaticFields) {
			generateStaticGetterAndSetter(fn);
		}
		if (!isInterface && superIsIgnored)
		{
			super.visitField(ACC_PUBLIC, "$$crijOld", "Ljava/lang/Object;", null, null);
			super.visitField(ACC_PUBLIC, VERSION_FIELD_NAME, "I", null, null);
			super.visitField(ACC_PUBLIC, "$$crijLock", "Ljava/lang/Object;", null, null);
		}
		if(this.className.equals("java/lang/Class"))
		{
			super.visitField(Opcodes.ACC_PUBLIC, "sfHelper", "Lnet/jonbell/crij/runtime/CRIJSFHelper;", null, null);
			super.visitField(Opcodes.ACC_PUBLIC, "sfHelperClass", "Ljava/lang/Class;", null, null);
			super.visitField(Opcodes.ACC_PUBLIC, "originalClass", "Ljava/lang/Class;", null, null);
			super.visitField(Opcodes.ACC_PUBLIC, "oldField", "Ljava/lang/reflect/Field;", null, -1);
			super.visitField(Opcodes.ACC_PUBLIC, "ignored", "B", null, 0);
			super.visitField(Opcodes.ACC_PUBLIC, "versionOffset", "J", null, 0);
			super.visitField(Opcodes.ACC_PUBLIC, "oldOffset", "J", null, 0);
			super.visitField(Opcodes.ACC_PUBLIC, "lockOffset", "J", null, 0);
			super.visitField(Opcodes.ACC_PUBLIC, "preallocInst", "Ljava/lang/Object;", null, null);
		}
		
		if (!isInterface && Instrumenter.isIgnoredClassWithDummyMethods(this.className))
		{
			generateDummyCRIJ();
			super.visitEnd();
			return;
		}

		super.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "$$crijSFHelper", "Lnet/jonbell/crij/runtime/CRIJSFHelper;", null, null);
		if (!isInterface)
			super.visitField(ACC_PUBLIC | ACC_STATIC | ACC_VOLATILE, "$$crijInited", "Z", null, 0);
		for (RollbackState s : RollbackState.values()) {
			super.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "$$crij_class_" + s.name(), "Ljava/lang/Class;", null, null);
		}

		if(hasMain)
		{
			MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null,null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKESTATIC, className, "$$crijMain", "([Ljava/lang/String;)V", false);
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		if(hasRun)
		{
			MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null,null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKESPECIAL, className, "$$crijRun", "()V", false);
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		if (!isInterface)
			generateEmptyGetSetRollbackCheckpointMethods(superIsIgnored);

		
		// Generate the stub methods that will be called on every get/set
		// In the default fast mode they do nothing.
		if(!ignoredButStillPropogate)
		{	
			generateCrijInit();
			for (RollbackState s : RollbackState.values())
				generateCrijGetClass(s);
			if (!isInterface) {
				MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "$$crijIsRollbackState", "()Z", null, null);
				mv.visitCode();
				mv.visitInsn(ICONST_0);
				mv.visitInsn(IRETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
		}
		else if (!isInterface) {
			super.visitField(Opcodes.ACC_PUBLIC, "$$crijIsRollbackState", "Z", null, 0);
			MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "$$crijIsRollbackState", "()Z", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, className, "$$crijIsRollbackState", "Z");
			mv.visitInsn(IRETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		// For static field helper
		generateCrijGetSFHelper();

		if (!isInterface) {
			// CopyFields
			generateCrijCopyFieldsTo();

			generateCrijCopyFieldsFrom();

			// propogateCheckpoint
			generateCrijPropagateCheckpoint();

			// propogateRollback
			generateCrijPropagateRollback();
			
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJgetVersion", "()I", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, className, VERSION_FIELD_NAME, "I");
			mv.visitInsn(IRETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
			mv = super.visitMethod(ACC_PUBLIC, "$$CRIJsetVersion", "(I)V", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitVarInsn(ILOAD, 1);
			mv.visitFieldInsn(PUTFIELD, className, VERSION_FIELD_NAME, "I");
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		
		//Class coverage stuff
		if(!Instrumenter.isIgnoredFromClassCoverage(className))
			super.visitField(ACC_STATIC | ACC_FINAL | ACC_PUBLIC, "$$GMU$$ClassCov", "Lnet/jonbell/crij/runtime/ClassCoverageProbe;", null, null);
		if (!hasClinit) {

			MethodVisitor mv = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
			mv.visitCode();

			if (fixLdcClass) {
				mv.visitLdcInsn(className.replace("/", "."));
				mv.visitInsn(Opcodes.ICONST_0);
				mv.visitLdcInsn(className.replace("/", "."));
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
			} else
				mv.visitLdcInsn(Type.getObjectType(className));
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/Premain", "initClass", "(Ljava/lang/Class;)V", false);

			if (!Instrumenter.isIgnoredFromClassCoverage(className)) {

				mv.visitTypeInsn(NEW, "net/jonbell/crij/runtime/ClassCoverageProbe");
				mv.visitInsn(DUP);
				if (fixLdcClass) {
					mv.visitLdcInsn(className.replace("/", "."));
					mv.visitInsn(Opcodes.ICONST_0);
					mv.visitLdcInsn(className.replace("/", "."));
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
				} else
					mv.visitLdcInsn(Type.getObjectType(className));
				mv.visitMethodInsn(INVOKESPECIAL, "net/jonbell/crij/runtime/ClassCoverageProbe", "<init>", "(Ljava/lang/Class;)V", false);

				mv.visitFieldInsn(PUTSTATIC, className, "$$GMU$$ClassCov", "Lnet/jonbell/crij/runtime/ClassCoverageProbe;");
			}
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();


		}

		super.visitEnd();

	}
	

	private void generateDummyCRIJ()
	{
		for (FieldNode fn : fields) {
			{
				// Generate getter
				Type origType = Type.getType(fn.desc);
				int isStatic = fn.access & Opcodes.ACC_STATIC;
				boolean isInstanceField = (fn.access & Opcodes.ACC_STATIC) == 0;
				int opcode = isInstanceField ? GETFIELD : GETSTATIC;
				int acc = Opcodes.ACC_PUBLIC;
				MethodVisitor mv = super.visitMethod(acc | isStatic, "crijGET" + "$$" + fn.name, "()" + fn.desc, null, null);
				mv.visitCode();
				if (opcode == GETFIELD)
					mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(opcode, className, fn.name, fn.desc);
				mv.visitInsn(origType.getOpcode(Opcodes.IRETURN));
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
			{
				// Generate getter
				Type origType = Type.getType(fn.desc);
				Type retType = origType;
				int isStatic = fn.access & Opcodes.ACC_STATIC;
				boolean isInstanceField = (fn.access & Opcodes.ACC_STATIC) == 0;
				int acc = Opcodes.ACC_PUBLIC;
				int opcodeSet = isInstanceField ? PUTFIELD : PUTSTATIC;

				MethodVisitor mv = super.visitMethod(acc | isStatic, "crijSET" + "$$" + fn.name, "(" + retType.getDescriptor() + ")V", null, null);
				mv.visitCode();

				if (isInstanceField) {
					mv.visitVarInsn(ALOAD, 0);
					mv.visitVarInsn(retType.getOpcode(Opcodes.ILOAD), 1);
				} else {
					mv.visitVarInsn(retType.getOpcode(Opcodes.ILOAD), 0);
				}
				mv.visitFieldInsn(opcodeSet, className, fn.name, retType.getDescriptor());
				mv.visitInsn(RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
		}
		if(superIsIgnored)
		{
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$crijAccess", "()V", null, null);
			mv.visitCode();
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
//		{
//			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$crijSetCalled", "()V", null, null);
//			mv.visitCode();
//			mv.visitInsn(RETURN);
//			mv.visitMaxs(0, 0);
//			mv.visitEnd();
//		}
		{
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$crijCheckpoint", "(I)V", null, null);
			mv.visitCode();
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		{
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$crijRollback", "(I)V", null, null);
			mv.visitCode();
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		{
			MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "$$crijIsRollbackState", "()Z", null, null);
			mv.visitCode();
			mv.visitInsn(ICONST_0);
			mv.visitInsn(IRETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		{
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJcopyFieldsTo", "(Ljava/lang/Object;)V", null, null);
			mv.visitCode();
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		{
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJcopyFieldsFrom", "(Ljava/lang/Object;)V", null, null);
			mv.visitCode();
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		{
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJpropagateCheckpoint", "(I)V", null, null);
			mv.visitCode();
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		{
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJpropagateRollback", "(I)V", null, null);
			mv.visitCode();
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		{
			MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJgetVersion", "()I", null, null);
			mv.visitCode();
			mv.visitInsn(ICONST_0);
			mv.visitInsn(IRETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
			mv = super.visitMethod(ACC_PUBLIC, "$$CRIJsetVersion", "(I)V", null, null);
			mv.visitCode();
			mv.visitInsn(RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
	}

	private void generateCrijInit()
	{
		MethodVisitor mv;

		mv = super.visitMethod(ACC_PUBLIC | ACC_STATIC, "$$crijInit", "()V", null, null);
		mv.visitCode();
		if (!this.isInterface) {
			mv.visitFieldInsn(GETSTATIC, className, "$$crijInited", "Z");
			Label ok = new Label();
			mv.visitJumpInsn(IFNE, ok);
			ldcClass(mv, Type.getObjectType(classToLock));
			mv.visitInsn(DUP);
			mv.visitInsn(MONITORENTER);
			mv.visitFieldInsn(GETSTATIC, className, "$$crijInited", "Z");
			Label ok2 = new Label();
			mv.visitJumpInsn(IFNE, ok2);
			for (RollbackState s : RollbackState.values()) {
				ldcThisClass(mv);
				if (s != RollbackState.Fast && s != RollbackState.Eager) {
					mv.visitIntInsn(BIPUSH, s.ordinal());
					mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", "generateClass", "(Ljava/lang/Class;I)Ljava/lang/Class;", false);
				}
				mv.visitFieldInsn(PUTSTATIC, className, "$$crij_class_" + s.name(), "Ljava/lang/Class;");
			}
			mv.visitInsn(ICONST_1);
			mv.visitFieldInsn(PUTSTATIC, className, "$$crijInited", "Z");
			mv.visitLabel(ok2);
			if (!ignoreFrames)
				mv.visitFrame(F_NEW, 0, new Object[0], 1, new Object[] { "java/lang/Class" });
			mv.visitInsn(MONITOREXIT);
			mv.visitLabel(ok);
			if (!ignoreFrames)
				mv.visitFrame(F_NEW, 0, new Object[0], 0, new Object[0]);

		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}
	
	private void generateCrijGetClass(RollbackState s)
	{
		MethodVisitor mv = super.visitMethod(ACC_PUBLIC | ACC_STATIC, "$$crijGetClass" + s.name(), "()Ljava/lang/Class;", null, null);
		mv.visitCode();
		mv.visitFieldInsn(GETSTATIC, className, "$$crijInited", "Z");
		Label ok = new Label();
		mv.visitJumpInsn(IFNE, ok);
		mv.visitMethodInsn(INVOKESTATIC, className, "$$crijInit", "()V", false);
		mv.visitLabel(ok);
		if (!ignoreFrames)
			mv.visitFrame(F_NEW, 0, new Object[0], 0, new Object[0]);
		mv.visitFieldInsn(GETSTATIC, className, "$$crij_class_" + s.name(), "Ljava/lang/Class;");
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}
	
	private void generateCrijGetSFHelper()
	{
		MethodVisitor mv = super.visitMethod(ACC_PUBLIC | ACC_STATIC, "$$CRIJGetSFHelper", "()Lnet/jonbell/crij/runtime/CRIJSFHelper;", null, null);
		mv.visitCode();
		Label ok = new Label();
//		ldcThisClass(mv);
//		mv.visitInsn(DUP);
//		mv.visitInsn(MONITORENTER);
		//Shoudl not need to synchronize on this because it should be pre-generated
		mv.visitFieldInsn(GETSTATIC, className, "$$crijSFHelper", "Lnet/jonbell/crij/runtime/CRIJSFHelper;");
		mv.visitJumpInsn(IFNONNULL, ok);
//		mv.visitInsn(DUP);
		ldcThisClass(mv);
		mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent", "generateSFHolderClass", "(Ljava/lang/Class;)Lnet/jonbell/crij/runtime/CRIJSFHelper;", false);
		mv.visitFieldInsn(PUTSTATIC, className, "$$crijSFHelper", "Lnet/jonbell/crij/runtime/CRIJSFHelper;");

		mv.visitLabel(ok);
		if (!ignoreFrames)
			mv.visitFrame(F_NEW, 0, new Object[0], 0, new Object[0]);// { "java/lang/Class" });
//		mv.visitInsn(MONITOREXIT);
		mv.visitFieldInsn(GETSTATIC, className, "$$crijSFHelper", "Lnet/jonbell/crij/runtime/CRIJSFHelper;");
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}
	
	private void generateCrijCopyFieldsTo()
	{
		MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJcopyFieldsTo", "(Ljava/lang/Object;)V", null, null);
		mv.visitCode();
		if (!superIsIgnored) {
			mv.visitVarInsn(ALOAD, 0);
			mv.visitVarInsn(ALOAD, 1);
			mv.visitMethodInsn(INVOKESPECIAL, superName, "$$CRIJcopyFieldsTo", "(Ljava/lang/Object;)V", false);
		}
		if (!ignoredButStillPropogate) {
			mv.visitVarInsn(ALOAD, 1);
			mv.visitTypeInsn(CHECKCAST, className);
			for (FieldNode fn : fields) {
				if ((ACC_STATIC & fn.access) == 0) {
					mv.visitInsn(DUP);
					mv.visitVarInsn(ALOAD, 0);
					mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
					mv.visitFieldInsn(PUTFIELD, className, fn.name, fn.desc);
				}
			}
			mv.visitInsn(POP);
		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private void generateCrijCopyFieldsFrom()
	{
		MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJcopyFieldsFrom", "(Ljava/lang/Object;)V", null, null);
		mv.visitCode();
		if (!superIsIgnored) {
			mv.visitVarInsn(ALOAD, 0);
			mv.visitVarInsn(ALOAD, 1);
			mv.visitMethodInsn(INVOKESPECIAL, superName, "$$CRIJcopyFieldsFrom", "(Ljava/lang/Object;)V", false);
		}
		if (!ignoredButStillPropogate) {
			mv.visitVarInsn(ALOAD, 0);
			for (FieldNode fn : fields) {
				if ((ACC_STATIC & fn.access) == 0) {
					mv.visitInsn(DUP);
					mv.visitVarInsn(ALOAD, 1);
					mv.visitTypeInsn(CHECKCAST, className);
					mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
					mv.visitFieldInsn(PUTFIELD, className, fn.name, fn.desc);
				}
			}
			mv.visitInsn(POP);
		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

	}

	private void generateCrijPropagateCheckpoint()
	{
		MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJpropagateCheckpoint", "(I)V", null, null);
		mv.visitCode();
		if (!superIsIgnored) {
			mv.visitVarInsn(ALOAD, 0);
			mv.visitVarInsn(ILOAD, 1);
			mv.visitMethodInsn(INVOKESPECIAL, superName, "$$CRIJpropagateCheckpoint", "(I)V", false);
		}
		for (FieldNode fn : fields) {
			Type t = Type.getType(fn.desc);
			if ((ACC_STATIC & fn.access) != 0)
				continue; //static fields are handled separately by the static field helper class
			if (t.getSort() == Type.OBJECT && !Instrumenter.isIgnoredClass(t.getInternalName()))
			{
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
				Label isNull = new Label();
				mv.visitJumpInsn(IFNULL, isNull);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
				mv.visitVarInsn(ILOAD, 1);
				//TODO this shoudl NOT have to be invokeinterface, but it does, becaue we don't know
				//if the field type is an interface :/
				mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$crijCheckpoint", "(I)V", true);
				mv.visitLabel(isNull);
				if (!ignoreFrames)
					mv.visitFrame(Opcodes.F_SAME, 0, new Object[0], 0, new Object[0]);
			} else if (t.getSort() == Type.ARRAY) { 
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
				mv.visitVarInsn(ILOAD, 1);
				mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ArrayWrapper", "propagateCheckpoint", "(Ljava/lang/Object;I)V", false);
			} else if (t.getSort() == Type.OBJECT) {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
				mv.visitVarInsn(ILOAD, 1);
				mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateCheckpoint", "(Ljava/lang/Object;I)V", false);
			}
		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private void generateCrijPropagateRollback()
	{
		MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "$$CRIJpropagateRollback", "(I)V", null, null);
		mv.visitCode();
		if (!superIsIgnored) {
			mv.visitVarInsn(ALOAD, 0);
			mv.visitVarInsn(ILOAD, 1);
			mv.visitMethodInsn(INVOKESPECIAL, superName, "$$CRIJpropagateRollback", "(I)V", false);
		}
		for (FieldNode fn : fields) {
			Type t = Type.getType(fn.desc);
			if ((ACC_STATIC & fn.access) != 0)
				// Skip static fields
				//TODO
				continue;

			if (t.getSort() == Type.OBJECT && !Instrumenter.isIgnoredClass(t.getInternalName())) {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
				Label isNull = new Label();
				mv.visitJumpInsn(IFNULL, isNull);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
				mv.visitVarInsn(ILOAD, 1);
				//TODO this shoudl NOT have to be invokeinterface, but it does, becaue we don't know
				//if the field type is an interface :/
				mv.visitMethodInsn(INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$crijRollback", "(I)V", true);
				mv.visitLabel(isNull);
				if (!ignoreFrames)
					mv.visitFrame(Opcodes.F_SAME, 0, new Object[0], 0, new Object[0]);
			} else if (t.getSort() == Type.ARRAY) {
					mv.visitVarInsn(ALOAD, 0);
					mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
					mv.visitVarInsn(ILOAD, 1);
					mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ArrayWrapper", "propagateRollback", "(Ljava/lang/Object;I)V", false);
			} else if (t.getSort() == Type.OBJECT) {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitFieldInsn(GETFIELD, className, fn.name, fn.desc);
				mv.visitVarInsn(ILOAD, 1);
				mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateRollback", "(Ljava/lang/Object;I)V", false);
		}
		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

}
