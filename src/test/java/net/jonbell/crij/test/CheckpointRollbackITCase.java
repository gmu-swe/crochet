package net.jonbell.crij.test;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import net.jonbell.crij.instrument.CheckpointRollbackStubClassGenerator;
import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.Tagger;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.objectweb.asm.ClassWriter;

/**
 * Turn this test off if we are turning off stack support methodvisitor!
 * @author jon
 *
 */
@Ignore
public class CheckpointRollbackITCase {

	static int sFoo;
	@Test
	public void testStaticFields() throws Exception {
		sFoo = 100;
		assertEquals(100, sFoo);
		CheckpointRollbackAgent.checkpointAllRoots();
		sFoo = 200;
		assertEquals(200, sFoo);
		CheckpointRollbackAgent.rollbackAllRoots();
		assertEquals(100, sFoo);
	}
	@Test
	public void testSimpleIntField() throws Exception {
		Holder h = new Holder();
		h.a = 10;
		assertTrue(h instanceof Holder);
		assertEquals(10, h.a);
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)h).$$crijCheckpoint(version);
		//Below assertion no longer works because we wrap getClass :)
//		assertTrue(h.getClass().getName().startsWith("net.jonbell.crij.test.CheckpointRollbackITCase$Holder$$CRIJSlowCheckpoint/"));
		assertEquals(10, h.a);
		h.a = 11;
		assertEquals(11, h.a);
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)h).$$crijRollback(version);
		assertEquals(10, h.a);
	}
	
	@Test
	public void testIntFieldImmediateRollback() throws Exception {
		Holder h = new Holder();
		h.a = 10;
		assertEquals(10, h.a);
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)h).$$crijCheckpoint(version);
		h.a = 11;
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)h).$$crijRollback(version);
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)h).$$crijCheckpoint(version);
		assertEquals(10, h.a);

	}
	@Test
	public void testIntFieldImmediateRollbackTwoObjects() throws Exception {
		Holder3 h1 = new Holder3();
		Holder3 h2 = new Holder3();
		h1.a = 10;
		h1.next = h2;
		h2.a = 10;
		h2.next = null;
		assertEquals(10, h1.a);
		assertEquals(10, h2.a);
		assertEquals(h1.next, h2);

		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)h1).$$crijCheckpoint(version);

		h1.a = 11;
		h2.a = 11;
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)h1).$$crijRollback(version);
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)h1).$$crijCheckpoint(version);
		assertEquals(10, h1.a);
		assertEquals(10, h2.a);

	}
//	@Test
	public void testStackNesting() throws Exception {
		int i = 7;
		assertEquals(7, i);
		int version = CheckpointRollbackAgent.getNewVersion();
		Tagger.checkpointStackRoots(version);

		i = 10;
		assertEquals(10, i);
		version = CheckpointRollbackAgent.getNewVersion();
		Tagger.checkpointStackRoots(version);
		
		i = 11;
		assertEquals(11, i);
		version = CheckpointRollbackAgent.getNewVersion();
		Tagger.rollbackStackRoots(version);
		
		assertEquals(10, i);
		version = CheckpointRollbackAgent.getNewVersion();
		Tagger.rollbackStackRoots(version);
		assertEquals(7, i);
	}
	@Test
	public void testStack() throws Exception {
		Thread t = new Thread(new Runnable() {
			
			@Override
			public void run() {
				Holder h = new Holder();
				h.a = 10;
				int i = 7;
				double d = 6;
				float f = 5;
				long j = 4;
				boolean z = false;
				byte b = 2;
				char c = 1;
				int v = CheckpointRollbackAgent.getCurrentVersion();
				CheckpointRollbackAgent.checkpointAllRoots(false);
				assertTrue(h instanceof Holder);
				assertEquals(10, h.a);
				assertEquals(7, i);
				assertEquals(6, d, 0);
				assertEquals(5, f, 0);
				assertEquals(4, j);
				assertEquals(false, z);
				assertEquals(2, b);
				assertEquals(1, c);
				System.out.println("Testing stack (should print twice)");
				h.a = 11;
				assertEquals(11, h.a);
				h = null;
				i += 100;
				d += 100;
				f+= 100;
				j += 100;
				z = true;
				b += 100;
				c += 100;
				assertEquals(107, i);
				assertEquals(106, d, 0);
				assertEquals(105, f, 0);
				assertEquals(104, j);
				assertEquals(true, z);
				assertEquals(102, b);
				assertEquals(101, c);

				if(v + 1== CheckpointRollbackAgent.getCurrentVersion())
					CheckpointRollbackAgent.rollbackAllRoots(false);
			}
		});
		t.start();
		t.join();
	}
	
	@Test
	public void testStaticIntField() throws Exception {
		Holder.z = 10;
		assertEquals(10, Holder.z);
		int version = CheckpointRollbackAgent.getNewVersion();
		CRIJInstrumented sfHelper = (CRIJInstrumented) Holder.class.getDeclaredMethod("$$CRIJGetSFHelper",null).invoke(null);
		sfHelper.$$crijCheckpoint(version);
		Holder.z = 100;
		assertEquals(100, Holder.z);
		version = CheckpointRollbackAgent.getNewVersion();
		sfHelper.$$crijRollback(version);
		assertEquals(10, Holder.z);
	}
	static class Holder
	{
		int a;
		String b;
		static int z;
	}
	static class Holder2
	{
		int i;
	}

	public static void main(String[] args) throws Exception {
		JUnitCore.main(CheckpointRollbackITCase.class.getName());
//		new CheckpointRollbackITCase().testStack();
	}
	static class Holder3
	{
		int a;
		Holder3 next;
	}
}
