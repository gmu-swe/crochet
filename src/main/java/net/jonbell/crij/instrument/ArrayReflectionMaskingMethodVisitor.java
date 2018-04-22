package net.jonbell.crij.instrument;

import net.jonbell.crij.runtime.MethodInvoke;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class ArrayReflectionMaskingMethodVisitor extends MethodVisitor {

	private String className;

	public ArrayReflectionMaskingMethodVisitor(MethodVisitor mv, String className) {
		super(ASM5, mv);
		this.className = className;
	}

	static final String RUNTIME_HELPER_INTERNAL_NAME = "net/jonbell/crij/runtime/ReflectionMasker";

	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {

		Type[] args = Type.getArgumentTypes(desc);
		if (name.equals("getClass") && desc.equals("()Ljava/lang/Class;")) {
			super.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/ReflectionMasker", "getClass", "(Ljava/lang/Object;)Ljava/lang/Class;", false);
			return;
		}
		if (owner.equals("java/lang/reflect/Array") && !owner.equals(className)) {
			owner = RUNTIME_HELPER_INTERNAL_NAME;
			name = "array" + name;
		}
		if (owner.equals("java/lang/reflect/Field")
				&& opcode == Opcodes.INVOKEVIRTUAL
				&& (name.equals("get") || name.equals("set") || name.equals("getInt") || name.equals("getBoolean") || name.equals("getChar") || name.equals("getDouble") || name.equals("getByte") || name.equals("getFloat") || name.equals("getLong") || name.equals("getShort")
						|| name.equals("setInt$$PHOSPHORTAGGED") || name.equals("setBoolean") || name.equals("setChar") || name.equals("setDouble") || name.equals("setByte") || name.equals("setFloat") || name.equals("setLong") || name.equals("setShort") || name.equals("getType"))) {
			owner = "net/jonbell/crij/runtime/ReflectionMasker";
			opcode = Opcodes.INVOKESTATIC;
			desc = "(Ljava/lang/reflect/Field;" + desc.substring(1);
		}
		if ((owner.equals("java/lang/reflect/Method") || owner.equals("java/lang/reflect/Constructor")) && (name.startsWith("invoke") || name.startsWith("newInstance"))) {

			if (owner.equals("java/lang/reflect/Method")) {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER_INTERNAL_NAME, "fixAllArgs", "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)" + Type.getDescriptor(MethodInvoke.class), false);
				// B
				super.visitInsn(Opcodes.DUP);
				// B B
				super.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(MethodInvoke.class), "m", "Ljava/lang/reflect/Method;");
				// B M
				super.visitInsn(Opcodes.SWAP);
				// M B
				super.visitInsn(Opcodes.DUP);
				super.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(MethodInvoke.class), "o", "Ljava/lang/Object;");
				super.visitInsn(Opcodes.SWAP);
				super.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(MethodInvoke.class), "a", "[Ljava/lang/Object;");
			} else {
				super.visitInsn(POP);
				super.visitInsn(Opcodes.SWAP);
				// [A C
				super.visitInsn(Opcodes.DUP_X1);
				// C [A C
				super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER_INTERNAL_NAME, "fixAllArgs", "(Lnet/jonbell/crij/runtime/wrapper/AArrayWrapper;Ljava/lang/reflect/Constructor;)Lnet/jonbell/crij/runtime/wrapper/AArrayWrapper;", false);
				super.visitInsn(ACONST_NULL);
			}
		} else if ((owner.equals("java/lang/reflect/Method")) && name.startsWith("get") && !className.equals(owner) && !className.startsWith("sun/reflect") && !className.startsWith("java/lang/Class")) {
			if (args.length == 0) {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER_INTERNAL_NAME, "getOrigMethod", "(Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;", false);
			} else if (args.length == 1) {
				super.visitInsn(Opcodes.SWAP);
				super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER_INTERNAL_NAME, "getOrigMethod", "(Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;", false);
				super.visitInsn(Opcodes.SWAP);
			} else if (args.length == 2) {
				int lv1 = lvs.getTmpLV();
				super.visitVarInsn(Opcodes.ASTORE, lv1);
				int lv2 = lvs.getTmpLV();
				super.visitVarInsn(Opcodes.ASTORE, lv2);
				super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER_INTERNAL_NAME, "getOrigMethod", "(Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;", false);
				super.visitVarInsn(Opcodes.ALOAD, lv2);
				super.visitVarInsn(Opcodes.ALOAD, lv1);
				lvs.freeTmpLV(lv1);
				lvs.freeTmpLV(lv2);
			}
		} else if ((owner.equals("java/lang/reflect/Constructor")) && name.startsWith("get") && !className.equals(owner) && !className.startsWith("sun/reflect") && !className.equals("java/lang/Class")) {
			if (args.length == 0) {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER_INTERNAL_NAME, "getOrigMethod", "(Ljava/lang/reflect/Constructor;)Ljava/lang/reflect/Constructor;", false);
			} else if (args.length == 1) {
				super.visitInsn(Opcodes.SWAP);
				super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER_INTERNAL_NAME, "getOrigMethod", "(Ljava/lang/reflect/Constructor;)Ljava/lang/reflect/Constructor;", false);
				super.visitInsn(Opcodes.SWAP);
			} else if (args.length == 2) {
				int lv1 = lvs.getTmpLV();
				super.visitVarInsn(Opcodes.ASTORE, lv1);
				int lv2 = lvs.getTmpLV();
				super.visitVarInsn(Opcodes.ASTORE, lv2);
				super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER_INTERNAL_NAME, "getOrigMethod", "(Ljava/lang/reflect/Constructor;)Ljava/lang/reflect/Constructor;", false);
				super.visitVarInsn(Opcodes.ALOAD, lv2);
				super.visitVarInsn(Opcodes.ALOAD, lv1);
				lvs.freeTmpLV(lv1);
				lvs.freeTmpLV(lv2);
			}
		} else if (owner.equals("java/lang/Class") && (((name.equals("getConstructor") || (name.equals("getDeclaredConstructor"))) && args.length == 1) || ((name.equals("getMethod") || name.equals("getDeclaredMethod"))) && args.length == 2)) {
			if (args.length == 2) {

				// super.visitMethodInsn(Opcodes.INVOKESTATIC,
				// RUNTIME_HELPER_INTERNAL_NAME, "addTypeParams",
				// "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)" +
				// Type.getDescriptor(Pair.class));
				// super.visitInsn(Opcodes.DUP);
				// super.visitFieldInsn(Opcodes.GETFIELD,
				// Type.getInternalName(Pair.class), "o0",
				// Type.getDescriptor(Class.class));
				// super.visitInsn(Opcodes.SWAP);
				// super.visitInsn(Opcodes.DUP);
				// super.visitFieldInsn(Opcodes.GETFIELD,
				// Type.getInternalName(Pair.class), "o1",
				// Type.getDescriptor(String.class));
				// super.visitInsn(Opcodes.SWAP);
				// super.visitFieldInsn(Opcodes.GETFIELD,
				// Type.getInternalName(Pair.class), "o2",
				// Type.getDescriptor(Class[].class));
				opcode = Opcodes.INVOKESTATIC;
				owner = RUNTIME_HELPER_INTERNAL_NAME;
				desc = "(Ljava/lang/Class;" + desc.substring(1);

			} else {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER_INTERNAL_NAME, "addTypeParams", "([Ljava/lang/Class;)[Ljava/lang/Class;", false);
			}
		}
		super.visitMethodInsn(opcode, owner, name, desc, itf);

		if (owner.equals("java/lang/Class") && desc.endsWith("[Ljava/lang/reflect/Field;") && !className.equals("java/lang/Class")) {
			super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/ReflectionMasker", "removeInternalFields", "([Ljava/lang/reflect/Field;)[Ljava/lang/reflect/Field;", false);
		} else if (owner.equals("java/lang/Class") && !className.equals(owner) && (desc.equals("()[Ljava/lang/reflect/Method;"))) {
			super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/ReflectionMasker", "removeInternalMethods", "([Ljava/lang/reflect/Method;)[Ljava/lang/reflect/Method;", false);
		} else if (owner.equals("java/lang/Class") && !className.equals(owner) && (desc.equals("()[Ljava/lang/reflect/Constructor;"))) {
			super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/ReflectionMasker", "removeInternalConstructors", "([Ljava/lang/reflect/Constructor;)[Ljava/lang/reflect/Constructor;", false);
		} else if (owner.equals("java/lang/Class") && name.equals("getInterfaces")) {
			super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/ReflectionMasker", "removeInternalInterface", "([Ljava/lang/Class;)[Ljava/lang/Class;", false);
		}
	}

	private LocalVariableManager lvs;

	public void setLVM(LocalVariableManager lvm) {
		this.lvs = lvm;
	}
}
