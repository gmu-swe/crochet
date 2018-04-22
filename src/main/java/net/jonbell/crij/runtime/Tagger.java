package net.jonbell.crij.runtime;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.jonbell.crij.instrument.Premain;
import net.jonbell.crij.runtime.lock.BigFatLockPolicy;
import net.jonbell.crij.runtime.lock.DummyLockPolicy;
import net.jonbell.crij.runtime.lock.FineGrainedLockPolicy;
import net.jonbell.crij.runtime.lock.LockPolicy;
import net.jonbell.crij.runtime.lock.VersionCASLockPolicy;

public class Tagger {
	/**
	 * Flag set by the JVMTI agent to indicate that it was successfully loaded
	 */
	public static int engaged = 0;

	public static Object jvmtiLock = new Object();
	
	public static LockPolicy lockPolicy;
	
	private static native Object _getTag(Object obj);

	private static native void _setTag(Object obj, Object t);

	private static native void _checkpointStack(int v, boolean ignoreCurrentThread);
	private static native void _rollbackStack(int v, boolean ignoreCurrentThread);
	
	public static native long getObjectSize(Object o);
	
	public static native void captureStack();
	
	public static void init()
	{
//		lockPolicy = new BigFatLockPolicy();
//		lockPolicy = new VersionCASLockPolicy();
//		lockPolicy = new FineGrainedLockPolicy();
		lockPolicy = new DummyLockPolicy();
	}

	
	private static native Object _getInitializedClasses();
	public static Class[] getInitializedClasses()
	{
		if(engaged == 0)
			throw new IllegalStateException();
		Object ret = _getInitializedClasses();
		return (Class[]) ret;
	}
	private static native Object _getUnInitializedClasses();
	public static Class[] getUnInitializedClasses()
	{
		if(engaged == 0)
			throw new IllegalStateException();
		Object ret = _getUnInitializedClasses();
		return (Class[]) ret;
	}
	public static void checkpointStackRoots(int v){
		checkpointStackRoots(v, true);
	}
	
	public static void rollbackStackRoots(int v){
		rollbackStackRoots(v, true);
	}

	public static void checkpointStackRoots(int v, boolean ignoreCurrentThread){
		if(engaged == 0)
			throw new IllegalStateException("Attempting to use JVMTI features, but native agent not loaded");
		synchronized(jvmtiLock)
		{
			Premain.checkpointLatch.writeLock().lock();
			_checkpointStack(v, ignoreCurrentThread);
			jvmtiLock.notify();
//			System.out.println("Done?");
			try {
				jvmtiLock.wait();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			Premain.checkpointLatch.writeLock().unlock();
		}
		
	}
	
	public static void rollbackStackRoots(int v, boolean ignoreCurrentThread){
		if(engaged == 0)
			throw new IllegalStateException("Attempting to use JVMTI features, but native agent not loaded");
		synchronized(jvmtiLock)
		{
			_rollbackStack(v, ignoreCurrentThread);
			jvmtiLock.notify();
		}
		while(true)
		{
			//JVMTI will bail this out of the busy waiting.
		}
	}
	
	/**
	 * Get the tag currently assigned to an object. If the reference is to an
	 * object, and its class has been instrumented, then we do this entirely
	 * within the JVM. If the reference is to an array, or an instance of a
	 * class that was NOT instrumented, then we use JNI/JVMTI and make a native
	 * call.
	 * 
	 * @param obj
	 * @return
	 */
	public static Object getTag(Object obj) {
		if (engaged == 0)
			throw new IllegalStateException("Attempting to use JVMTI features, but native agent not loaded");
		if (obj == null)
			return null;
		return _getTag(obj);
	}

	/**
	 * Set the tag on an object. If the reference is to an object, and its class
	 * has been instrumented, then we do this entirely within the JVM. If the
	 * reference is to an array, or an instance of a class that was NOT
	 * instrumented, then we use JNI/JVMTI and make a native call.
	 * 
	 * @param obj
	 * @param t
	 */
	public static void setTag(Object obj, Object t) {
		if (engaged == 0)
			throw new IllegalStateException("Attempting to use JVMTI features, but native agent not loaded");
		if (obj == null)
			return;
		_setTag(obj, t);
	}

}
