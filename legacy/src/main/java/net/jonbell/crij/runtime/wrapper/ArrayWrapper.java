package net.jonbell.crij.runtime.wrapper;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.jonbell.crij.instrument.Premain;
import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.Tagger;

public class ArrayWrapper {
	public Object old_array;
	private boolean inUse;
	boolean isPrim;
	private int version;
	private ArrayWrapper old;
	
	public int getVersion() {
		return version;
	}
	/**
	 * Internal-only use: array MUST be an empty new array of same type and size as backing array.
	 * @param array
	 * @param isPrim
	 */
	public ArrayWrapper(Object array, boolean isPrim)
	{
		this.old_array = array;
		this.isPrim = isPrim;
	}
	public ArrayWrapper(Object array)
	{
		this.old_array = Array.newInstance(array.getClass().getComponentType(), Array.getLength(array));
		this.isPrim = array.getClass().getComponentType().isPrimitive();
	}
	/**
	 * Concurrency: Can't synchronize on ar because someone else might be legitimately holding a lock. Bleh.
	 * @param ar
	 * @return
	 */
	
	private static Object l = new Object();

	public static ArrayWrapper getOrInitWrapper(Object ar)
	{
		ArrayWrapper ret;
		ret = (ArrayWrapper) Tagger.getTag(ar);

		if(ret == null)
		{
			ret = new ArrayWrapper(ar);
			synchronized (l)
			{
				ArrayWrapper ret2 = (ArrayWrapper) Tagger.getTag(ar);
				if (ret2 == null)
					Tagger.setTag(ar, ret);
				else
					ret = ret2;
			}
		}
		return ret;
	}
	void logArrayCopy()
	{
		CheckpointRollbackAgent.nArrayCopies++;
		if (CheckpointRollbackAgent.TRACK_SIZES_BYTES) {
			CheckpointRollbackAgent.arrayCopyBytes += Tagger.getObjectSize(old_array);
		}
	}

	public void $$CRIJcopyFieldsTo(Object to){
		logArrayCopy();
		System.arraycopy(old_array, 0, to, 0, Array.getLength(old_array));
	}
	public void $$CRIJcopyFieldsFrom(Object old){
		logArrayCopy();
		System.arraycopy(old, 0, old_array, 0, Array.getLength(old_array));
	}

	public void $$crijRollback(Object orig_array, int version){
		if(!this.inUse || this.version >= version)
			return;
		synchronized (this)
		{
			if(!this.inUse || this.version >= version)
				return;
			CheckpointRollbackAgent.nArrayRollbacks++;
			logArrayCopy();
			System.arraycopy(old_array, 0, orig_array, 0, Array.getLength(old_array));
			this.version = version;
			this.inUse = false;
		}
//		this.version = version;
//		if(old != null)
//		{
//			old.$$crijRollback(orig_array, version);
//			if(old.old == null)
//				this.old = null;
//		}
//		else
//			System.arraycopy(old_array, 0, orig_array, 0, Array.getLength(old_array));
	}
	public void $$crijCheckpoint(Object orig_array, int version){
		if(this.version >= version)
			return;
		synchronized (this)
		{
			if(this.version >= version)
				return;
			logArrayCopy();
			System.arraycopy(orig_array, 0, old_array, 0, Array.getLength(old_array));
			this.version = version;
			this.inUse = true;
		}
//		this.version = version;
//		if(inUse)
//		{
//			if(old == null)
//			{
//				old = new ArrayWrapper(old_array);
//			}
//			old.$$crijCheckpoint(orig_array, version);
//		}
//		else
//		{
//			inUse = true;
//			CheckpointRollbackAgent.nArrayCopies++;
//			System.arraycopy(orig_array, 0, old_array, 0, Array.getLength(old_array));
//		}
	}

	public static void propagateCheckpoint(Object array, int version) {
		if (array == null)
			return;
		ArrayWrapper w = ArrayWrapper.getOrInitWrapper(array);
		if(w.version >= version)
			return;

		w.$$crijCheckpoint(array, version);

		if (array instanceof Object[]) {
			propagateCheckpointSlow(array,version);
		}
	}
	
	public static AtomicInteger[] counts = new AtomicInteger[128];
	
	static {
		for (int i = 0 ; i < counts.length ; i++)
			counts[i] = new AtomicInteger(0);
	}
	
	private static void propagateCheckpointSlow(Object array, int version)
	{
		long versionOffset = -1;
		int curV;
		int oldKlass = -1;
		int klassToChangeTo = 0;
		CheckpointRollbackAgent.nArrayPropagations++;
		boolean slow = false;
//		if (((Object[])array).length < counts.length)
//			counts[((Object[])array).length].incrementAndGet();
		for (Object obj : ((Object[]) array)) {
			if (obj != null) {
				if (obj instanceof Class || obj instanceof String)
					break;
				if (obj instanceof CRIJInstrumented) {
//					versionOffset = obj.getClass().versionOffset;
//					curV = CheckpointRollbackAgent.u.$$CRIJNATIVEWRAP$$getInt(obj,versionOffset);
//					if(curV >= version)
//						continue;
					((CRIJInstrumented)obj).$$crijCheckpoint(version);
//					//Fast trick to try to force the object into checkpoint state
//					int klass = CheckpointRollbackAgent.u.$$CRIJNATIVEWRAP$$getInt(obj,8L);
//					if(klass == oldKlass)
//					{
//						curV = CheckpointRollbackAgent.u.$$CRIJNATIVEWRAP$$getInt(obj,versionOffset);
//						if(curV >= version)
//							continue;
//						if(klassToChangeTo != 0)
//							CheckpointRollbackAgent.u.$$CRIJNATIVEWRAP$$compareAndSwapInt(obj, 8L, klass, klassToChangeTo);
//					}
//					else
//					{
//						//Call checkpoint, see what the new klass is
//						versionOffset = obj.getClass().versionOffset;
//						((CRIJInstrumented) obj).$$crijCheckpoint(version);
//						oldKlass = klass;
//						klassToChangeTo = CheckpointRollbackAgent.u.$$CRIJNATIVEWRAP$$getInt(obj,8L);
//						if(klassToChangeTo == klass)
//							klassToChangeTo = 0;
//					}
				}
				else if(obj.getClass().isArray()) {
					slow = true;
					ArrayWrapper.propagateCheckpoint(obj, version);
				} else {
					slow = true;
					ObjectWrapper.propagateCheckpoint(obj, version);
				}
			}
		}

		if (slow)
			CheckpointRollbackAgent.nSlowArrayPropagations++;
	}

	public static void propagateRollback(Object array, int version) {
		if (array == null)
			return;

		ArrayWrapper w = ArrayWrapper.getOrInitWrapper(array);
		w.$$crijRollback(array, version);
		if (array instanceof Object[]) {
			for (Object obj : ((Object[]) array)) {
				if (obj != null) {
					if (obj instanceof Class || obj instanceof String)
						return;
					if(obj instanceof CRIJInstrumented)
					{
//						try {
							((CRIJInstrumented) obj).$$crijRollback(version);
//						} catch (AbstractMethodError err) {
//							// nop
//						}
					}
					else if(obj.getClass().isArray())
						ArrayWrapper.propagateRollback(obj, version);
					else
						ObjectWrapper.propagateRollback(obj, version);
				}
			}
		}
	}
}
