package net.jonbell.crij.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.function.Supplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import net.jonbell.crij.instrument.CheckpointRollbackStaticFieldStubClassGenerator;
import net.jonbell.crij.instrument.CheckpointRollbackStubClassGenerator;
import net.jonbell.crij.instrument.Instrumenter;
import net.jonbell.crij.instrument.PlaceholderHelperClassWriter;
import net.jonbell.crij.instrument.Premain;
import net.jonbell.crij.runtime.lock.DummyLockPolicy;
import net.jonbell.crij.runtime.lock.VersionCASLockPolicy;
import sun.misc.Unsafe;

public class CheckpointRollbackAgent {
	public static boolean VERBOSE = true;
	public static boolean STOP_ON_ERROR = true;

	private static int VERSION;

	public static synchronized int getNewVersion() {
		VERSION++;
		return VERSION;
	}
	
	public static synchronized int getNewVersionForRollback() {
		VERSION++;
		if(VERSION % 2 == 1)
			VERSION++;
		return VERSION;
	}
	
	public static synchronized int getNewVersionForCheckpoint() {
		VERSION++;
		if(VERSION % 2 == 0)
			VERSION++;
		return VERSION;
	}

	public static int getCurrentVersion() {
		return VERSION;
	}

	public static int checkpointAllRoots() {
		int v = getNewVersionForCheckpoint();
		Tagger.checkpointStackRoots(v);
		return v;
	}
	public static int checkpointHeapRoots(){
		int v = getNewVersionForCheckpoint();
		StaticFieldWalker.traverseStaticFields(true, v);
		return v;
	}
	public static int checkpointFrame(){
		int v = getNewVersionForCheckpoint();
		StaticFieldWalker.traverseStaticFields(true, v);
		return v;
	}

	public static int checkpointFrameNoSFieldsNoFields(){
		return -1;
	}
	public static int checkpointFrameNoSFields(){
		int v = getNewVersionForCheckpoint();
		return v;
	}
	
	public static int rollbackFrame(){
		int v = getNewVersionForRollback();
		StaticFieldWalker.traverseStaticFields(true, v);
		throw new RollbackException(v);
	}
	
	public static int rollbackAllRoots() {
		int v = getNewVersionForRollback();
		Tagger.rollbackStackRoots(v);
		return v;
	}
	public static int rollbackHeapRoots(){
		int v = getNewVersionForRollback();
		StaticFieldWalker.traverseStaticFields(false, v);
		return v;
	}

	public static int checkpointCollectedRoots() {
		int v = getNewVersionForCheckpoint();
		RootCollector.checkpointCollectedRoots(v);
		return v;
	}

	public static int rollbackCollectedRoots() {
		int v = getNewVersionForRollback();
		RootCollector.rollbackCollectedRoots(v);
		return v;
	}

	public static int checkpointAllRoots(Object addl) {
		int v = getNewVersionForCheckpoint();
		// StaticFieldWalker.traverseStaticFields(true, v);
		Tagger.checkpointStackRoots(v);
		((CRIJInstrumented) addl).$$crijCheckpoint(v);
		return v;
	}

	public static int rollbackAllRoots(Object addl) {
		int v = getNewVersionForRollback();
		Tagger.rollbackStackRoots(v);
		// StaticFieldWalker.traverseStaticFields(false, v);
		((CRIJInstrumented) addl).$$crijRollback(v);
		return v;
	}

	public static int checkpointAllRoots(boolean ignoreCurrent) {
		int v = getNewVersionForCheckpoint();
		Tagger.checkpointStackRoots(v, ignoreCurrent);
		return v;
	}

	public static int rollbackAllRoots(boolean ignoreCurrent) {
		int v = getNewVersionForRollback();
		Tagger.rollbackStackRoots(v, ignoreCurrent);
		return v;
	}

