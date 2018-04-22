package net.jonbell.crij.instrument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashSet;

import net.jonbell.crij.instrument.asm.OffsetPreservingClassReader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.commons.SerialVersionUIDAdder;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

public class CRClassFileTransformer implements ClassFileTransformer {
	public static boolean RUNTIME_INST;
	
	private HashSet<String> checkpointLocations;
	private HashSet<String> rollbackLocations;
	
	public CRClassFileTransformer()
	{
		checkpointLocations = new HashSet<String>();
		rollbackLocations = new HashSet<String>();
	}
	
	public CRClassFileTransformer(HashSet<String> checkpointLocations, HashSet<String> rollbackLocations)
	{
		this.checkpointLocations = checkpointLocations;
		this.rollbackLocations = rollbackLocations;
	}
	
	private final class HackyClassWriter extends ClassWriter {

		private HackyClassWriter(ClassReader classReader, int flags) {
			super(classReader, flags);
		}

		private Class<?> getClass(String name) throws ClassNotFoundException {
			if(RUNTIME_INST)
				return null;
			try {
				return Class.forName(name.replace("/", "."));
			} catch (SecurityException e) {
				throw new ClassNotFoundException("Security exception when loading class");
			} catch (NoClassDefFoundError e) {
				throw new ClassNotFoundException();
			} catch (Throwable e) {
				throw new ClassNotFoundException();
			}
		}

		protected String getCommonSuperClass(String type1, String type2) {
			Class<?> c, d;
			try {
				c = getClass(type1);
				d = getClass(type2);
				if(c == null || d == null)
					return "java/lang/Object";
			} catch (ClassNotFoundException e) {
				return "java/lang/Object";
			} catch (ClassCircularityError e) {
				return "java/lang/Object";
			}
			if (c.isAssignableFrom(d)) {
				return type1;
			}
			if (d.isAssignableFrom(c)) {
				return type2;
			}
			if (c.isInterface() || d.isInterface()) {
				return "java/lang/Object";
			} else {
				do {
					c = c.getSuperclass();
				} while (!c.isAssignableFrom(d));
				//					System.out.println("Returning " + c.getName());
				return c.getName().replace('.', '/');
			}
		}
	}
	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
		try {
			ClassReader cr;
			if(checkpointLocations.size() > 0 || rollbackLocations.size() > 0)
				cr = new OffsetPreservingClassReader(classfileBuffer);
			else
				cr = new ClassReader(classfileBuffer);

			if (Instrumenter.isIgnoredClass(cr.getClassName())) {
				if (cr.getClassName().equals("sun/misc/Unsafe")) {
					ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					NativeWrappingCV cv = new NativeWrappingCV(cw);
					cr.accept(cv, 0);
					return cw.toByteArray();
				} else
					return classfileBuffer;
			}

			short version = cr.readShort(6);

			boolean generateFrames = false;
			if (version >= 100 || version <= 50)
				generateFrames = true;

			ClassNode cn = new ClassNode();
			cr.accept(cn, ClassReader.SKIP_CODE);
			if("_ANON_CLASS_NAME_".equals(cn.name))
				return classfileBuffer;
			if((cn.access & Opcodes.ACC_ANNOTATION) != 0)
				return classfileBuffer;
			if("net/jonbell/crij/runtime/CRIJSFHelper".equals(cn.superName))
				return null;
			for(Object  o : cn.interfaces)
				if("net/jonbell/crij/runtime/CRIJInstrumented".equals(o))
					return classfileBuffer;
			
			if (generateFrames) {
				// This class is old enough to not guarantee frames. Generate
				// new frames for analysis reasons, then make sure to not emit
				// ANY frames.
				ClassWriter cw = new HackyClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
				cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
					@Override
					public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
						return new JSRInlinerAdapter(super.visitMethod(access, name, desc, signature, exceptions), access, name, desc, signature, exceptions);
					}
				}, 0);
				if(checkpointLocations.size() > 0 || rollbackLocations.size() > 0)
					cr = new OffsetPreservingClassReader(cw.toByteArray());
				else
					cr = new ClassReader(cw.toByteArray());

			}

			className = cr.getClassName();
			ClassWriter cw = new HackyClassWriter(cr, ClassWriter.COMPUTE_MAXS);
			ClassVisitor cv = cw;
			HashSet<String> syntheticFields = new HashSet<String>();
			cv = new SerialVersionUIDAdder(new CheckpointRollbackFieldCV(cv, generateFrames, syntheticFields));
			if(checkpointLocations.size() > 0 || rollbackLocations.size() > 0)
			{
				cv = new CheckpointRollbackAddingCV(cv, checkpointLocations,rollbackLocations);
			}
			cr.accept(cv, ClassReader.EXPAND_FRAMES);

			byte[] ret = cw.toByteArray();
			if (Premain.DEBUG) {
				File debugDir = new File("debug");
				if (!debugDir.exists())
					debugDir.mkdir();
				File f = new File("debug/" + className.replace("/", ".") + ".class");
				FileOutputStream fos = new FileOutputStream(f);
				fos.write(ret);
				fos.close();

				debugDir = new File("debug-in");
				if (!debugDir.exists())
					debugDir.mkdir();
				f = new File("debug-in/" + className.replace("/", ".") + ".class");
				fos = new FileOutputStream(f);
				fos.write(classfileBuffer);
				fos.close();
				// ClassReader cr2 = new ClassReader(ret);
				// cr2.accept(new CheckClassAdapter(new ClassWriter(0)),
				// ClassReader.EXPAND_FRAMES);
			}
