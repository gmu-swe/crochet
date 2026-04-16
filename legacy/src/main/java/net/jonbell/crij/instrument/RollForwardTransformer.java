package net.jonbell.crij.instrument;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;


import net.jonbell.crij.instrument.asm.OffsetPreservingClassReader;
import net.jonbell.crij.instrument.asm.OffsetPreservingLabel;

public class RollForwardTransformer {

	/*
	 * These methods will return the arg at the index specified in the NEXT
	 * frame that we are goign to be jumping in to
	 */
	public static native Object getLVObject(int a, int d);

	public static native int getLVInt(int a, int d);

	public static native long getLVLong(int a, int d);

	public static native float getLVFloat(int a, int d);

	public static native double getLVDouble(int a, int d);

	/*
	 * These methods return the top element on the stack in the current frame
	 * They will pop that off of the stack copy.
	 */
	public static native Object getStackObject();

	public static native int getStackInt();

	public static native long getStackLong();

	public static native float getStackFloat();

	public static native double getStackDouble();

	private static native void advanceFrame();

	public static native Object getOwnedMonitor(int idx);
	
	static ThreadLocal<Integer> nextReplayKey;
	
	
	public static void moveForwardTo(int nextKey) {
		System.out.println("Moving forward to " + nextKey + " in " + Thread.currentThread());

		nextReplayKey.set(nextKey);
		advanceFrame();
	}

	public static boolean shouldJump(int in, Class<?> thisClazz) {
		System.out.println("Jump check " + in + " vs " + nextReplayKey.get() + "in " + Thread.currentThread() + " " + thisClazz);
//		new Exception().printStackTrace();
		if(tops == null)
			return false;
		RollForward top = tops.get(Thread.currentThread());
		boolean shouldJump = false;
		if(top != null && top.ret == in)
		{
			tops.remove(Thread.currentThread());
			shouldJump = true;
			//This is the *top* guy for *this* thread
			advanceFrame(); //this is the top, make sure we point to the first frame!
		}
		else if (nextReplayKey.get() == in) {
			nextReplayKey.set(-1);
			shouldJump = true;
		}
		if(shouldJump)
		{
			int outstanding = 0;
			synchronized (outstandingRollForwards) {
				outstanding = outstandingRollForwards.get(thisClazz);
				outstandingRollForwards.put(thisClazz, outstanding-1);
			}
			if(outstanding == 1)
			{
				System.out.println("Ready to reset class " + thisClazz);
				try {
					Premain.inst.retransformClasses(thisClazz);
				} catch (UnmodifiableClassException e) {
					e.printStackTrace();
				}
			}
			return true;
		}
		return false;
	}

	public static void doTransform() {
		try {
			if (transforms != null)
				Premain.inst.retransformClasses(transforms.keySet().toArray(new Class[transforms.size()]));
		} catch (Throwable e) {
			e.printStackTrace();
		}
		transforms = null;
	}

	static HashMap<Thread, RollForward> tops;
	static HashMap<Class<?>, LinkedList<RollForward>> transforms;

	static ArrayList<RollForward> rfsByRet;
	static HashMap<Class<?>, Integer> outstandingRollForwards = new HashMap<Class<?>, Integer>();
//	static HashMap<Thread, LinkedList<RollForward>> transformsByThread =  new HashMap<Thread, LinkedList<RollForward>>();
	public static int transformForRollForward(Class<?> c, String methodName, String methodDesc, String calledDesc, boolean isStaticCall, Thread t, int off, int key, int nMonitors, int version) {
		if (transforms == null)
			transforms = new HashMap<Class<?>, LinkedList<RollForward>>();
		if(tops == null)
			tops = new HashMap<Thread, RollForwardTransformer.RollForward>();
		
		nextReplayKey = new ThreadLocal<Integer>(){
			@Override
			protected Integer initialValue() {
				return -1;
			}
		};
		RollForward f = new RollForward();
		f.methodDesc = methodDesc;
		f.methodName = methodName;
		f.calledDesc = calledDesc;
		f.off = off;
		f.nMonitors = nMonitors;
		if (f.calledDesc == null)// this is the call to the checkpoint method
		{
			f.isCallToCheckpoint = true;
			f.off = f.off + 4;
		}
		f.key = key;
		f.isStaticCall = isStaticCall;
		maxKey++;
		f.ret = maxKey;
		f.version = version;
		if (!transforms.containsKey(c))
			transforms.put(c, new LinkedList<RollForwardTransformer.RollForward>());
		transforms.get(c).add(f);
		synchronized (outstandingRollForwards) {
			if (!outstandingRollForwards.containsKey(c))
				outstandingRollForwards.put(c, 0);
			outstandingRollForwards.put(c, outstandingRollForwards.get(c) + 1);
		}

		tops.put(t, f);
		return f.ret;
	}