	public static int COW_OFFSET = 0;
	public static int SLOW_ROLLBACK_OFFSET = 1;
	public static int FAST_OFFSET = 2;
	public static int SLOW_CHECKPOINT_OFFSET = 3;
	public static int SLOW_PROPAGATE_OFFSET = 4;
	public static int UNDEFINED_OFFSET = 5;
	public static int EAGER_OFFSET = 5;

	public static int BINARY_FAST_OFFSET = 0;
	public static int BINARY_SLOW_OFFSET = 1;

	public enum RollbackState {
		/*
		 * Unfortunately, you will also now need to manually create
		 * crijGetClassXXX methods in CRIJSFHelper.java for each new state that
		 * you add here
		 */
		// CoW (CRIJCoW.class, "onReadCoW", "onWriteCoW", "onCheckpointCoW",
		// "onRollbackCoW", UNDEFINED_OFFSET,
		// FAST_OFFSET,SLOW_CHECKPOINT_OFFSET,SLOW_ROLLBACK_OFFSET),
		// SlowRollback (CRIJSlowRollback.class, "onReadSlowRollback",
		// "onWriteSlowRollback", "onCheckpointSlowRollback",
		// "onRollbackSlowRollback", FAST_OFFSET, FAST_OFFSET,
		// SLOW_CHECKPOINT_OFFSET, UNDEFINED_OFFSET),
		// Fast (CRIJFast.class, "onReadFast", "onWriteFast",
		// "onCheckpointFast", "onRollbackFast", FAST_OFFSET, FAST_OFFSET,
		// SLOW_CHECKPOINT_OFFSET, SLOW_ROLLBACK_OFFSET),
		// SlowCheckpoint (CRIJSlowCheckpoint.class, "onReadSlowCheckpoint",
		// "onWriteSlowCheckpoint", "onCheckpointSlowCheckpoint",
		// "onRollbackSlowCheckpoint", COW_OFFSET, FAST_OFFSET,
		// UNDEFINED_OFFSET, SLOW_ROLLBACK_OFFSET),
		// SlowPropagate (CRIJFast.class, "onReadSlowPropagate",
		// "onWriteSlowPropagate", "onCheckpointSlowPropagate",
		// "onRollbackSlowPropagate", FAST_OFFSET, FAST_OFFSET,
		// SLOW_CHECKPOINT_OFFSET, UNDEFINED_OFFSET),
		// Undefined (CRIJFast.class, "crash", "crash", "crash", "crash",
		// UNDEFINED_OFFSET, UNDEFINED_OFFSET, UNDEFINED_OFFSET,
		// UNDEFINED_OFFSET),
		// Eager (CRIJFast.class, "onReadEager", "onWriteEager",
		// "onCheckpointEager", "onRollbackEager", UNDEFINED_OFFSET,
		// UNDEFINED_OFFSET, UNDEFINED_OFFSET, UNDEFINED_OFFSET);
		Fast(CRIJFast.class, "fastOnRead", "fastOnWrite", "fastOnCheckpoint", "fastOnRollback", FAST_OFFSET, FAST_OFFSET, BINARY_SLOW_OFFSET, BINARY_SLOW_OFFSET), Slow(CRIJSlowCheckpoint.class, "slowOnRead", "slowOnWrite", "slowOnCheckpoint", "slowOnRollback", FAST_OFFSET,
				FAST_OFFSET, FAST_OFFSET, FAST_OFFSET), Eager(CRIJFast.class, "eagerOnRead", "eagerOnWrite", "eagerOnCheckpoint", "eagerOnRollback", UNDEFINED_OFFSET, UNDEFINED_OFFSET, UNDEFINED_OFFSET, UNDEFINED_OFFSET);

		public final Class<?> iface;
		public final String getterCalledMethod;
		public final String setterCalledMethod;
		public final String checkpointCalledMethod;
		public final String rollbackCalledMethod;
		public final int onRead;
		public final int onWrite;
		public final int onCheckpoint;
		public final int onRollback;

