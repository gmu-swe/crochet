package net.jonbell.crij.instrument;

import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.jonbell.crij.runtime.CRIJSFHelper;
import net.jonbell.crij.runtime.CRIJSlowCheckpoint;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;

public class Premain {
    public static boolean DEBUG = false;
    public static final boolean WITH_ARRAY_PER_EL = false;
    public static final boolean WITH_STATIC_FIELDS = true;
    public static Instrumentation inst;
    public static boolean inited = false;
    public static boolean WITH_ROLLBACK_FINALLY = false;
    
    /**
     * Valid arguments:
     * 
     * checkpointAt=fully/qualified/class/name.methodName(andDesc)V#Num where Num is the bytecode offset just AFTER
     * where you want the checkpoint to happen, can be specified more than once
     * 
     * rollbackAt=fully/qualified/class/name.methodName(andDesc)V#Num where Num is the bytecode offset just AFTER
     * where you want the checkpoint to happen, can be specified more than once
     */
    public static void premain(String args, Instrumentation inst) {
        checkpointLatch= new ReentrantReadWriteLock();

    	HashSet<String> checkpointAts = new HashSet<String>();
    	HashSet<String> rollbackAts = new HashSet<String>();
    	if (args != null) {
			String[] aaa = args.split(",");
			for (String s : aaa) {
				String[] a = s.split("=");
				if(a[0].equals("checkpointAt"))
				{
					checkpointAts.add(a[1]);
				}
				else if(a[0].equals("rollbackAt"))
				{
					rollbackAts.add(a[1]);
				}
				else if(a[0].equals("withStackRollback"))
					WITH_ROLLBACK_FINALLY = true;
			}
    	}
    	CRClassFileTransformer.RUNTIME_INST = true;
		CheckpointRollbackAgent.VERBOSE = false;
		inst.addTransformer(new CRClassFileTransformer(checkpointAts, rollbackAts));
        inst.addTransformer(new RollForwardTransformer.Transformer(), true);
        Premain.inst = inst;

        inited = true;

        initClass(ProtectionDomain.class);
        for(Class<?> c : inst.getAllLoadedClasses())
        {
        	if(Instrumenter.isIgnoredClass(c) || c.isInterface() || c.isArray())
        		continue;
        	initClass(c);
        }
    }
	public static ReadWriteLock checkpointLatch;
//	public static PrintStream sysout;
	
	public static boolean haveCheckpointed = false;
	public static HashSet<Class> notYetInited = new HashSet<>();
	public static void initClass(Class<?> c) {
		if (!inited)
			return;
		if (Instrumenter.isIgnoredClass(c) || c.isArray())
			return;
		try {
//			if(c.isInterface())
//			{
//				if (!CRIJSFHelper.class.isAssignableFrom(c)) {
//					Method m = c.getDeclaredMethod("$$CRIJGetSFHelper", new Class[0]);
//					m.setAccessible(true);
//					m.invoke(null);
////					System.out.println(c);
//					CheckpointRollbackAgent.generateSFHolderClass(c);
//				}
//			}
//			else
		if (!CRIJSlowCheckpoint.class.isAssignableFrom(c) && !Instrumenter.isIgnoredClassWithDummyMethods(c)
					&& !Instrumenter.isIgnoredClassButStillPropogate(c.getName().replace('.', '/'))) {
			//NOTE TO FUTURE SELF:
			//in principle, we shouldn't need to force this initialization to happen up front
			//but, it seems there are some weird initialization races if we dont
				if (haveCheckpointed) {
					Method m = c.getDeclaredMethod("$$crijInit");
					m.setAccessible(true);
					m.invoke(null);
				} else if(!c.isInterface())
					notYetInited.add(c);
				if (!CRIJSFHelper.class.isAssignableFrom(c) && c.sfHelper == null) {
					try{
						checkpointLatch.readLock().lock();
					Method m = c.getDeclaredMethod("$$CRIJGetSFHelper", new Class[0]);
					m.setAccessible(true);
					m.invoke(null);
//					System.out.println(c);
//					CheckpointRollbackAgent.generateSFHolderClass(c);
					}
					catch(Throwable t)
					{
//						System.err.println("On  " + c.getName());
//						t.printStackTrace();
					}
					finally{
						checkpointLatch.readLock().unlock();
					}
				}
			}
		} catch(Throwable t)
		{
//			t.printStackTrace();
		}
		if(c.isInterface())
			return;
		long versionOffset = c.versionOffset;
		if (versionOffset == 0) {
			Field versionField;
			try {
				versionField = c.getField(CheckpointRollbackFieldCV.VERSION_FIELD_NAME);
			} catch (NoSuchFieldException | SecurityException e) {
				throw new Error(c.toString(), e);
			}
			versionOffset = CheckpointRollbackAgent.u.objectFieldOffset(versionField);
			c.versionOffset = versionOffset;
		}
		long lockOffset = c.lockOffset;
		if (lockOffset == 0) {
			Field lockField;
			try {
				lockField = c.getField("$$crijLock");

			} catch (NoSuchFieldException | SecurityException e) {
				throw new Error(c.toString(), e);
			}
			lockOffset = CheckpointRollbackAgent.u.objectFieldOffset(lockField);
			c.lockOffset = lockOffset;
		}

		if (!Instrumenter.isIgnoredClassWithDummyMethods(c)) {
			try {
				if (!Modifier.isAbstract(c.getModifiers())) {
					Object inst = CheckpointRollbackAgent.u.allocateInstance(c);
					c.preallocInst = inst;
				}
			} catch (InstantiationException e) {
//				e.printStackTrace();
			}
			
			try {
				c.oldOffset = CheckpointRollbackAgent.u.objectFieldOffset(c.getField("$$crijOld"));
			} catch (NoSuchFieldException | SecurityException e) {
				e.printStackTrace();
			}

		}
	}
}
