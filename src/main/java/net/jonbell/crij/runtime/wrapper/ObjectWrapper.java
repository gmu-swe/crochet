package net.jonbell.crij.runtime.wrapper;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.jonbell.crij.instrument.Instrumenter;
import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.Tagger;

public class ObjectWrapper {

	private Object old;
	private byte state;
	private static final byte STATE_NORMAL = 0;
	private static final byte STATE_CHECKPOINTED = 1;

	public ObjectWrapper(Object obj) {

	}

	/**
	 * Concurrency: Can't synchronize on ar because someone else might be
	 * legitimately holding a lock. Bleh.
	 * 
	 * @param ar
	 * @return
	 */
	public synchronized static ObjectWrapper getOrInitWrapper(Object ar) {
		ObjectWrapper ret = (ObjectWrapper) Tagger.getTag(ar);
		if (ret == null) {
			ret = new ObjectWrapper(ar);
			Tagger.setTag(ar, ret);
		}
		return ret;
	}

	public static void propagateCheckpoint(Object obj, int version) {
		if (obj == null)
			return;
		if (obj.getClass().isArray()) {
			ArrayWrapper.propagateCheckpoint(obj, version);
			return;
		}
		if (obj instanceof Class || obj instanceof String)
			return;
		if(obj instanceof Reference)
		{
			propagateCheckpoint(((Reference<?>) obj).get(), version);
		}
		if(Instrumenter.isIgnoredClass(obj.getClass()))
			return;
		if (obj instanceof CRIJInstrumented) {
			try {
				((CRIJInstrumented) obj).$$crijCheckpoint(version);
			} catch (AbstractMethodError er) {
//				if(obj instanceof Class)
//					return;
//				if(obj instanceof String)
//					return;
//				er.printStackTrace();
//				System.out.println(obj.getClass());
				getOrInitWrapper(obj).$$crijCheckpoint(obj, version);
			}
			return;
		}
		getOrInitWrapper(obj).$$crijCheckpoint(obj, version);
	}

	private void $$crijCheckpoint(Object obj, int version) {
		if (state == STATE_CHECKPOINTED)
			return;
		state = STATE_CHECKPOINTED;
		try {
			old = CheckpointRollbackAgent.allocateInstance(obj.getClass());
			for (Field f : old.getClass().getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers())) {
					f.setAccessible(true);
					f.set(old, f.get(obj));
				}
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	private void $$crijRollback(Object obj, int version) {
		if (state == STATE_NORMAL)
			return;
		state = STATE_NORMAL;
		try {
			for (Field f : old.getClass().getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers())) {
					f.setAccessible(true);
					f.set(obj, f.get(old));
				}
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	public static void propagateRollback(Object obj, int version) {
		if (obj == null)
			return;
		if (obj.getClass().isArray()) {
			ArrayWrapper.propagateRollback(obj, version);
			return;
		}
		if (obj instanceof Class || obj instanceof String)
			return;
		if(obj instanceof Reference)
		{
			propagateRollback(((Reference<?>) obj).get(), version);
		}
		if(Instrumenter.isIgnoredClass(obj.getClass()))
			return;
		if (obj instanceof CRIJInstrumented) {
			try {
				((CRIJInstrumented) obj).$$crijRollback(version);
			} catch (AbstractMethodError er) {
//				if(obj instanceof Class)
//					return;
//				if(obj instanceof String)
//					return;
//				System.out.println(obj.getClass());
				getOrInitWrapper(obj).$$crijRollback(obj, version);
			}
			return;
		}
		getOrInitWrapper(obj).$$crijRollback(obj, version);
	}

}
