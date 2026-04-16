package net.jonbell.crij.instrument;

import static org.objectweb.asm.Opcodes.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;

import net.jonbell.crij.runtime.ReflectionFixer;
import net.jonbell.crij.runtime.internalstruct.FieldInfoNode;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import sun.misc.Unsafe;

public class CheckpointRollbackFieldMV extends MethodVisitor {	
	private AnalyzerAdapter analyzer;

	public void setAnalyzer(AnalyzerAdapter analyzer) {
		this.analyzer = analyzer;
	}
	private boolean ignoreReflectionMasking;
	private String superClass;
	
	private boolean straightUnsafe;
	
	public CheckpointRollbackFieldMV(MethodVisitor mv, MethodNode annotationInfoHolder, boolean fixLdcClass, String name, String className, String superClass, HashSet<String> localStaticFields) {
		super(ASM5, mv);
		this.fixLDCClass = fixLdcClass;
		this.methodName = name;
		this.className = className;
		this.ignoreReflectionMasking = className.equals("net/jonbell/crij/test/AliasingITCase");
		this.isUninitConstructor = name.equals("<init>");
		this.localStaticFields = localStaticFields;
		this.superClass = superClass;
		this.straightUnsafe = className.startsWith("java/lang/reflect") ||
				className.equals("java/util/concurrent/atomic/AtomicLong")||
				className.equals("java/util/concurrent/atomic/AtomicInteger") || (className.equals("java/util/concurrent/ConcurrentHashMap") &&( name.equals("tabAt") || name.equals("casTabAt") || name.equals("addCount")));
	}
	private boolean fixLDCClass;
	private String methodName;
	private String className;
	private boolean isUninitConstructor;

	private HashSet<String> localStaticFields;