		public static final RollbackState[] VALUES = RollbackState.values();

		RollbackState(Class<?> iface, String getterCalledMethod, String setterCalledMethod, String checkpointCalledMethod, String rollbackCalledMethod, int onRead, int onWrite, int onCheckpoint, int onRollback) {
			this.iface = iface;
			this.getterCalledMethod = getterCalledMethod;
			this.setterCalledMethod = setterCalledMethod;
			this.checkpointCalledMethod = checkpointCalledMethod;
			this.rollbackCalledMethod = rollbackCalledMethod;
			this.onRead = onRead;
			this.onRollback = onRollback;
			this.onWrite = onWrite;
			this.onCheckpoint = onCheckpoint;
		}
	}

	public static Unsafe u;
	static {
		try {
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			u = (Unsafe) f.get(null);

		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	static byte[] templateClass;

	static byte[] getTemplateClass() {
		if (templateClass == null) {
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			CheckpointRollbackStubClassGenerator g = new CheckpointRollbackStubClassGenerator(cw, false);
			g.generate();
			templateClass = cw.toByteArray();
			if (Premain.DEBUG) {
				File debugDir = new File("debug");
				if (!debugDir.exists())
					debugDir.mkdir();
				File f = new File("debug/default$$CRIJHolder" + ".class");
				try {
					FileOutputStream fos = new FileOutputStream(f);
					fos.write(templateClass);
					fos.close();
				} catch (Throwable t) {

				}
			}
		}
		return templateClass;
	}
	@SuppressWarnings("unchecked")
	public static CRIJSFHelper generateSFHolderClass(Class in) {
		try {
			// System.out.println(in);
			synchronized (in) {
				if (in.sfHelperClass != null) {
					if (in.sfHelper == null)
						in.sfHelper = (CRIJSFHelper) in.sfHelperClass.newInstance();
					return in.sfHelper;
				}
			}
//			System.out.println(in);
			PlaceholderHelperClassWriter cw = new PlaceholderHelperClassWriter(ClassWriter.COMPUTE_MAXS);
			Field[] fields = in.getDeclaredFields();
			LinkedList<Field> sFields = new LinkedList<>();
			for (Field f : fields) {
				if (Modifier.isStatic(f.getModifiers()) && !f.getName().startsWith("$$crij"))
					sFields.add(f);
			}
			String origName = in.getName().replace('.', '/');
			// System.out.println("SFH: " + in);
			// System.out.println(Arrays.toString(in.getInterfaces()));
			// System.out.println(in.getSuperclass());
			CheckpointRollbackStaticFieldStubClassGenerator g = new CheckpointRollbackStaticFieldStubClassGenerator(cw, origName, in.getSuperclass(), in.getInterfaces(), sFields);
			g.generate();
			byte[] clazz = cw.toByteArray();
			// Need to find the constant pool entry of the parent class to
			// overwrite it with a patch.
			ClassReader cr = new ClassReader(clazz);

			Object[] patches = new Object[cr.getItemCount()];
			patches[cw.getPlace() + 1] = in;

			// LinkedList<Integer> ifacePlaces = cw.getInterfacePlaces();

			// }
			if (Premain.DEBUG) {
				File debugDir = new File("debug");
				if (!debugDir.exists())
					debugDir.mkdir();
				File f = new File("debug/" + in.getName().replace('/', '.') + "$$crijSFHelper" + ".class");
				try {
					FileOutputStream fos = new FileOutputStream(f);
					fos.write(clazz);
					fos.close();
				} catch (Throwable t) {

				}
			}
			synchronized (in) {
				// System.out.println(in);
				// System.out.println(in.sfHelperClass);
				if (in.sfHelperClass != null) {
					if (in.sfHelper == null)
						in.sfHelper = (CRIJSFHelper) in.sfHelperClass.newInstance();
					return in.sfHelper;
				}
				try {
					Class<?> c = u.defineAnonymousClass(in, clazz, patches);
					c.originalClass = in;
					in.sfHelperClass = c;
					c.oldField = c.getField("$$crijOld");
					Premain.initClass(c);
					if (in.sfHelper == null)
						in.sfHelper = (CRIJSFHelper) in.sfHelperClass.newInstance();
				} catch (LinkageError e) {
					if (in.sfHelperClass != null)
						return in.sfHelper;
					throw e;
				}
			}
			return in.sfHelper;
		} catch (Throwable t) {
//			t.printStackTrace();
			throw new IllegalStateException(t);
		}
	}

	public static Class generateClass(Class in, int enumEntry) {
		String oldName = in.getName().replace('.', '/');
		Object[] patches = new Object[42];
		RollbackState s = RollbackState.VALUES[enumEntry];
		patches[1] = oldName + "$$CRIJ" + s.name();
		patches[4] = in;
		patches[6] = s.iface;
		patches[9] = "$$crijGetClass" + RollbackState.VALUES[s.onWrite].name();
		patches[15] = s.setterCalledMethod;
//		patches[20] = "$$crijGetClass" + RollbackState.VALUES[s.onRead].name();
//		patches[23] = s.getterCalledMethod;
		patches[21] = "$$crijGetClass" + RollbackState.VALUES[s.onCheckpoint].name();
		patches[24] = s.checkpointCalledMethod;
		patches[29] = "$$crijGetClass" + RollbackState.VALUES[s.onRollback].name();
		patches[32] = s.rollbackCalledMethod;
		Class ret = u.defineAnonymousClass(in, getTemplateClass(), patches);
		ret.originalClass = (in.originalClass != null ? in.originalClass : in);
		try {
			ret.oldField = ret.getField("$$crijOld");
		} catch (NoSuchFieldException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		}
		Premain.initClass(ret);
		return ret;
	}

	public static void changeClass(Object in, Class from, Class to) {
		// if(in instanceof Method &&
		// otherClass.getName().startsWith("java.lang.reflect.AccessibleObject"))
		// new Exception().printStackTrace();
		// throw new IllegalStateException();
		// System.out.println(in.getClass() + ", "+ otherClass.getName());
		// if (Premain.DEBUG &&
		// CRIJSlowCheckpoint.class.isAssignableFrom(otherClass))
		// {
		// long oldOffset = getOldOffset(in.getClass());
		// if (u.getObject(in, oldOffset) != null)
		// throw new Error();
		// }
		// Object inst = u.allocateInstance(otherClass);

		int klassFrom = u.getInt(from.preallocInst, 8L);
		int klassTo = u.getInt(to.preallocInst, 8L);
		u.compareAndSwapInt(in, 8L, klassFrom, klassTo);
	}

	public static <C> C allocateInstance(Class<C> c) throws InstantiationException {
		return (C) u.allocateInstance(c);
	}

	private static void describe(Object obj, String pref) {
		if (!VERBOSE)
			return;

		long oldOffset = getOldOffset(obj.getClass());
		Object old = u.getObject(obj, oldOffset);
		String methodName = new Exception().getStackTrace()[1].getMethodName();
		System.out.print(pref + obj.hashCode() + ":" + obj.getClass() + ":" + methodName);
		if (old != null)
			System.out.print("($old " + old.getClass() + ")");

		System.out.println();
	}

	public static long getOldOffset(Class c) {
		if (c.oldOffset == 0)
			throw new IllegalStateException();
		return c.oldOffset;
	}

	/**
	 * TODO: this shouldn't really be public, but we need it in our test
	 * cases...
	 * 
	 * @param obj
	 * @return
	 */
	public static Object getOld(Object obj) {
		long oldOffset = CheckpointRollbackAgent.getOldOffset(obj.getClass());
		return u.getObject(obj, oldOffset);
	}

	static void ignoreVersion() {
		Thread t = Thread.currentThread();
		Object o = Tagger.getTag(t);
		if (o instanceof Integer)
			Tagger.setTag(t, ((Integer) o).intValue() + 1);
		else
			Tagger.setTag(t, 1);
	}

	static void stopIgnoreVersion() {
		Thread t = Thread.currentThread();
		Object o = Tagger.getTag(t);
		if (((Integer) o).intValue() == 1)
			Tagger.setTag(t, null);
		else
			Tagger.setTag(t, ((Integer) o).intValue() - 1);
	}

	static boolean isIgnoreVersion() {
		Thread t = Thread.currentThread();
		Object o = Tagger.getTag(t);
		return o != null;
	}

	public static void crash(CRIJInstrumented obj, Class unneeded) {
		throw new UnsupportedOperationException();
	}

	public static void resetStats() {

	}

	public static void hackRefQueue(CRIJInstrumented queue, Object ret) {
		if (Tagger.lockPolicy == null)
			return;
		if (ret instanceof Reference) {
			Object a = ((Reference) ret).get();
			if (!(a instanceof CRIJInstrumented))
				return;
			int v = Tagger.lockPolicy.lock((CRIJInstrumented) a);
			if (v == VersionCASLockPolicy.INVAL)
				return;
			try {
				// TODO: Make sure that queue is not locked
				// Otherwise reading the version does not work
				if (queue.$$CRIJgetVersion() > 0 && v < queue.$$CRIJgetVersion()) {
					if (queue.$$crijIsRollbackState())
						((CRIJInstrumented) a).$$crijRollback(queue.$$CRIJgetVersion());
					else
						((CRIJInstrumented) a).$$crijCheckpoint(queue.$$CRIJgetVersion());
				}
			} finally {
				Tagger.lockPolicy.unlock((CRIJInstrumented) a, v);
			}
		}

	}

	public static Object error = null;

	public static void raiseException(Object obj, int propagatingVersion, int objectVersion) {
		Error e = new Error("OBJ at " + objectVersion + " but propagating " + propagatingVersion);
		if (STOP_ON_ERROR) {
			e.printStackTrace();
			// Set dummy policy to allow debugger to connect without deadlocking
			Tagger.lockPolicy = new DummyLockPolicy();
			// Loop forever, add breakpoint inside loop when connecting debugger
			while (true)
				error = e;
		}
		throw e;
	}

	public static int nCheckpointsFast;
	public static int nCheckpointsSlow;
	public static int nReadsSlow;
	public static int nWritesSlow;
	public static int nObjectCopies;
	public static int nArrayAllocations;
	public static int nArrayOldStores;
	public static int nArrayCopies;
	public static int nArrayRollbacks;
	public static int nArrayPropagations;
	public static int nSlowArrayPropagations;
	public static int nObjectRollbacks;
	public static int nRollbacksSlow;
	public static long arrayCopyBytes;
	public static long objectCopyBytes;
	public static long objectsTraversedBytes;
	public static final boolean TRACK_SIZES_BYTES = false;
	static {
		if(System.getProperty("stats") != null)
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				printStats();
			}
		}));
	}

	public static void fastOnRead(CRIJInstrumented obj, Class<?> dumb) {

	}

	public static void fastOnWrite(CRIJInstrumented obj, Class<?> dumb) {

	}

	public static void fastOnCheckpoint(CRIJInstrumented obj, Class<?> dumb, int v) {
		Class c = obj.getClass();
		long versionOffset = c.versionOffset;
//		if (c.versionOffset == 0)
//			throw new IllegalStateException();
		int curV = u.getInt(obj, versionOffset);

		 if (curV >= v)
			 return;

		 if(TRACK_SIZES_BYTES)
		 {
			objectsTraversedBytes += Tagger.getObjectSize(obj);
		 }
		int realV = (curV < 0) ? curV * -1 : curV;
//		int realV = curV;
//		if (v == 0)
//			System.out.println("v == 0 checkpoint");
		// if(v > curV)
		checkVersion(obj, v, realV);

		if (Premain.DEBUG && v % 2 != 0)
		{
			// Checkpoint
//			long oldOffset = getOldOffset(obj.getClass());
//			CRIJInstrumented curOld = (CRIJInstrumented) u.getObject(obj, oldOffset);
//			u.compareAndSwapObject(obj, oldOffset, curOld, null);
		}

//		u.compareAndSwapInt(obj, versionOffset, curV, v);
		 u.compareAndSwapInt(obj, versionOffset, curV, v*-1);
		if (!CRIJSlowCheckpoint.class.isAssignableFrom(c))
			changeClass(obj, c, dumb);
		 u.compareAndSwapInt(obj, versionOffset, v*-1, v);

		nCheckpointsFast++;
	}

	public static void fastOnRollback(CRIJInstrumented obj, Class<?> dumb, int v) {
		fastOnCheckpoint(obj, dumb, v);
	}

	public static void eagerOnRead(CRIJInstrumented obj, Class<?> dumb) {

	}

	public static void eagerOnWrite(CRIJInstrumented obj, Class<?> dumb) {

	}

	public static void eagerOnCheckpoint(CRIJInstrumented obj, Class<?> dumb, int v) {
		Class c = obj.getClass();

		long versionOffset = c.versionOffset;
//		if (c.versionOffset == 0)
//			throw new IllegalStateException();
		int curV = u.getInt(obj, versionOffset);
		if (curV == v)
			return;

		// int realV = (curV < 0) ? curV * -1 : curV;
		int realV = curV;

//		if (v == 0)
//			System.out.println("v == 0 checkpoint");
		// if(v > curV)
		checkVersion(obj, v, realV);

		if (v % 2 != 0)
		{
			// Checkpoint
//			long oldOffset = getOldOffset(obj.getClass());
//			CRIJInstrumented curOld = (CRIJInstrumented) u.getObject(obj, oldOffset);
//			u.compareAndSwapObject(obj, oldOffset, curOld, null);
		}

		obj.$$CRIJpropagateCheckpoint(v);

		// u.compareAndSwapInt(obj, versionOffset, curV, v*-1);
		u.compareAndSwapInt(obj, versionOffset, curV, v);
		// u.compareAndSwapInt(obj, versionOffset, v*-1, v);
	}

	public static void eagerOnRollback(CRIJInstrumented obj, Class<?> dumb, int v) {
		Class c = obj.getClass();

		long versionOffset = c.versionOffset;
//		if (c.versionOffset == 0)
//			throw new IllegalStateException();
		int curV = u.getInt(obj, versionOffset);
		if (curV == v)
			return;

		// int realV = (curV < 0) ? curV * -1 : curV;
		int realV = curV;

//		if (v == 0)
//			System.out.println("v == 0 checkpoint");
		// if(v > curV)
		checkVersion(obj, v, realV);

		if (v % 2 != 0)
		{
			// Checkpoint
//			long oldOffset = getOldOffset(obj.getClass());
//			CRIJInstrumented curOld = (CRIJInstrumented) u.getObject(obj, oldOffset);
//			u.compareAndSwapObject(obj, oldOffset, curOld, null);
		}

		obj.$$CRIJpropagateRollback(v);

		// u.compareAndSwapInt(obj, versionOffset, curV,
		// v*-1);
		u.compareAndSwapInt(obj, versionOffset, curV, v);
		// u.compareAndSwapInt(obj, versionOffset, v*-1, v);
	}

	public static void slowOnRead(CRIJInstrumented obj, Class<?> dumb) {
		Class c = obj.getClass();
		long versionOffset = c.versionOffset;
//		if (c.versionOffset == 0)
//			throw new IllegalStateException();
		int curV = u.getIntVolatile(obj, versionOffset);

		// int realV = (curV < 0) ? curV * -1 : curV;
		int realV = curV;
		checkVersion(obj, realV, curV);
		if (curV % 2 == 0) {
			// Even - rollback
			try {
				long oldOffset = getOldOffset(obj.getClass());
				CRIJInstrumented curOld = (CRIJInstrumented) u.getObject(obj, oldOffset);
				if (curOld == null || u.getIntVolatile(curOld, versionOffset) >= realV) {
					// Some other thread already wrote back the old value
				} else {
					synchronized (curOld) {
						curOld = (CRIJInstrumented) u.getObject(obj, oldOffset);
						if (curOld.getClass() != dumb)
							throw new IllegalStateException();
						if (curOld != null && u.getInt(curOld, versionOffset) < realV) {
							if (CheckpointRollbackAgent.TRACK_SIZES_BYTES) {
								CheckpointRollbackAgent.objectCopyBytes += Tagger.getObjectSize(obj);
							}
							obj.$$CRIJcopyFieldsFrom(curOld);
							u.putInt(curOld, versionOffset, realV);
							 u.putObject(obj, oldOffset, null);
							nObjectRollbacks++;
						}
					}
				}
				// We don't care if the CAS succeeds or not.
				// If it doesn't, some other thread did the right copy
				obj.$$CRIJpropagateRollback(realV);
			}
			catch(Throwable t)
			{
				
			}
		} else {
			try {
				// Odd - checkpoint
				long oldOffset = getOldOffset(obj.getClass());
				CRIJInstrumented curOld = (CRIJInstrumented) u.getObject(obj, oldOffset);
				if (curOld != null && u.getIntVolatile(curOld, versionOffset) >= realV) {
					// Some other thread already set their old for the current
					// version, we're done
				} else {
					CRIJInstrumented old;
					try {
						old = (CRIJInstrumented) u.allocateInstance(dumb);
					} catch (InstantiationException e) {
						throw new Error(e);
					}
					if (old.getClass() != dumb)
						throw new IllegalStateException();
					if (CheckpointRollbackAgent.TRACK_SIZES_BYTES) {
						CheckpointRollbackAgent.objectCopyBytes += Tagger.getObjectSize(obj);
					}
					obj.$$CRIJcopyFieldsTo(old);
					u.putIntVolatile(old, versionOffset, realV);

					nObjectCopies++;

					u.compareAndSwapObject(obj, oldOffset, curOld, old);
				}

				// We don't care if the CAS succeeds or not.
				// If it doesn't, some other thread did the right copy
				obj.$$CRIJpropagateCheckpoint(realV);
			} catch (Throwable t) {

			}
		}

		if (CRIJSlowCheckpoint.class.isAssignableFrom(c))
			changeClass(obj, c, dumb);

		nReadsSlow++;
	}

	public static void slowOnWrite(CRIJInstrumented obj, Class<?> dumb) {
		slowOnRead(obj, dumb);
		nWritesSlow++;
	}

	public static void slowOnCheckpoint(CRIJInstrumented obj, Class<?> dumb, int v) {
		Class c = obj.getClass();

		long versionOffset = c.versionOffset;
//		if (c.versionOffset == 0)
//			throw new IllegalStateException();
		int curV = u.getIntVolatile(obj, versionOffset);
		if(curV >= v)
			return;
		if (v % 2 == 0) {
			checkVersion(obj, v, curV);
			// Even - rollback
			u.compareAndSwapInt(obj, versionOffset, curV, v);
			if (TRACK_SIZES_BYTES) {
				objectsTraversedBytes += Tagger.getObjectSize(obj);
			}
			nRollbacksSlow++;
		} else {
			checkVersion(obj, v, curV);
			// Odd - checkpoint
//			long oldOffset = getOldOffset(obj.getClass());
//			CRIJInstrumented curOld = (CRIJInstrumented) u.getObject(obj, oldOffset);
//			u.compareAndSwapObject(obj, oldOffset, curOld, null);

			if (curV != 0 && curV % 2 == 0)
			{
				// Checkpoint on rollback, perform rollback before the checkpoint
				long oldOffset = getOldOffset(obj.getClass());
				CRIJInstrumented curOld = (CRIJInstrumented) u.getObject(obj, oldOffset);
				if (curOld == null) // || u.getIntVolatile(curOld,versionOffset) >= v)
				{
					// Some other thread already wrote back the old value
				} else {
					synchronized (curOld) {
						curOld = (CRIJInstrumented) u.getObject(obj, oldOffset);
						if(curOld.getClass() != dumb)
							throw new IllegalStateException();
						if (curOld != null && u.getInt(curOld,versionOffset) < curV)
						{
							obj.$$CRIJcopyFieldsFrom(curOld);
							u.putInt(curOld, versionOffset, curV);
							// TODO optimization: Don't throw old away as we are checkpointing
							u.putObject(obj,oldOffset, null);
							nObjectRollbacks++;
						}
					}
					obj.$$CRIJpropagateRollback(curV);
				}
			}
			u.compareAndSwapInt(obj, versionOffset, curV, v);
			if (TRACK_SIZES_BYTES) {
				objectsTraversedBytes += Tagger.getObjectSize(obj);
			}
			nCheckpointsSlow++;
		}
	}

	private static void checkVersion(CRIJInstrumented obj, int v, int curV) {
		if (Premain.DEBUG && (curV > v || v == 0 || curV < 0))
			throw new Error("Want to go to " + v + " but at " + curV);
	}

	public static void slowOnRollback(CRIJInstrumented obj, Class<?> dumb, int v) {
		slowOnCheckpoint(obj, dumb, v);
	}

	public static void printStats() {
		System.out.println("NCheckpointsFromFast\tNCheckpointsFromSlow\tNReadWriteSlow\tNObjectCopy\tNArrayCopy\tNArrayPropagate\tObjectCopyBytes\tArrayCopyBytes\tSizeOfObjectsTraversed");
		System.out.println(nCheckpointsFast+"\t"+nCheckpointsSlow+"\t"+nReadsSlow+"\t"+nObjectCopies+"\t"+nArrayCopies+"\t"+nArrayPropagations+"\t"+objectCopyBytes+"\t"+arrayCopyBytes+"\t"+objectsTraversedBytes);
//		System.out.println("NCheckpoints from fasT:  " + nCheckpointsFast);
//		System.out.println("NCheckpoints from slow:  " + nCheckpointsSlow);
//		System.out.println("NReads from slow:        " + nReadsSlow);
//		System.out.println("NWrites from slow:       " + nWritesSlow);
//		System.out.println("NObjectCopies:           " + nObjectCopies);
//		System.out.println("NArrayCopies:            " + nArrayCopies);
//		System.out.println("NArrayRollbacks:         " + nArrayRollbacks);
//		System.out.println("NArrayPropagations :     " + nArrayPropagations);
//		System.out.println("NSlowArrayPropagations : " + nSlowArrayPropagations);
//		System.out.println("NObjectRollbacks :       " + nObjectRollbacks);
//		System.out.println("NRollbacksSlow :         " + nRollbacksSlow);

		// for (int i = 0 ; i < ArrayWrapper.counts.length ; i++)
		// System.out.println("Arrays of size " + i + " : " +
		// ArrayWrapper.counts[i].get());

		nCheckpointsFast = 0;
		nCheckpointsSlow = 0;
		nReadsSlow = 0;
		nWritesSlow = 0;
		nObjectCopies = 0;
		nArrayAllocations = 0;
		nArrayOldStores = 0;
		nArrayCopies = 0;
		nArrayRollbacks = 0;
		nArrayPropagations = 0;
		nSlowArrayPropagations = 0;
		nObjectRollbacks = 0;
		nRollbacksSlow = 0;
		objectCopyBytes = 0;
		arrayCopyBytes = 0;
		objectsTraversedBytes = 0;
	}

	static
	{
		RootCollector.enabled = true;
	}
}
