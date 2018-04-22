package net.jonbell.crij.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.jonbell.crij.runtime.internalstruct.ArrayList;



public class ReflectionFixer {
	static boolean slept = false;

	public static Object getClassPath(String v, Class c) {
		try {
//			System.out.println("GCP " + v);
			Constructor cons = c.getConstructor(String.class);
			return cons.newInstance(System.getProperty("eclipse.java.home") + "/lib/rt.jar");
		} catch (NoSuchMethodException | SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			e.printStackTrace();
		}
		return null;
	}
	public static void getClassPathCalled()
	{
		if(slept)
			return;
		try {
			Thread.sleep(1000);
			slept= true;
		} catch (InterruptedException e) {
		}
	}
	public static void access(Object obj)
	{
		if(obj instanceof CRIJSlowCheckpoint)
			((CRIJInstrumented)obj).$$crijAccess();
	}
	
	public static String getPropertyHideBootClasspath(String prop)
	{
		if(prop.equals("sun.boot.class.path"))
			return null;
		else if(prop.equals("os.name"))
			return "linux";
		return System.getProperty(prop);
	}
	public static Class[] removeInternalInterface(Class[] in) {
		if (in == null)
			return null;
		boolean found = false;
		for (int i = 0; i < in.length; i++) {
			if (in[i].equals(CRIJInstrumented.class))
				found = true;
		}
		if (!found)
			return in;
		Class[] ret = new Class[in.length - 1];
		int idx = 0;
		for (int i = 0; i < in.length; i++) {
			if (!in[i].equals(CRIJInstrumented.class)){
				ret[idx] = in[i];
				idx++;
			}
		}
		return ret;
	}

