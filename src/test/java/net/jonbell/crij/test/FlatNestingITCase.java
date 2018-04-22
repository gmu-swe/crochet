package net.jonbell.crij.test;

import org.junit.Test;

import java.lang.reflect.Field;

import org.junit.Assert;

import net.jonbell.crij.runtime.CRIJCoW;
import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CRIJSlowCheckpoint;
import net.jonbell.crij.runtime.CRIJSlowRollback;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;

import sun.misc.Unsafe;

public class FlatNestingITCase {

	static Unsafe u;
	static
	{
		try{
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			u = (Unsafe) f.get(null);
		}
		catch(Throwable t)
		{
			t.printStackTrace();
		}
	}


	@Test
	public void testFastNoOldCheckLargerVer()
	{
		// Build object graph
		Ref root = new Ref();

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		Assert.assertEquals(0, ((CRIJInstrumented)root).$$CRIJgetVersion());
		((CRIJInstrumented)root).$$crijCheckpoint(version);

		// Check results
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		Assert.assertTrue(root instanceof CRIJSlowCheckpoint);
	}
	@Test
	public void testFastNoOldCheckSameVer()
	{
		// Build object graph
		Ref root    = new Ref();
		Ref left    = new Ref();
		Ref right   = new Ref();
		Ref aliased = new Ref();

		root.left = left;
		root.right = right;

		left.left = aliased;
		right.right = aliased;

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		((CRIJInstrumented)root).$$crijCheckpoint(version);

		// Mutate to propagate checkpoint and check results
		Assert.assertEquals(0, ((CRIJInstrumented)left).$$CRIJgetVersion());
		Assert.assertEquals(0, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		root.left.left.field = 10;
		Assert.assertEquals(Ref.class, aliased.getClass());
		Assert.assertTrue(right instanceof CRIJSlowCheckpoint);
		Assert.assertEquals(Ref.class, aliased.getClass());
		Assert.assertEquals(version, ((CRIJInstrumented)left).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());

		unused(root.right.right.field);

		// Check results
		Assert.assertEquals(version, ((CRIJInstrumented)left).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());
	}
	@Test
	public void testFastOldCheckLargerVer()
	{
		// Build object graph
		Ref root = new Ref();

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		Assert.assertEquals(0, ((CRIJInstrumented)root).$$CRIJgetVersion());
		((CRIJInstrumented)root).$$crijCheckpoint(version);
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());

		// Mutate object graph
		Assert.assertNull(getOld(root));
		root.field = 42;
		Assert.assertNotNull(getOld(root));
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		Assert.assertEquals(Ref.class, root.getClass());

		// Checkpoint object graph
		version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		((CRIJInstrumented)root).$$crijCheckpoint(version);

		// Check results
//		Assert.assertNull(getOld(root));
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		Assert.assertTrue(root instanceof CRIJSlowCheckpoint);
	}
	@Test
	public void testFastOldCheckSameVer()
	{
		// Build object graph
		Ref root    = new Ref();
		Ref left    = new Ref();
		Ref right   = new Ref();
		Ref aliased = new Ref();

		root.left = left;
		root.right = right;

		left.left = aliased;
		right.right = aliased;

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		((CRIJInstrumented)root).$$crijCheckpoint(version);

		// Mutate object graph and check results
		Assert.assertNull(getOld(aliased));
		Assert.assertEquals(0, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		root.left.left.field = 42;
		Assert.assertNotNull(getOld(aliased));
		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		Assert.assertEquals(Ref.class, aliased.getClass());

		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());
		Assert.assertTrue(right instanceof CRIJSlowCheckpoint);

		unused(root.right.right.field);

		Assert.assertNotNull(getOld(aliased));
		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		Assert.assertEquals(Ref.class, aliased.getClass());

		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());
//		Assert.assertTrue(right instanceof CRIJCoW);
	}
