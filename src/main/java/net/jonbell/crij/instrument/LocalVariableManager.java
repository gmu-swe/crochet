package net.jonbell.crij.instrument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;

public class LocalVariableManager extends OurLocalVariablesSorter implements Opcodes {
	private AnalyzerAdapter analyzer;
	private static final boolean DEBUG = false;
	int createdLVIdx = 0;
	HashSet<LocalVariableNode> createdLVs = new HashSet<LocalVariableNode>();
	HashMap<Integer, LocalVariableNode> curLocalIdxToLVNode = new HashMap<Integer, LocalVariableNode>();
	MethodVisitor uninstMV;

	Type returnType;

	@Override
	public void visitInsn(int opcode) {
		super.visitInsn(opcode);
	}

	@Override
	public void visitIincInsn(int var, int increment) {
		if(var > lastRealArgPos)
			var += nNewArgs;
		super.visitIincInsn(var, increment);
	}
	@Override
	public void visitVarInsn(int opcode, int var) {
		if(var > lastRealArgPos)
			var += nNewArgs;
		super.visitVarInsn(opcode, var);
	}

	public HashMap<Integer, Integer> varToShadowVar = new HashMap<Integer, Integer>();

	private int nNewArgs;
	private int lastRealArgPos;
	public LocalVariableManager(int access, String desc, String oldDesc, MethodVisitor mv, AnalyzerAdapter analyzer, MethodVisitor uninstMV, int nNewArgs) {
		super(ASM5, access, desc, mv);
		this.analyzer = analyzer;
		this.uninstMV = uninstMV;
		this.nNewArgs = nNewArgs;
		returnType = Type.getReturnType(desc);
		if ((access & Opcodes.ACC_STATIC) == 0) {
			lastRealArgPos++;
		}
		Type[] oldArgs = Type.getArgumentTypes(oldDesc);
		for (int i = 0; i < oldArgs.length; i++) {
			lastRealArgPos += oldArgs[i].getSize();
		}
		lastRealArgPos--;
		end = new Label();
		if(nNewArgs != 0)
			changed = true;
//		System.out.println(desc + " from " + oldDesc + ", " + lastRealArgPos);
		// System.out.println("New LVS");
		// System.out.println("LVS thinks its at " + lastArg);
	}

	public void freeTmpLV(int idx) {
		for (TmpLV v : tmpLVs) {
			if (v.idx == idx && v.inUse) {
				Label lbl = new Label();
				super.visitLabel(lbl);
				curLocalIdxToLVNode.get(v.idx).end = new LabelNode(lbl);
				v.inUse = false;
				v.owner = null;
				if (idx < analyzer.locals.size())
					analyzer.locals.set(idx, Opcodes.TOP);
				return;
			}
		}
		// System.err.println(tmpLVs);
		throw new IllegalArgumentException("asked to free tmp lv " + idx + " but couldn't find it?");
	}

	@Deprecated
	public int newLocal(Type type) {
		int idx = super.newLocal(type);
		Label lbl = new Label();
		super.visitLabel(lbl);

		LocalVariableNode newLVN = new LocalVariableNode("phosphorShadowLV" + createdLVIdx, type.getDescriptor(), null, new LabelNode(lbl), new LabelNode(end), idx);
		createdLVs.add(newLVN);
		curLocalIdxToLVNode.put(idx, newLVN);
		createdLVIdx++;

		return idx;
	}


	protected int remap(int var, Type type) {
		int ret = super.remap(var, type);
		// System.out.println(var +" -> " + ret);
		Object objType = "[I";
		switch (type.getSort()) {
		case Type.BOOLEAN:
		case Type.SHORT:
		case Type.INT:
			objType = Opcodes.INTEGER;
			break;
		case Type.LONG:
			objType = Opcodes.LONG;
			break;
		case Type.DOUBLE:
			objType = Opcodes.DOUBLE;
			break;
		case Type.FLOAT:
			objType = Opcodes.FLOAT;
			break;
		}
		return ret;
	}

	private int newPreAllocedReturnType(Type type) {
		int idx = super.newLocal(type);
		Label lbl = new Label();
		super.visitLabel(lbl);
		// System.out.println("End is going to be " + end);
		LocalVariableNode newLVN = new LocalVariableNode("phosphorReturnPreAlloc" + createdLVIdx, type.getDescriptor(), null, new LabelNode(lbl), new LabelNode(end), idx);
		createdLVs.add(newLVN);
		curLocalIdxToLVNode.put(idx, newLVN);
		createdLVIdx++;
		analyzer.locals.add(idx, type.getInternalName());
		return idx;
	}

