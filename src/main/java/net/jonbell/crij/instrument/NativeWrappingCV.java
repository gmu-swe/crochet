package net.jonbell.crij.instrument;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.IRETURN;

import java.util.HashSet;
import java.util.LinkedList;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;


public class NativeWrappingCV extends ClassVisitor {
	public static final String NATIVE_PREFIX = "$$CRIJNATIVEWRAP$$";
	public NativeWrappingCV(ClassVisitor cv) {
		super(Opcodes.ASM5, cv);
	}

	private String className;

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		super.visit(version, access, name, signature, superName, interfaces);
		this.className = name;
	}

	LinkedList<MethodNode> nativeMethods = new LinkedList<MethodNode>();

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		if(!name.contains("<"))
			nativeMethods.add(new MethodNode(access, name, desc, signature, exceptions));
		return super.visitMethod(access, name, desc, signature, exceptions);
	}

	private void tryRead(MethodVisitor mv)
	{
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/ReflectionFixer", "access", "(Ljava/lang/Object;)V", false);
//		mv.visitVarInsn(ALOAD, 1);
//		Label done = new Label();
//		mv.visitTypeInsn(Opcodes.INSTANCEOF, "net/jonbell/crij/runtime/CRIJInstrumented");
//		mv.visitJumpInsn(Opcodes.IFEQ, done);
//		mv.visitVarInsn(ALOAD, 1);
//		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$crijGetCalled", "()V", true);
//		mv.visitLabel(done);
	}
	private void tryWrite(MethodVisitor mv)
	{
//		mv.visitVarInsn(ALOAD, 1);
//		Label done = new Label();
//		mv.visitTypeInsn(Opcodes.INSTANCEOF, "net/jonbell/crij/runtime/CRIJInstrumented");
//		mv.visitJumpInsn(Opcodes.IFEQ, done);
//		mv.visitVarInsn(ALOAD, 1);
//		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/jonbell/crij/runtime/CRIJInstrumented", "$$crijSetCalled", "()V", true);
//		mv.visitLabel(done);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/ReflectionFixer", "access", "(Ljava/lang/Object;)V", false);
	}
	private void generateNativeWrappers() {
		boolean isUnsafe = className.equals("sun/misc/Unsafe");
		for (MethodNode mn : nativeMethods) {
			MethodVisitor mv = super.visitMethod(mn.access & ~Opcodes.ACC_NATIVE, NATIVE_PREFIX + mn.name, mn.desc, mn.signature, (String[]) mn.exceptions.toArray(new String[mn.exceptions.size()]));
			mv.visitCode();
			Type[] args = Type.getArgumentTypes(mn.desc);

			Object[] localsFrame = new Object[args.length];
			for (int i = 0; i < args.length; i++) {
				localsFrame[i] = LocalVariableManager.getStackTypeForType(args[i]);
			}

			if (isUnsafe && args.length > 0 && args[0].getSort() == Type.OBJECT) {
				mv.visitVarInsn(ALOAD, 1);
				if (mn.name.startsWith("getAndSet")) {
					mv.visitInsn(Opcodes.DUP);
					tryRead(mv);
					tryWrite(mv);
				} else if (mn.name.startsWith("compareAndSwap")) {
					mv.visitInsn(Opcodes.DUP);
					tryRead(mv);
					tryWrite(mv);
				} else if (mn.name.startsWith("get")) {
					tryRead(mv);
				} else if (mn.name.startsWith("put")) {
					tryWrite(mv);
				}
			}
			int k = 0;
			int opcode = Opcodes.INVOKESTATIC;
			if ((mn.access & Opcodes.ACC_STATIC) == 0) {
				opcode = Opcodes.INVOKESPECIAL;
				mv.visitVarInsn(ALOAD, 0);
				k++;
			}
			for (int i = 0; i < args.length; i++) {
				mv.visitVarInsn(args[i].getOpcode(ILOAD), k);
				k += args[i].getSize();
			}
			mv.visitMethodInsn(opcode, className, mn.name, mn.desc, false);
			mv.visitInsn(Type.getReturnType(mn.desc).getOpcode(IRETURN));
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

	}

	@Override
	public void visitEnd() {
		generateNativeWrappers();

		super.visitEnd();
	}
}