//	@Test
//	public void testFastNoOldCRollLargerVer()
//	{
//		// Build object graph
//		Ref root = new Ref();
//
//		// Checkpoint object graph
//		int version = CheckpointRollbackAgent.getNewVersionForRollback();
//		Assert.assertEquals(0, ((CRIJInstrumented)root).$$CRIJgetVersion());
//		((CRIJInstrumented)root).$$crijRollback(version);
//
//		// Check results
//		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
////		Assert.assertTrue(root instanceof CRIJSlowRollback);
//	}
	@Test
	public void testFastNoOldRollSameVer()
	{
		// Build object graph
		Ref root = new Ref();
		Ref left = new Ref();
		Ref right = new Ref();
		root.left = left;
		root.right = right;
		Ref aliased = new Ref();
		left.left = aliased;
		right.right = aliased;

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForRollback();
		((CRIJInstrumented)root).$$crijRollback(version);

		// Mutate to propagate checkpoint and check results
		Assert.assertEquals(0, ((CRIJInstrumented)left).$$CRIJgetVersion());
		Assert.assertEquals(0, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		unused(root.left.left.field);
		Assert.assertEquals(Ref.class, aliased.getClass());
//		Assert.assertTrue(right instanceof CRIJSlowRollback);
		Assert.assertEquals(version, ((CRIJInstrumented)left).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());

		unused(root.right.right.field);

		// Check results
		Assert.assertEquals(Ref.class, right.getClass());
		Assert.assertEquals(version, ((CRIJInstrumented)left).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());
	}
	@Test
	public void testFastOldRollLargerVer()
	{
		// Build object graph
		Ref root = new Ref();
		root.field = 20;

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		Assert.assertEquals(0, ((CRIJInstrumented)root).$$CRIJgetVersion());
		((CRIJInstrumented)root).$$crijCheckpoint(version);

		// Mutate to propagate checkpoint and check results
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
//		Assert.assertTrue(root instanceof CRIJSlowCheckpoint);
		Assert.assertNull(getOld(root));
		root.field = 30;
		Assert.assertEquals(Ref.class, root.getClass());
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		Assert.assertNotNull(getOld(root));

		// Rollback
		version = CheckpointRollbackAgent.getNewVersionForRollback();
		Assert.assertEquals(version-1, ((CRIJInstrumented)root).$$CRIJgetVersion());
		((CRIJInstrumented)root).$$crijRollback(version);

		//  Propagate rollback and check results
//		Assert.assertTrue(root instanceof CRIJSlowRollback);
		Assert.assertNotNull(getOld(root));
		Assert.assertEquals(20, root.field);
		Assert.assertEquals(Ref.class, root.getClass());
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
//		Assert.assertNull(getOld(root));
	}
	@Test
	public void testCheckNoOldCheckLargerVer()
	{
		// Build object graph
		Ref root = new Ref();

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		Assert.assertEquals(0, ((CRIJInstrumented)root).$$CRIJgetVersion());
		((CRIJInstrumented)root).$$crijCheckpoint(version);
//		Assert.assertTrue(root instanceof CRIJSlowCheckpoint);
		Assert.assertNull(getOld(root));
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		((CRIJInstrumented)root).$$crijCheckpoint(version);

		// Check results
//		Assert.assertTrue(root instanceof CRIJSlowCheckpoint);
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		Assert.assertNull(getOld(root));
	}
	@Test
	public void testCheckNoOldCheckSameVer()
	{
		// Build object graph
		Ref root    = new Ref();
		Ref left    = new Ref();
		Ref right   = new Ref();
		Ref aliased = new Ref();

		root.left = left;
		root.right = right;

		left.left = aliased;
		right.right = aliased;

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		((CRIJInstrumented)root).$$crijCheckpoint(version);

		// Propagate checkpoint
		unused(root.left.field);

		// Second checkpoint
		version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		((CRIJInstrumented)root).$$crijCheckpoint(version);

		// Propagate checkpoint
		unused(root.left.field);

		// Check objects in the right state
		Assert.assertEquals(version, ((CRIJInstrumented)left).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());

//		Assert.assertTrue(aliased instanceof CRIJSlowCheckpoint);
//		Assert.assertTrue(right   instanceof CRIJSlowCheckpoint);

		Assert.assertNull(getOld(aliased));

		// Test operation
		unused(root.right.field);

		// Check results
		Assert.assertEquals(version, ((CRIJInstrumented)left).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());

//		Assert.assertTrue(aliased instanceof CRIJSlowCheckpoint);
//		Assert.assertTrue(right   instanceof CRIJCoW);

		Assert.assertNull(getOld(aliased));
	}
	@Test
	public void testCheckNoOldRollLargerVer()
	{
		// Build object graph
		Ref root = new Ref();

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		((CRIJInstrumented)root).$$crijCheckpoint(version);

		// Check objects in the right state
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
//		Assert.assertTrue(root instanceof CRIJSlowCheckpoint);
		Assert.assertNull(getOld(root));

		// Rollback object graph
		version = CheckpointRollbackAgent.getNewVersionForRollback();
		((CRIJInstrumented)root).$$crijRollback(version);

		// Check results
		Assert.assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
//		Assert.assertTrue(root instanceof CRIJSlowRollback);
		Assert.assertNull(getOld(root));
	}
	@Test
	public void testRollbackAndCheckpoint()
	{
		// Build object graph
		Ref root1   = new Ref();
		Ref root2   = new Ref();
		Ref nonroot = new Ref();
		ArrayRef arr = new ArrayRef();

		Ref original = new Ref();
		original.field = 10;
		Ref modified = new Ref();
		modified.field = 20;

		root1.arr = arr;
		arr.array = new Ref[1];
		arr.array[0] = nonroot;
		nonroot.left = root2;
		nonroot.right = original;

		// Checkpoint object graph
		int version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		((CRIJInstrumented)root1).$$crijCheckpoint(version);
		((CRIJInstrumented)root2).$$crijCheckpoint(version);

		// Modify object graph
		root1.arr.array[0].right.field = 30;
		root1.arr.array[0].right = modified;

		// Check objects in the right state
		Assert.assertEquals(version, ((CRIJInstrumented)root1).$$CRIJgetVersion());
//		Assert.assertTrue(root1 instanceof CRIJCoW);
		Assert.assertEquals(Ref.class, nonroot.getClass());
//		Assert.assertTrue(root2 instanceof CRIJSlowCheckpoint);

		// Rollback object graph
		version = CheckpointRollbackAgent.getNewVersionForRollback();
		((CRIJInstrumented)root1).$$crijRollback(version);
		((CRIJInstrumented)root2).$$crijRollback(version);

		// Check objects in the right state
//		Assert.assertTrue(root1 instanceof CRIJSlowRollback);
		Assert.assertEquals(Ref.class, nonroot.getClass());
//		Assert.assertTrue(root2 instanceof CRIJSlowRollback);

		// Checkpoint object graph
		version = CheckpointRollbackAgent.getNewVersionForCheckpoint();
		((CRIJInstrumented)root1).$$crijCheckpoint(version);
		((CRIJInstrumented)root2).$$crijCheckpoint(version);

		// Check objects in the right state
//		Assert.assertTrue(root1 instanceof CRIJSlowCheckpoint);
//		Assert.assertTrue(root2 instanceof CRIJSlowCheckpoint);
//		Assert.assertTrue(nonroot instanceof CRIJSlowRollback);
		Assert.assertEquals(Ref.class, nonroot.getClass());
		Assert.assertEquals(version, ((CRIJInstrumented)root1).$$CRIJgetVersion());
		Assert.assertEquals(version, ((CRIJInstrumented)root2).$$CRIJgetVersion());
//		Assert.assertEquals(version-1, ((CRIJInstrumented)nonroot).$$CRIJgetVersion());
//		Assert.assertEquals(version-1, ((CRIJInstrumented)nonroot).$$CRIJgetVersion());

		// Check nonroot has the right fields
//		Assert.assertEquals(nonroot.right, original);
		Assert.assertEquals(root1.arr.array[0].right, original);
		Assert.assertEquals(root1.arr.array[0].right.field, 10);
	}
//	@Test
//	public void testCheckNoOldRollSameVer()
//	{
//		// Build object graph
//		Ref root    = new Ref();
//		Ref left    = new Ref();
//		Ref right   = new Ref();
//		Ref aliased = new Ref();
//
//		root.left = left;
//		root.right = right;
//
//		left.left = aliased;
//		right.right = aliased;
//
//		// Checkpoint object graph
//		int version = CheckpointRollbackAgent.getNewVersion();
//		((CRIJInstrumented)root).$$crijCheckpoint(version);
//
//		// Propagate checkpoint
//		unused(root.left.field);
//
//		// Rollback object graph
//		version = CheckpointRollbackAgent.getNewVersion();
//		((CRIJInstrumented)root).$$crijRollback(version);
//
//		// Propagate rollback
//		unused(root.left.field);
//
//		// Check objects in the right state
//		Assert.assertEquals(version, ((CRIJInstrumented)left).$$CRIJgetVersion());
//		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
//		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());
//
//		Assert.assertTrue(aliased instanceof CRIJSlowCheckpoint);
//		Assert.assertTrue(right   instanceof CRIJSlowRollback);
//
//		Assert.assertNull(getOld(aliased));
//
//		// Test operation
//		unused(root.right.field);
//
//		// Check results
//		Assert.assertEquals(version, ((CRIJInstrumented)left).$$CRIJgetVersion());
//		Assert.assertEquals(version, ((CRIJInstrumented)aliased).$$CRIJgetVersion());
//		Assert.assertEquals(version, ((CRIJInstrumented)right).$$CRIJgetVersion());
//
//		Assert.assertTrue(aliased instanceof CRIJSlowRollback);
//		Assert.assertEquals(Ref.class, right.getClass());
//
//		Assert.assertNull(getOld(aliased));
//	}

	private static void unused(int i)
	{
		// empty
	}
	static Object getOld(Object obj)
	{
		return CheckpointRollbackAgent.getOld(obj);
	}

	static class Ref {
		int field;
		Ref left, right;
		ArrayRef arr;
	}

	static class ArrayRef {
		Ref array[];
	}
	
	public static void main(String[] args)
	{
		new FlatNestingITCase().testRollbackAndCheckpoint();
	}
}