	static class RollForward {
		public int version;
		public int nMonitors;
		public boolean isStaticCall;
		String calledDesc;
		String methodName;
		String methodDesc;
		boolean isCallToCheckpoint;
		Thread t;
		int off;
		int regularOffset;
		int key;
		int ret;
		Label l;
		protected FrameNode fn;

		@Override
		public String toString() {
			return "RollForward [" + methodName + "." + methodDesc + ", off=" + off + "]";
		}
	}

	private static int maxKey;

	public static class Transformer implements ClassFileTransformer {

		@Override
		public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
			if (classBeingRedefined == null)
				return null;
			if(transforms == null)
				return null;
			try {
				HashMap<String, LinkedList<RollForward>> calls = new HashMap<String, LinkedList<RollForward>>();
				for (RollForward rf : transforms.get(classBeingRedefined)) {
					String k = rf.methodName + rf.methodDesc;
					if (!calls.containsKey(k))
						calls.put(k, new LinkedList<RollForwardTransformer.RollForward>());
					calls.get(k).add(rf);
				}
				ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				ClassReader cr = new OffsetPreservingClassReader(classfileBuffer);
				ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, cw) {
					@Override
					public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
						super.visit(version, access, name, signature, superName, interfaces);
					}

					public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
						MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
						String k = name + desc;
						LinkedList<RollForward> rfs = calls.get(k);
						if (rfs != null) {

							AnalyzerAdapter an = new AnalyzerAdapter(className, access, name, desc, mv);
							mv = new InstructionAdapter(Opcodes.ASM5, an) {
								
								Object[] removeLongsDoubleTopVal(List<Object> in) {
									ArrayList<Object> ret = new ArrayList<Object>();
									boolean lastWas2Word = false;
									for (Object n : in) {
										if (n == Opcodes.TOP && lastWas2Word) {
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

								FrameNode getCurrentFrameNode() {
									if (an.locals == null || an.stack == null)
										throw new IllegalArgumentException();
									Object[] locals = removeLongsDoubleTopVal(an.locals);
									Object[] stack = removeLongsDoubleTopVal(an.stack);
									FrameNode ret = new FrameNode(Opcodes.F_NEW, locals.length, locals, stack.length, stack);
									return ret;
								}
								@Override
								public void visitCode() {
									super.visitCode();
									for (RollForward rf : rfs) {
										if(rf.fn == null)
											throw new IllegalStateException("Couldn't find frame node at offset " + rf.off + " should call " + rf.calledDesc + " in  " + rf.methodName+rf.methodDesc);
										FrameNode fn = getCurrentFrameNode();
//										System.out.println("Locals in " +className+"."+ rf.methodName+": "+rf.fn.local);
//										System.out.println("Stack " + rf.fn.stack);
										Label ok = new Label();
										
										super.iconst(rf.ret);
										visitLdcInsn(Type.getObjectType(className));

										super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "shouldJump", "(ILjava/lang/Class;)Z", false);
										//MUST load in stack stuff here - because later on we might be in a try/catch block and that wont be fun to fix
										super.visitJumpInsn(Opcodes.IFEQ, ok);
										// Add jump-to
										
										if (rf == tops.get(rf.t)) {
											//acquire locks if they exist...
											for(int i = 0; i < rf.nMonitors;i++)
											{
												iconst(i);
												super.visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getOwnedMonitor", "(I)Ljava/lang/Object;", false);
												super.visitInsn(Opcodes.MONITORENTER);
											}
										}

										int idx = 0;
										if (((access & Opcodes.ACC_STATIC) == 0))
											idx++;// don't try to set 'THIS'
										for (int i = idx; i < rf.fn.local.size(); i++) {
											Object o = rf.fn.local.get(i);
											if (o == Opcodes.INTEGER) {
												iconst(idx);
												iconst(0);
												visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getLVInt", "(II)I", false);
												visitVarInsn(Opcodes.ISTORE, idx);
												idx++;
											} else if (o == Opcodes.FLOAT) {
												iconst(idx);
												iconst(0);
												visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getLVFloat", "(II)F", false);
												visitVarInsn(Opcodes.FSTORE, idx);
												idx++;
											} else if (o == Opcodes.DOUBLE) {
												iconst(idx);
												iconst(0);
												visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getLVDouble", "(II)D", false);
												visitVarInsn(Opcodes.DSTORE, idx);
												idx += 2;
											} else if (o == Opcodes.LONG) {
												iconst(idx);
												iconst(0);
												visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getLVLong", "(II)J", false);
												visitVarInsn(Opcodes.LSTORE, idx);
												idx+=2;
											} else {
												iconst(idx);
												iconst(0);
												visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getLVObject", "(II)Ljava/lang/Object;", false);
												if (o instanceof String)
													visitTypeInsn(Opcodes.CHECKCAST, ((String) o));
												visitInsn(Opcodes.DUP);
												iconst(rf.version);
												visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateRollback", "(Ljava/lang/Object;I)V", false);
												visitVarInsn(Opcodes.ASTORE, idx);
												idx++;
											}
										}
										if (rf.isCallToCheckpoint) {
											super.visitInsn(Opcodes.ICONST_0);
										} else {
											// First, load on anything that
											// SHOULD be on the stack here
											LinkedList<Object> stack = new LinkedList<Object>(rf.fn.stack);
											int nArgs = 0;
											if (!rf.isStaticCall)
												nArgs++;
											Type[] args = Type.getArgumentTypes(rf.calledDesc);
											nArgs += args.length;
//											System.out.println(stack +", nargs " + nArgs);
											for (int i = 0; i < stack.size() - nArgs; i++) {
												Object o = stack.pop();
												if (o == Opcodes.NULL || o instanceof String) {
													visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getStackObject", "()Ljava/lang/Object;", false);
													if (o instanceof String)
														visitTypeInsn(Opcodes.CHECKCAST, (String) o);
													visitInsn(Opcodes.DUP);
													iconst(rf.version);
													visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/runtime/wrapper/ObjectWrapper", "propagateRollback", "(Ljava/lang/Object;I)V", false);
												} else if (o == Opcodes.INTEGER) {
													visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getStackInt", "()I", false);
												} else if (o == Opcodes.LONG) {
													visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getStackLong", "()J", false);
												} else if (o == Opcodes.FLOAT) {
													visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getStackFloat", "()F", false);
												} else if (o == Opcodes.DOUBLE) {
													visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getStackDouble", "()D", false);
												}
											}
//											System.out.println(">>>" + stack);
//											System.out.println("Call to desc " + rf.calledDesc + ", is static?" + rf.isStaticCall);
											idx = 0;
											if (!rf.isStaticCall) {
												// need to get "THIS"
												String r = (String) stack.pop();
												iconst(0);
												iconst(1);
												visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "getLVObject", "(II)Ljava/lang/Object;", false);
												visitTypeInsn(Opcodes.CHECKCAST, r);
											}
											for (int i = 0; i < args.length; i++) {
												Type t = args[i];
												switch (t.getSort()) {
												case Type.OBJECT:
												case Type.ARRAY:
													visitInsn(Opcodes.ACONST_NULL);
													break;
												case Type.INT:
												case Type.SHORT:
												case Type.CHAR:
												case Type.BOOLEAN:
												case Type.BYTE:
													visitInsn(Opcodes.ICONST_0);
													break;
												case Type.LONG:
													visitInsn(Opcodes.LCONST_0);
													break;
												case Type.DOUBLE:
													visitInsn(Opcodes.DCONST_0);
													break;
												case Type.FLOAT:
													visitInsn(Opcodes.FCONST_0);
													break;
												default:
													throw new UnsupportedOperationException();
												}
											}
											iconst(rf.key);
											visitMethodInsn(Opcodes.INVOKESTATIC, "net/jonbell/crij/instrument/RollForwardTransformer", "moveForwardTo", "(I)V", false);
										}
										super.visitJumpInsn(Opcodes.GOTO, rf.l);
										super.visitLabel(ok);
										fn.accept(mv);
										rf.l.info = fn;
										super.visitInsn(Opcodes.NOP);
									}
								}
								@Override
								public void visitLabel(Label label) {
									super.visitLabel(label);
									if(label.info instanceof FrameNode)
									{
										getCurrentFrameNode().accept(this);
									}
								}

								
							};
							RollForwardMN mn = new RollForwardMN(mv, access, name, desc, signature, exceptions, rfs);
							AnalyzerAdapter preAnalyzer = new AnalyzerAdapter(className, access, name, desc, mn);
							mn.setAnalyzer(preAnalyzer);
							return preAnalyzer;
							
						}
						return mv;
					};
				};
				cr.accept(cv, ClassReader.EXPAND_FRAMES);
				byte[] ret = cw.toByteArray();
				if (Premain.DEBUG) {
					File debugDir = new File("debug");
					if (!debugDir.exists())
						debugDir.mkdir();
					File f = new File("debug/rf." + className.replace("/", ".") + ".class");
					FileOutputStream fos = new FileOutputStream(f);
					fos.write(ret);
					fos.close();
				}
				return ret;
			} catch (Throwable t) {
				t.printStackTrace();
			}
			return null;
		}
	}

	static class RollForwardMN extends MethodNode {
		final MethodVisitor cmv;
		private final LinkedList<RollForward> rfs;
		RollForwardMN(MethodVisitor cmv, int access, String name, String desc, String signature, String[] exceptions, LinkedList<RollForward> rfs)
		{
			super(Opcodes.ASM5, access, name, desc, signature, exceptions);
			this.cmv = cmv;
			this.rfs =rfs;
		}
		AnalyzerAdapter an;
		public void setAnalyzer(AnalyzerAdapter an) {
			this.an = an;
		}
		Object[] removeLongsDoubleTopVal(List<Object> in) {
			ArrayList<Object> ret = new ArrayList<Object>();
			boolean lastWas2Word = false;
			for (Object n : in) {
				if (n == Opcodes.TOP && lastWas2Word) {
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

		FrameNode getCurrentFrameNode() {
			if (an.locals == null || an.stack == null)
				throw new IllegalArgumentException();
			Object[] locals = removeLongsDoubleTopVal(an.locals);
			Object[] stack = removeLongsDoubleTopVal(an.stack);
			FrameNode ret = new FrameNode(Opcodes.F_NEW, locals.length, locals, stack.length, stack);
			return ret;
		}
	
		@Override
		protected LabelNode getLabelNode(Label l) {
			if (!(l.info instanceof LabelNode)) {
	            l.info = new LabelNode(l);
	        }
	        return (LabelNode) l.info;
	       }
		@Override
		public void visitLabel(Label label) {
			super.visitLabel(label);
			for (RollForward rf : rfs) {
				if (((OffsetPreservingLabel) label).getOriginalPosition() == rf.off) {
					rf.fn = getCurrentFrameNode();
//					System.out.println("Found " + rf.off + ", fn " + rf.fn.stack);
					rf.l = new Label();
					super.visitLabel(rf.l);
				}
			}
		}
		@Override
		public void visitEnd() {
			super.visitEnd();
			this.accept(cmv);
		}
	}
	public static void main(String[] args) throws Throwable {
		Premain.DEBUG = true;
		File clazz = new File("target/test-classes/Continuation.class");
		final ClassReader cr1 = new ClassReader(new FileInputStream(clazz));
		PrintWriter pw = new PrintWriter(new FileWriter("z.txt"));
		Transformer t = new Transformer();
		// transformForRollForward(Continuation.class, "main",
		// "([Ljava/lang/String;)V", "()Ljava/lang/Object;", true, null, 12, 0);
		// transformForRollForward(Continuation.class, "foo",
		// "()V","()Ljava/lang/String;",false, null, 5,1);
		// transformForRollForward(Continuation.class, "foo",
		// "()V","(Ljava/lang/Object;Ljava/lang/Object;)V",true, null, 8, 0);
//		transformForRollForward(Continuation.class, "foo", "()V", "()V", false, null, 13, 0, 0, 1);
//		byte[] r = t.transform(null, "Continuation", Continuation.class, null, cr1.b);
//		ClassReader cr = new ClassReader(r);
//		CheckClassAdapter ca = new CheckClassAdapter(new ClassWriter(0));
//		cr.accept(ca, 0);
	}
}