	public static Method[] removeInternalMethods(Method[] in) {
		ArrayList<Method> ret = new ArrayList<Method>();
		for (Method m : in) {
			if (m.getName().startsWith("$$crij")  || m.getName().startsWith("$$CRIJ") || m.getName().startsWith("crijGET$$")
					||m.getName().startsWith("crijSET$$")
					) {

			} else
				ret.add(m);
		}
		Method[] retz = new Method[ret.size()];
		ret.toArray(retz);
		return retz;
	}
	public static Field[] removeInternalFields(Field[] in)
	{
		ArrayList<Field> ret = new ArrayList<Field>();
		for (Field f : in) {
			if (f.getName().startsWith("$$crij") || f.getName().equals("$$GMU$$ClassCov") 
					) {

			} else
				ret.add(f);
		}
		Field[] retz = new Field[ret.size()];
		ret.toArray(retz);
		return retz;
	}
	public static Class<?> getClass(Object o)
	{
		Class<?> r = o.getClass();
		if(r.originalClass != null)
			return r.originalClass;
		return r;
	}
	private static void getCalled(Field f, Object o)
	{
		if (Modifier.isStatic(f.getModifiers())) {
			Class<?> c = f.getDeclaringClass();
			try {
				Field sf = c.getDeclaredField("$$crijSFHelper");
				sf.setAccessible(true);
				CRIJSFHelper sfHelper = (CRIJSFHelper) sf.get(null);
				if(sfHelper != null)
					sfHelper.$$crijAccess();
			} catch (IllegalArgumentException | IllegalAccessException | SecurityException e) {
				e.printStackTrace();
			} catch (NoSuchFieldException ex) {
				// swallow
			}
		}
		else if(o instanceof CRIJInstrumented)
		{
			((CRIJInstrumented) o).$$crijAccess();
		}
	
	}
	private static void setCalled(Field f, Object o)
	{
		if(Modifier.isStatic(f.getModifiers()))
		{
			Class<?> c = f.getDeclaringClass();
			try {
				Field sf = c.getDeclaredField("$$crijSFHelper");
				sf.setAccessible(true);
				CRIJSFHelper sfHelper = (CRIJSFHelper) sf.get(null);
				if(sfHelper != null)
					sfHelper.$$crijAccess();
			} catch (IllegalArgumentException | IllegalAccessException | SecurityException e) {
				e.printStackTrace();
			} catch (NoSuchFieldException ex) {
				// swallow
			}
		}
		else if(o instanceof CRIJInstrumented)
		{
			((CRIJInstrumented) o).$$crijAccess();
		}
	}
	public static Object get(Field f, Object o) throws IllegalArgumentException, IllegalAccessException
	{
		getCalled(f, o);
		f.setAccessible(true);
		return f.get(o);
	}
	public static int getInt(Field f, Object o) throws IllegalArgumentException, IllegalAccessException
	{
		getCalled(f, o);
		f.setAccessible(true);
		return f.getInt(o);
	}
	public static short getShort(Field f, Object o) throws IllegalArgumentException, IllegalAccessException
	{
		getCalled(f, o);
		f.setAccessible(true);
		return f.getShort(o);
	}
	public static long getLong(Field f, Object o) throws IllegalArgumentException, IllegalAccessException
	{
		getCalled(f, o);
		f.setAccessible(true);
		return f.getLong(o);
	}
	public static float getFloat(Field f, Object o) throws IllegalArgumentException, IllegalAccessException
	{
		getCalled(f, o);
		f.setAccessible(true);
		return f.getFloat(o);
	}
	public static double getDouble(Field f, Object o) throws IllegalArgumentException, IllegalAccessException
	{
		getCalled(f, o);
		f.setAccessible(true);
		return f.getDouble(o);
	}
	public static char getChar(Field f, Object o) throws IllegalArgumentException, IllegalAccessException
	{
		getCalled(f, o);
		f.setAccessible(true);
		return f.getChar(o);
	}
	public static byte getByte(Field f, Object o) throws IllegalArgumentException, IllegalAccessException
	{
		getCalled(f, o);
		f.setAccessible(true);
		return f.getByte(o);
	}
	public static boolean getBoolean(Field f, Object o) throws IllegalArgumentException, IllegalAccessException
	{
		getCalled(f, o);
		f.setAccessible(true);
		return f.getBoolean(o);
	}
	public static void set(Field f, Object o, Object v) throws IllegalArgumentException, IllegalAccessException
	{
		setCalled(f, o);
		f.setAccessible(true);
		f.set(o,v);
	}
	public static void setInt(Field f, Object o, int v) throws IllegalArgumentException, IllegalAccessException
	{
		setCalled(f, o);
		f.setAccessible(true);
		f.setInt(o, v);
	}
	public static void setShort(Field f, Object o, short v) throws IllegalArgumentException, IllegalAccessException
	{
		setCalled(f, o);
		f.setAccessible(true);
		f.setShort(o, v);
	}
	public static void setLong(Field f, Object o, long v) throws IllegalArgumentException, IllegalAccessException
	{
		setCalled(f, o);
		f.setAccessible(true);
		f.setLong(o, v);
	}
	public static void setFloat(Field f, Object o, float v) throws IllegalArgumentException, IllegalAccessException
	{
		setCalled(f, o);
		f.setAccessible(true);
		f.setFloat(o, v);
	}
	public static void setDouble(Field f, Object o, double v) throws IllegalArgumentException, IllegalAccessException
	{
		setCalled(f, o);
		f.setAccessible(true);
		f.setDouble(o, v);
	}
	public static void setChar(Field f, Object o, char v) throws IllegalArgumentException, IllegalAccessException
	{
		setCalled(f, o);
		f.setAccessible(true);
		f.setChar(o, v);
	}
	public static void setByte(Field f, Object o, byte v) throws IllegalArgumentException, IllegalAccessException
	{
		setCalled(f, o);
		f.setAccessible(true);
		f.setByte(o, v);
	}
	public static void setBoolean(Field f, Object o, boolean v) throws IllegalArgumentException, IllegalAccessException
	{
		setCalled(f, o);
		f.setAccessible(true);
		f.setBoolean(o, v);
	}
}