	@Override
	public void remapLocal(int local, Type type) {
		Label lbl = new Label();
		super.visitLabel(lbl);
		curLocalIdxToLVNode.get(local).end = new LabelNode(lbl);
		super.remapLocal(local, type);

		LocalVariableNode newLVN = new LocalVariableNode("phosphorShadowLV" + createdLVIdx, type.getDescriptor(), null, new LabelNode(lbl), new LabelNode(end), local);
		createdLVs.add(newLVN);
		curLocalIdxToLVNode.put(local, newLVN);

		createdLVIdx++;
	}

	/**
	 * Gets a tmp lv capable of storing the top stack el
	 * 
	 * @return
	 */
	public int getTmpLV() {
		Object obj = analyzer.stack.get(analyzer.stack.size() - 1);
		if (obj instanceof String)
			return getTmpLV(Type.getObjectType((String) obj));
		if (obj == Opcodes.INTEGER)
			return getTmpLV(Type.INT_TYPE);
		if (obj == Opcodes.FLOAT)
			return getTmpLV(Type.FLOAT_TYPE);
		if (obj == Opcodes.DOUBLE)
			return getTmpLV(Type.DOUBLE_TYPE);
		if (obj == Opcodes.LONG)
			return getTmpLV(Type.LONG_TYPE);
		if (obj == Opcodes.TOP) {
			obj = analyzer.stack.get(analyzer.stack.size() - 2);
			if (obj == Opcodes.DOUBLE)
				return getTmpLV(Type.DOUBLE_TYPE);
			if (obj == Opcodes.LONG)
				return getTmpLV(Type.LONG_TYPE);
		}
		return getTmpLV(Type.getType("Ljava/lang/Object;"));

	}

	public static Object getStackTypeForType(Type t) {
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

	HashSet<Integer> tmpLVIdices = new HashSet<Integer>();

	static boolean isSameRawType(Type t1, Type t2)
	{
		if(t1.getSort() == t2.getSort())
			return true;
		if((t1.getSort() == Type.OBJECT || t1.getSort() == Type.ARRAY) && (t2.getSort() == Type.OBJECT || t2.getSort() == Type.ARRAY))
				return true;
		return false;
	}
	public int getTmpLV(Type t) {
		if (t.getDescriptor().equals("java/lang/Object;"))
			throw new IllegalArgumentException();
		for (TmpLV lv : tmpLVs) {
			if (!lv.inUse && isSameRawType(lv.type, t)) {
				if (!lv.type.equals(t)) {
					super.remapLocal(lv.idx, t);
					if (analyzer.locals != null && lv.idx < analyzer.locals.size()) {
						analyzer.locals.set(lv.idx, getStackTypeForType(t));
					}
					lv.type = t;
				}
				lv.inUse = true;
				if (DEBUG) {
					lv.owner = new IllegalStateException("Unclosed tmp lv created at:");
					lv.owner.fillInStackTrace();
				}
				Label lbl = new Label();
				super.visitLabel(lbl);
				LocalVariableNode newLVN = new LocalVariableNode("phosphorShadowLV" + createdLVIdx, t.getDescriptor(), null, new LabelNode(lbl), new LabelNode(end), lv.idx);
				createdLVs.add(newLVN);
				curLocalIdxToLVNode.put(lv.idx, newLVN);
				return lv.idx;
			}
		}
		TmpLV newLV = new TmpLV();
		newLV.idx = newLocal(t);
		newLV.type = t;
		newLV.inUse = true;
		tmpLVs.add(newLV);
		tmpLVIdices.add(newLV.idx);
		if (DEBUG) {
			newLV.owner = new IllegalStateException("Unclosed tmp lv created at:");
			newLV.owner.fillInStackTrace();
		}
		return newLV.idx;
	}

	ArrayList<TmpLV> tmpLVs = new ArrayList<LocalVariableManager.TmpLV>();

	boolean endVisited = false;

	@Override
	public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
		super.visitLocalVariable(name, desc, signature, start, end, index);
		if (!createdLVs.isEmpty()) {
			if (!endVisited) {
				super.visitLabel(this.end);
				endVisited = true;
			}
			for (LocalVariableNode n : createdLVs) {
				uninstMV.visitLocalVariable(n.name, n.desc, n.signature, n.start.getLabel(), n.end.getLabel(), n.index);
			}
			createdLVs.clear();
		}
	}