//			try{
//			ClassReader cr2 = new ClassReader(ret);
//			cr2.accept(new CheckClassAdapter(new ClassWriter(0)), ClassReader.EXPAND_FRAMES);
//			}
//			catch(ArrayIndexOutOfBoundsException ex)
//			{
//				System.err.println("Unable to verify " + className);
//				File debugDir = new File("debug-verify");
//				if (!debugDir.exists())
//					debugDir.mkdir();
//				File f = new File("debug-verify/" + className.replace("/", ".") + ".class");
//				FileOutputStream fos = new FileOutputStream(f);
//				fos.write(ret);
//				fos.close();
//				throw ex;
//			}
//			TraceClassVisitor tcv =null;
//			cr = new ClassReader(classfileBuffer);
//			PrintWriter pw = new PrintWriter(new File("last-class.txt"));
//			tcv = new TraceClassVisitor(pw);
//			cv = tcv;
//			syntheticFields = new HashSet<String>();
//			cv = new CheckpointRollbackFieldCV(cv, false, syntheticFields);
//			
//			cr.accept(cv, ClassReader.EXPAND_FRAMES);
			return ret;
		} catch (Throwable t) {
			t.printStackTrace();
			System.err.println("In " + className + ":");
			PrintWriter pw = null;
			TraceClassVisitor tcv = null;
			try {
				System.out.println("Saving ");
				File debugDir = new File("debug");
				if (!debugDir.exists())
					debugDir.mkdir();
				File f = new File("debug/" + className.replace("/", ".") + ".class");
				FileOutputStream fos = new FileOutputStream(f);
				fos.write(classfileBuffer);
				fos.close();

				ClassReader cr = new ClassReader(classfileBuffer);
				pw = new PrintWriter(new File("last-class.txt"));
				tcv = new TraceClassVisitor(pw);
				ClassVisitor cv = tcv;
				HashSet<String> syntheticFields = new HashSet<String>();
				cv = new CheckpointRollbackFieldCV(cv, false, syntheticFields);
				
				cr.accept(cv, ClassReader.EXPAND_FRAMES);
			} catch (Throwable tr) {
				// printed above
				tcv.p.print(pw);
				pw.flush();
			}

			return classfileBuffer;
		}
	}

}