	@Override
	public void visitCode() {
		super.visitCode();
		if (methodName.equals("<clinit>")) {
			if (fixLDCClass) {
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
				if (fixLDCClass) {
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
		}
	}
	@Override
	public void visitInsn(int opcode) {
		super.visitInsn(opcode);
	}
	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
		if (isUninitConstructor && opcode == INVOKESPECIAL && name.equals("<init>") && owner.equals(superClass))
			isUninitConstructor = false;
		//Stupid workaround for eclipse benchmark
		if (name.equals("getProperty") && className.equals("org/eclipse/jdt/core/tests/util/Util")) {
			owner = Type.getInternalName(ReflectionFixer.class);
			name = "getPropertyHideBootClasspath";
		}
		else if(owner.equals("org/eclipse/jdt/core/JavaCore") && name.equals("getClasspathVariable"))
		{
			mv.visitLdcInsn("org.eclipse.core.runtime.Path");
			mv.visitInsn(Opcodes.ICONST_0);
			mv.visitLdcInsn(className.replace("/", "."));
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);

			mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/ReflectionFixer", "getClassPath", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
			mv.visitTypeInsn(CHECKCAST, "org/eclipse/core/runtime/Path");
			return;
		}
		if (owner.equals("java/lang/reflect/Field")
				&& opcode == Opcodes.INVOKEVIRTUAL
				&& (name.equals("get") || name.equals("set") || name.equals("getInt") || name.equals("getBoolean") || name.equals("getChar") || name.equals("getDouble") || name.equals("getByte") || name.equals("getFloat") || name.equals("getLong") || name.equals("getShort") || name.equals("setInt")
						|| name.equals("setBoolean") || name.equals("setChar") || name.equals("setDouble") || name.equals("setByte") || name.equals("setFloat") || name.equals("setLong") || name.equals("setShort"))) {
			owner = Type.getInternalName(ReflectionFixer.class);
			opcode = Opcodes.INVOKESTATIC;
			desc = "(Ljava/lang/reflect/Field;" + desc.substring(1);
		} else if (!ignoreReflectionMasking && name.equals("getClass") && opcode != INVOKESTATIC && desc.equals("()Ljava/lang/Class;")) {
			owner = Type.getInternalName(ReflectionFixer.class);
			opcode = Opcodes.INVOKESTATIC;
			desc = "(Ljava/lang/Object;)Ljava/lang/Class;";
		} else if (!ignoreReflectionMasking && owner.equals("java/lang/Class") && opcode == Opcodes.INVOKEVIRTUAL && (name.equals("getFields") || name.equals("getDeclaredFields"))) {
			super.visitMethodInsn(opcode, owner, name, desc, itf);
			super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(ReflectionFixer.class), "removeInternalFields", "([Ljava/lang/reflect/Field;)[Ljava/lang/reflect/Field;", false);
			return;
		} else if (!ignoreReflectionMasking && owner.equals("java/lang/Class") && opcode == Opcodes.INVOKEVIRTUAL && (name.equals("getMethods") || name.equals("getDeclaredMethods"))) {
			super.visitMethodInsn(opcode, owner, name, desc, itf);
			super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(ReflectionFixer.class), "removeInternalMethods", "([Ljava/lang/reflect/Method;)[Ljava/lang/reflect/Method;", false);
			return;
		} else if (!ignoreReflectionMasking && owner.equals("java/lang/Class") && opcode == Opcodes.INVOKEVIRTUAL && (name.equals("getInterfaces")))
		{
			super.visitMethodInsn(opcode, owner, name, desc, itf);
			super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(ReflectionFixer.class), "removeInternalInterface", "([Ljava/lang/Class;)[Ljava/lang/Class;", false);
			return;
		}
		else if(!straightUnsafe && owner.equals("sun/misc/Unsafe") && (name.startsWith("get") || name.startsWith("put") || name.startsWith("compareAndSwap")))
		{
//			System.out.println(name+","+desc);
//			System.out.println(analyzer.stack);
			boolean canIgnore = false;
			Type[] args = Type.getArgumentTypes(desc);
			if(args.length > 0 && args[0].getSort() == Type.OBJECT)
 {
				int offsetToObj = 1;
				for (int i = 1; i < args.length; i++) {
					offsetToObj += args[i].getSize();
				}
				Object calleeType = (Object) analyzer.stack.get(analyzer.stack.size() - offsetToObj);
				if (calleeType instanceof String) {
					String strCallee = (String) calleeType;
					if (strCallee.charAt(0) == '[') {
						// We are using unsafe on an array
						// We can allow this to just happen natively
						canIgnore = true;
					} else if (!Instrumenter.isIgnoredClass(strCallee)) {
						// We can NOT ignore it, but we can do an invokevirtual!
						if (args.length == 2) {
							if (args[1].getSize() == 2) {
								// U O JJ
								super.visitInsn(DUP2_X1);
								super.visitInsn(POP2);
								// U JJ O
								super.visitInsn(DUP_X2);
								// U O JJ O
								super.visitMethodInsn(INVOKEVIRTUAL, strCallee, "$$crijAccess", "()V", false);
								canIgnore = true;
							} else {
								// U O I
								super.visitInsn(SWAP);
								// U I O
								super.visitInsn(DUP_X1);
								// U O I O
								super.visitMethodInsn(INVOKEVIRTUAL, strCallee, "$$crijAccess", "()V", false);
								canIgnore = true;

							}
						}
					}
				}
				else if(calleeType == Opcodes.NULL)
					canIgnore = true;
			}
			if(!canIgnore)
				name = NativeWrappingCV.NATIVE_PREFIX+name;
		}
		super.visitMethodInsn(opcode, owner, name, desc, itf);
	}
	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		Type typ = Type.getType(desc);
		if (isUninitConstructor) {
			// are we accessing a field of "this" which is not init'ed yet
			if (opcode == GETFIELD || opcode == PUTFIELD) {
				//no reason to call read/write because obviously we are a new object
				super.visitFieldInsn(opcode, owner, name, desc);
				return;
			}
		}
		if (Instrumenter.isIgnoredClass(owner)) {
			super.visitFieldInsn(opcode, owner, name, desc);
		} else {
			switch (opcode) {
			case GETFIELD:
				super.visitInsn(DUP);
				super.visitMethodInsn(INVOKEVIRTUAL, owner, "$$crijAccess", "()V", false);
				super.visitFieldInsn(opcode, owner, name, desc);
				break;
			case GETSTATIC:
				localStaticFields.add(owner);
				super.visitMethodInsn(INVOKESTATIC, className, "crijGET$$" + owner.replace('/', '$'), "()V", false);
				super.visitFieldInsn(opcode, owner, name, desc);
				break;
			case PUTFIELD:
				switch (Type.getType(desc).getSize()) {
				case 1:
					super.visitInsn(Opcodes.SWAP);
					super.visitInsn(DUP);
					super.visitMethodInsn(INVOKEVIRTUAL, owner, "$$crijAccess", "()V", false);
					super.visitInsn(Opcodes.SWAP);
					super.visitFieldInsn(opcode, owner, name, desc);
					break;
				case 2:
					super.visitInsn(Opcodes.DUP2_X1);
					super.visitInsn(Opcodes.POP2);
					super.visitInsn(DUP);
					super.visitMethodInsn(INVOKEVIRTUAL, owner, "$$crijAccess", "()V", false);
					super.visitInsn(Opcodes.DUP_X2);
					super.visitInsn(Opcodes.POP);
					super.visitFieldInsn(opcode, owner, name, desc);

					break;
				}
				break;
			case PUTSTATIC:
				localStaticFields.add(owner);
				super.visitMethodInsn(INVOKESTATIC, className, "crijGET$$" + owner.replace('/', '$'), "()V", false);
				super.visitFieldInsn(opcode, owner, name, desc);

				break;
			}
		}

	}

}