	Label end;

	@Override
	public void visitMaxs(int maxStack, int maxLocals) {
		if (!endVisited) {
			super.visitLabel(end);
			endVisited = true;
		}
		super.visitMaxs(maxStack, maxLocals);
	}

	@Override
	public void visitEnd() {
		super.visitEnd();
		for (TmpLV l : tmpLVs) {
			if (l.inUse)
				throw l.owner;
		}
	}

	private class TmpLV {
		int idx;
		Type type;
		boolean inUse;
		IllegalStateException owner;

		@Override
		public String toString() {
			return "TmpLV [idx=" + idx + ", type=" + type + ", inUse=" + inUse + "]";
		}
	}

	public void visitCode() {
		super.visitCode();
	}

	@Override
	public void visitFrame(final int type, final int nLocal, final Object[] local, final int nStack, final Object[] stack) {
		if (type == -100) {
			mv.visitFrame(Opcodes.F_NEW, nLocal, local, nStack, stack);
			return;
		}
		if (type != Opcodes.F_NEW) { // uncompressed frame
			throw new IllegalStateException("ClassReader.accept() should be called with EXPAND_FRAMES flag");
		}
		if (!changed && !isFirstFrame) { // optimization for the case where
											// mapping = identity
			mv.visitFrame(type, nLocal, local, nStack, stack);
			return;
		}
		isFirstFrame = false;
		// System.out.println("nlocal " + nLocal);
//		 System.out.println(Arrays.toString(local));
		// System.out.println(Arrays.toString(newLocals));
		// creates a copy of newLocals
		Object[] oldLocals = new Object[newLocals.length];
		System.arraycopy(newLocals, 0, oldLocals, 0, oldLocals.length);

		updateNewLocals(newLocals);

		for (int i = 0; i < newLocals.length; i++) {
			// Ignore tmp lv's in the stack frames.
			if (tmpLVIdices.contains(i))
				newLocals[i] = Opcodes.TOP;
		}

		ArrayList<Object> locals = new ArrayList<Object>();
		for (Object o : local) {
			locals.add(o);
			if (o == Opcodes.DOUBLE || o == Opcodes.LONG)
				locals.add(Opcodes.TOP);
		}

		// copies types from 'local' to 'newLocals'
		// 'newLocals' currently empty

		// System.out.println(Arrays.toString(newLocals));
		int index = 0; // old local variable index
		int number = 0; // old local variable number
		for (; number < nLocal; ++number) {
			Object t = local[number];
			int size = t == Opcodes.LONG || t == Opcodes.DOUBLE ? 2 : 1;
			if (t != Opcodes.TOP) {
				Type typ = OBJECT_TYPE;
				if (t == Opcodes.INTEGER) {
					typ = Type.INT_TYPE;
				} else if (t == Opcodes.FLOAT) {
					typ = Type.FLOAT_TYPE;
				} else if (t == Opcodes.LONG) {
					typ = Type.LONG_TYPE;
				} else if (t == Opcodes.DOUBLE) {
					typ = Type.DOUBLE_TYPE;
				} else if (t instanceof String) {
					typ = Type.getObjectType((String) t);
				}
				int _idx = index;
				if(_idx > lastRealArgPos)
					_idx += nNewArgs;
				setFrameLocal(remap(_idx, typ), t);
			}
			index += size;
		}

		// removes TOP after long and double types as well as trailing TOPs

		index = 0;
		number = 0;
		for (int i = 0; index < newLocals.length; ++i) {
			Object t = newLocals[index++];
			if (t != null && t != Opcodes.TOP) {
				newLocals[i] = t;
				number = i + 1;
				if (t == Opcodes.LONG || t == Opcodes.DOUBLE) {
					index += 1;
				}
			} else {
				newLocals[i] = Opcodes.TOP;
			}

		}
		// visits remapped frame
		mv.visitFrame(type, number, newLocals, nStack, stack);
//		 System.out.println("fin" + Arrays.toString(newLocals) +", " + number);

		// restores original value of 'newLocals'
		newLocals = oldLocals;
	}
}
