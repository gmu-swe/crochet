package net.jonbell.crij.test;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.wrapper.ObjectWrapper;
import net.jonbell.crij.test.support.LoadedClass;

import org.junit.Test;

public class ReflectionITCase {

	int f;
	static int sf;
	LoadedClass[] a;
	static LoadedClass[] sa;
	@Test
	public void testRegularReflection() throws Exception {
		Field f = ReflectionITCase.class.getDeclaredField("f");
		f.setInt(this, 100);
		((CRIJInstrumented) this).$$crijCheckpoint(CheckpointRollbackAgent.getNewVersion());

		f.setInt(this, 50);
		((CRIJInstrumented) this).$$crijRollback(CheckpointRollbackAgent.getNewVersion());
		assertEquals(100, f.getInt(this));
	}
	
//	@Test
//	public void testSFReflection() throws Exception {
//		Field f = ReflectionITCase.class.getDeclaredField("sf");
//		f.setInt(null, 100);
////		ObjectWrapper.propagateCheckpoint(f, 1);
////		ObjectWrapper.propagateCheckpoint(Thread.currentThread(), 1);
////		ObjectWrapper.propagateCheckpoint(this, 1);
//		
//		
//		ReflectionITCase.class.sfHelper.$$crijCheckpoint(CheckpointRollbackAgent.getNewVersion());
//		f.setInt(null, 50);
////		ObjectWrapper.propagateRollback(f, 2);
////		ObjectWrapper.propagateRollback(Thread.currentThread(), 2);
////		ObjectWrapper.propagateRollback(this, 2);
//		ReflectionITCase.class.sfHelper.$$crijRollback(CheckpointRollbackAgent.getNewVersion());
//		assertEquals(100, f.getInt(null));
//	}
	
//	@Test
//	public void testArrayFieldReflection() throws Exception {
//		
//	}
//	
//	@Test
//	public void testArrayFieldStaticReflection() throws Exception {
//		
//	}
//	@Test
//	public void testGetInterface() throws Exception {
//		Class[] i = ReflectionITCase.class.getInterfaces();
//		System.out.println(Arrays.toString(i));
//	}
//	@Test
//	public void testGetFields() throws Exception {
//		Field[] f = ReflectionITCase.class.getDeclaredFields();
//		System.out.println(Arrays.toString(f));
//		
//		f = ReflectionITCase.class.getFields();
//		System.out.println(Arrays.toString(f));
//	}
//	@Test
//	public void testGetMethods() throws Exception {
//		Method[] m = ReflectionITCase.class.getDeclaredMethods();
//		System.out.println(Arrays.toString(m));
//		
//		m = ReflectionITCase.class.getMethods();
//		System.out.println(Arrays.toString(m));
//	}
}
