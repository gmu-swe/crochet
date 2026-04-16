package net.jonbell.crij.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import net.jonbell.crij.instrument.Instrumenter;
import net.jonbell.crij.instrument.Premain;

public class StaticFieldWalker {

//	private static WeakHashMap<Class<?>, CRIJSFHelper> sfHelperMap = new WeakHashMap<Class<?>, CRIJSFHelper>();

	private static CRIJSFHelper getSFHelper(Class<?> c) {
		if (c.originalClass != null) {
			c = c.originalClass;
		}
		if(c.sfHelper != null)
			return c.sfHelper;
//		System.out.println("GSFH "+ c);
//		if (sfHelperMap.containsKey(c))
//			return sfHelperMap.get(c);
		try {
			if(!CheckpointRollbackAgent.u.tryMonitorEnter(c))
				return null;
//			System.out.println("Got lock");
			Method m = c.getMethod("$$CRIJGetSFHelper");
			m.setAccessible(true);
			CRIJSFHelper sfh = (CRIJSFHelper) m.invoke(null);
//			System.out.println("Called");
//			sfHelperMap.put(c, sfh);
			CheckpointRollbackAgent.u.monitorExit(c);
			return sfh;
		} catch (Throwable t) {
//			t.printStackTrace();
			throw new IllegalStateException("No SFHelper for class " + c, t);
		}
	}

	public static void traverseStaticFields(boolean checkpoint, int v) {
		if(!Premain.haveCheckpointed)
		{
			Premain.haveCheckpointed = true;
			for (Class c : Premain.notYetInited) {
				try {
					Method m = c.getDeclaredMethod("$$crijInit");
					m.setAccessible(true);
					m.invoke(null);
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
		}
		try {
			for (Class<?> c : Tagger.getInitializedClasses()) {
				try {
					if (c.getName().endsWith("$$crijSFHelper")) {
						continue;
					}
					if(c.isAnnotation() || c.isArray())
						continue;
					 if(!Instrumenter.isIgnoredClass(c) && !Instrumenter.isIgnoredClassWithDummyMethods(c))
						 getSFHelper(c);
				} catch (IllegalStateException e) {
					if (Instrumenter.isIgnoredClass(c))
						continue;
					else
					{
//						e.printStackTrace();
//						throw e;
					}
				}
			}
			for (Class<?> c : Tagger.getUnInitializedClasses()) {
				 if(Instrumenter.isIgnoredClass(c))
					 continue;
				 if(c.getName().startsWith("net.jon"))
					 System.out.println(c);
				if (checkpoint) {
//					Object cl = c.getClassLoader();
//					if (cl instanceof CRIJInstrumented)
//						((CRIJInstrumented) cl).$$crijCheckpoint(v);
//					 cl = c.getProtectionDomain();
//					 if(cl != null && cl instanceof CRIJInstrumented)
//						 ((CRIJInstrumented) cl).$$crijCheckpoint(v);
					c.$$crijCheckpoint(v);
				} else {
//					Object cl = c.getClassLoader();
//					if (cl instanceof CRIJInstrumented)
//						((CRIJInstrumented) cl).$$crijRollback(v);
//					 cl = c.getProtectionDomain();
//					 if(cl != null && cl instanceof CRIJInstrumented)
//					 ((CRIJInstrumented) cl).$$crijRollback(v);
					c.$$crijRollback(v);
				}
			}
			for (Class<?> c : Tagger.getInitializedClasses()) {
				try {
					if(c.getName().endsWith("$$crijSFHelper"))
					{
						continue;
					}
					if(c.getName().equals("java.util.List") && (v == 43 || v==42))
					{
						v = v;
					}
					if (!c.isAnnotation() && !c.isArray()) {
						if (checkpoint) {
							Object cl = c.getClassLoader();
							if (cl instanceof CRIJInstrumented)
								((CRIJInstrumented) cl).$$crijCheckpoint(v);
							 cl = c.getProtectionDomain();
							 if(cl != null && cl instanceof CRIJInstrumented)
								 ((CRIJInstrumented) cl).$$crijCheckpoint(v);
							 if(!Instrumenter.isIgnoredClass(c) && !Instrumenter.isIgnoredClassWithDummyMethods(c))
								 getSFHelper(c).$$crijCheckpoint(v);
							c.$$crijCheckpoint(v);
						} else {
							Object cl = c.getClassLoader();
							if (cl instanceof CRIJInstrumented)
								((CRIJInstrumented) cl).$$crijRollback(v);
							 cl = c.getProtectionDomain();
							 if(cl != null && cl instanceof CRIJInstrumented)
							 ((CRIJInstrumented) cl).$$crijRollback(v);
							 
							 if(!Instrumenter.isIgnoredClass(c) && !Instrumenter.isIgnoredClassWithDummyMethods(c))
								 getSFHelper(c).$$crijRollback(v);
							c.$$crijRollback(v);

						}
					}
				} catch (IllegalStateException e) {
					if (Instrumenter.isIgnoredClass(c))
						continue;
//					else
//						throw e;
				}
			}
			for (Thread t : Thread.getAllStackTraces().keySet()) {
//				System.out.println(t);
				if (checkpoint)
					((CRIJInstrumented) t).$$crijCheckpoint(v);
				else
					((CRIJInstrumented) t).$$crijRollback(v);
			}
			if (checkpoint)
				((CRIJInstrumented) ClassLoader.getSystemClassLoader()).$$crijCheckpoint(v);
			else
				((CRIJInstrumented) ClassLoader.getSystemClassLoader()).$$crijRollback(v);
			if(checkpoint)
			{
				Class<?> c= Class.forName("java.lang.ref.Finalizer");
				Field f = c.getDeclaredField("queue");
				f.setAccessible(true);
				((CRIJInstrumented)f.get(null)).$$crijCheckpoint(v);
			}
			else
			{
				Class<?> c= Class.forName("java.lang.ref.Finalizer");
				Field f = c.getDeclaredField("queue");
				f.setAccessible(true);
				((CRIJInstrumented)f.get(null)).$$crijRollback(v);
			}
			// for(Class c : Premain.inst.getAllLoadedClasses())
			// {
			// Object o = c.getClassLoader();
			// if(o != null)
			// {
			// if (checkpoint)
			// ((CRIJInstrumented) o).$$crijCheckpoint(v);
			// else
			// ((CRIJInstrumented) o).$$crijRollback(v);
			// }
			// }
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

}
