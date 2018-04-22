package net.jonbell.crij.runtime;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.ResourceBundle;
import java.util.Set;

import sun.misc.Unsafe;
import sun.misc.VM;
import net.jonbell.crij.instrument.Instrumenter;

public class RootCollector {

	public static Set<ClassCoverageProbe> collectedRootClasses = new HashSet<>();
	public static Set<WeakReference<CRIJInstrumented>> collectedRootObjects = new HashSet<>();
	public static boolean collectingRoots = false;
	public static boolean enabled = false;
	
	private static ClassCoverageProbe enabledProbe = new ClassCoverageProbe(null);
	private static DisabledClassCoverageProbe disabledProbe = new DisabledClassCoverageProbe(null);
	

	public static void collectClass(ClassCoverageProbe p)
	{
		if(!enabled)
//		if (!collectingRoots &&!VM.isBooted())
			return;
		int klass = CheckpointRollbackAgent.u.getInt(disabledProbe,8L);
		CheckpointRollbackAgent.u.putInt(p, 8L, klass);
		Class<?> c = p.getC();
		if (c.equals(HashSet.class))
			return;

		if (CRIJSFHelper.class.isAssignableFrom(c))
			return;
		synchronized (collectedRootClasses)
		{
//			if (collectedRootClasses.isEmpty())
//			{
//				Class<?> initializedClasses[] = Tagger.getInitializedClasses();
//				collectedRootClasses.addAll(Arrays.asList(initializedClasses));
//				collectedRootClasses.add(ResourceBundle.class);
//			}

			collectedRootClasses.add(p);
		}
	}
	
	private static void enableProbe(ClassCoverageProbe p)
	{
		int klass = CheckpointRollbackAgent.u.getInt(enabledProbe,8L);
		CheckpointRollbackAgent.u.putInt(p, 8L, klass);
	}
	public static void collectObject(CRIJInstrumented o)
	{
		synchronized (collectedRootClasses)
		{
			collectedRootObjects.add(new WeakReference<CRIJInstrumented>(o));
		}
	}
	public static void startCollectingRoots()
	{
		if (collectingRoots)
			throw new IllegalStateException();
		
//		System.out.println("Started collecting roots");

		for(ClassCoverageProbe p : collectedRootClasses)
		{
			//Re-enable the probe
			enableProbe(p);
		}
//		collectedRootClasses.clear();
//		collectedRootObjects.clear();
		collectingRoots = true;
//		collectedRootClasses = new HashSet<>();
//		collectedRootObjects = new HashSet<>();
	}
	public static void checkpointCollectedRoots(int version)
	{
//		System.out.println("Checkpointing roots " + collectedRootClasses.size());

//		collectingRoots = false;


		// Checkpoint all threads
		for (Thread t : getAllThreads())
		{
			((CRIJInstrumented)t).$$crijCheckpoint(version);
		}

		// Checkpoint root classes
		collectingRoots = false;

		for (ClassCoverageProbe p : collectedRootClasses){
			Class<?> c = p.getC();
			try {
//				if(c.getName().equals("avrora.arch.legacy.LegacyRegister"))
//					System.out.println(c.getName());

				Method m = c.getDeclaredMethod("$$CRIJGetSFHelper",new Class[0]);
				boolean accessible = m.isAccessible();
				m.setAccessible(true);
				CRIJSFHelper sfHelper = (CRIJSFHelper) m.invoke(new Object[0]);
				m.setAccessible(accessible);
				sfHelper.$$crijCheckpoint(version);
			} catch (NoSuchMethodException e) {
//				e.printStackTrace();
				continue;
			} catch (SecurityException | IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
//				throw new Error(e);
				continue;
			}
		}

		for (WeakReference<CRIJInstrumented> o : collectedRootObjects)
		{
			CRIJInstrumented i = o.get();
			if(i != null)
				i.$$crijCheckpoint(version);
		}
		collectingRoots = true;
	}
	public static void rollbackCollectedRoots(int version)
	{
//		System.out.println("Rolling-back roots");
//		if (collectingRoots)
//			throw new IllegalStateException();
		collectingRoots = false;

		// Rollback all threads
		for (Thread t : getAllThreads())
		{
			((CRIJInstrumented)t).$$crijRollback(version);
		}

		// Rollback all root classes
		for (ClassCoverageProbe p : collectedRootClasses)
		{
			Class<?> c = p.getC();
			try {
				Method m = c.getDeclaredMethod("$$CRIJGetSFHelper",new Class[0]);
				boolean accessible = m.isAccessible();
				m.setAccessible(true);
				CRIJSFHelper sfHelper = (CRIJSFHelper) m.invoke(new Object[0]);
				m.setAccessible(accessible);
				sfHelper.$$crijRollback(version);
			} catch (NoSuchMethodException e) {
				// TODO What should we do here?
				continue;
			} catch (SecurityException | IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
				throw new Error(e);
			}
		}

		for (WeakReference<CRIJInstrumented> o : collectedRootObjects)
		{
			CRIJInstrumented i = o.get();
			if(i != null)
				i.$$crijRollback(version);
		}
		collectingRoots = true;

	}
	public static Thread[] getAllThreads()
	{
		// Get top-level thread group
		ThreadGroup rootGroup = Thread.currentThread( ).getThreadGroup( );
		ThreadGroup parentGroup;
		while ( ( parentGroup = rootGroup.getParent() ) != null ) {
		    rootGroup = parentGroup;
		}

		// Get all threads
		Thread[] threads = new Thread[ rootGroup.activeCount() ];
		while ( rootGroup.enumerate( threads, true ) == threads.length ) {
		    threads = new Thread[ threads.length * 2 ];
		}

		// Resize array to remove null entries
		int size = 0;
		for (int i = 0 ; i < threads.length ; i++)
		{
			if (threads[i] == null)
			{
				size = i;
				break;
			}
		}
		
		Thread[] ret = new Thread[size];
		System.arraycopy(threads, 0, ret, 0, size);

		return ret;
	}
}
