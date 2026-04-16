package net.jonbell.crij.test;

import org.junit.Test;

import java.io.Serializable;

import org.junit.Assert;

import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;


public class ObjectFieldITCase {

	@Test
	public void testRegularObject() {
		ObjectFieldRef obj = new ObjectFieldRef();
		Leaf l = new Leaf();
		l.field = 10;

		obj.o = l;

		// Checkpoint obj
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);

		// Mutate stuff
		((Leaf)(obj.o)).field = 2;
		obj.o = new Leaf();

		// Rollback obj
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijRollback(version);

		// Check everything
		Assert.assertSame(l, obj.o);
		Assert.assertEquals(10, ((Leaf)(obj.o)).field);
	}

	@Test
	public void testRef() {
		ObjectFieldRef obj = new ObjectFieldRef();
		Leaf l = new Leaf();
		l.field = 10;

		Ref r = new Ref();
		r.l = l;

		obj.o = r;

		// Checkpoint obj
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);

		// Mutate stuff
		((Ref)(obj.o)).l.field = 2;
		r.l = new Leaf();
		obj.o = new Ref();

		// Rollback obj
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijRollback(version);

		// Check everything
		Assert.assertSame(r, obj.o);
		Assert.assertSame(l, r.l);
		Assert.assertEquals(10, ((Ref)(obj.o)).l.field);
	}

	@Test
	public void testLeafArray() {
		ObjectFieldRef obj = new ObjectFieldRef();
		Leaf l = new Leaf();
		l.field = 10;

		Leaf[] arr = new Leaf[] { l };
		obj.o = arr;

		// Checkpoint obj
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);

		// Mutate stuff
		((Leaf[])(obj.o))[0].field = 2;
		((Leaf[])(obj.o))[0] = new Leaf();
		obj.o = new Leaf[] { new Leaf() };

		// Rollback obj
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijRollback(version);

		// Check everything
		Assert.assertSame(arr, obj.o);
		Assert.assertSame(l, arr[0]);
		Assert.assertEquals(10, ((Leaf[])(obj.o))[0].field);
	}

	@Test
	public void testObjectArray() {
		ObjectFieldRef obj = new ObjectFieldRef();
		Leaf l = new Leaf();
		l.field = 10;

		Object[] arr = new Object[] { l };
		obj.o = arr;

		// Checkpoint obj
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);

		// Mutate stuff
		((Leaf)((Object[])(obj.o))[0]).field = 2;
		((Object[])(obj.o))[0] = new Leaf();
		obj.o = new Object[] { new Leaf() };

		// Rollback obj
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijRollback(version);

		// Check everything
		Assert.assertSame(arr, obj.o);
		Assert.assertSame(l, arr[0]);
		Assert.assertEquals(10, ((Leaf)((Object[])(obj.o))[0]).field);
	}

	@Test
	public void testExcluded() {
		ExcludedFieldRef obj = new ExcludedFieldRef();
		Leaf l = new Leaf();
		l.field = 10;

		obj.o = l;

		// Checkpoint obj
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);

		// Mutate stuff
		((Leaf)(obj.o)).field = 2;
		obj.o = new Leaf();

		// Rollback obj
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijRollback(version);

		// Check everything
		Assert.assertSame(l, obj.o);
		Assert.assertEquals(10, ((Leaf)(obj.o)).field);
	}

	static class ExcludedFieldRef {
		Serializable o;
	}

	static class ObjectFieldRef {
		Object o;
	}

	static class Ref {
		Leaf l;
	}

	static class Leaf implements Serializable {
		int field;
	}
}