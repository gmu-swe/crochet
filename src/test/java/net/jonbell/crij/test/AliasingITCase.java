package net.jonbell.crij.test;

import org.junit.Test;
import org.junit.Assert;
import org.junit.Ignore;

import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;


/**
 * This test is hard-coded to the 5-class statemachine model, which we don't use any more,
 * and hence is disabled.
 *
 */
@Ignore
public class AliasingITCase {

	@Test
	public void testRollbackSimpleAliasing()
	{
		Leaf l = new Leaf();
		Ref r = new Ref();
		r.o = l;
		int version = CheckpointRollbackAgent.getNewVersion();
		// Checkpoint both leaf and ref
		((CRIJInstrumented)r).$$crijCheckpoint(version);
		((CRIJInstrumented)l).$$crijCheckpoint(version);

		String r_class = r.getClass().getName();
		String l_class = l.getClass().getName();

		Assert.assertTrue(r_class.matches(".*SlowCheckpoint.*"));
		Assert.assertTrue(l_class.matches(".*SlowCheckpoint.*"));
		
		// Write both objects
		{
			l.field = 1;
			r.o = new Leaf();
			r.o.field = 2;
		}
		
		// Rollback both leaf and ref
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)r).$$crijRollback(version);
		((CRIJInstrumented)l).$$crijRollback(version);

		// Check classes
		{
			r_class = r.getClass().getName();
			l_class = l.getClass().getName();

			Assert.assertTrue(r_class.matches(".*SlowRollback.*"));
			Assert.assertTrue(l_class.matches(".*SlowRollback.*"));
		}

		// Read both objects
		{
			int field = l.field;
			Object o = r.o;

			Assert.assertSame(l, o);
			Assert.assertEquals(field, 0);
		}

		// Check classes
		{
			l.field = 1;

			r_class = r.getClass().getName();
			l_class = l.getClass().getName();

			Assert.assertEquals(Ref.class.getName(), r_class);
			Assert.assertEquals(Leaf.class.getName(), l_class);
		}
	}

	@Test
	public void testRollbackSimpleArrayAliasing()
	{
		Leaf l = new Leaf();
		ArrayRef r = new ArrayRef();
		r.arr[0] = l;
		int version = CheckpointRollbackAgent.getNewVersion();
		// Checkpoint both leaf and ref
		((CRIJInstrumented)r).$$crijCheckpoint(version);
		((CRIJInstrumented)l).$$crijCheckpoint(version);

		String r_class = r.getClass().getName();
		String l_class = l.getClass().getName();

		Assert.assertTrue(r_class.matches(".*SlowCheckpoint.*"));
		Assert.assertTrue(l_class.matches(".*SlowCheckpoint.*"));
		
		// Write both objects
		{
			l.field = 1;
			r.arr[0] = new Leaf();
			r.arr[0].field = 2;
		}
		
		// Rollback both leaf and ref
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)r).$$crijRollback(version);
		((CRIJInstrumented)l).$$crijRollback(version);

		// Check classes
		{
			r_class = r.getClass().getName();
			l_class = l.getClass().getName();

			Assert.assertTrue(r_class.matches(".*SlowRollback.*"));
			Assert.assertTrue(l_class.matches(".*SlowRollback.*"));
		}

		// Read both objects
		{
			int field = l.field;
			Object o = r.arr[0];

			Assert.assertSame(l, o);
			Assert.assertEquals(field, 0);
		}

		// Check classes
		{
			l.field = 1;

			r_class = r.getClass().getName();
			l_class = l.getClass().getName();

			Assert.assertEquals(ArrayRef.class.getName(), r_class);
			Assert.assertEquals(Leaf.class.getName(), l_class);
		}
	}

	@Test
	public void testCheckpointSimpleAliasing()
	{
		Leaf l = new Leaf();
		Ref r = new Ref();
		r.o = l;
		int version = CheckpointRollbackAgent.getNewVersion();
		// Checkpoint both leaf and ref
		((CRIJInstrumented)r).$$crijCheckpoint(version);
		((CRIJInstrumented)l).$$crijCheckpoint(version);

		String r_class = r.getClass().getName();
		String l_class = l.getClass().getName();

		Assert.assertTrue(r_class.matches(".*SlowCheckpoint.*"));
		Assert.assertTrue(l_class.matches(".*SlowCheckpoint.*"));

		// Write leaf, ref unchanged
		{
			l.field = 1;

			r_class = r.getClass().getName();
			l_class = l.getClass().getName();

			Assert.assertTrue(r_class.matches(".*SlowCheckpoint.*"));
			Assert.assertEquals(Leaf.class.getName(), l_class);
		}

		// Read ref, checkpoint shouldn't get propagated to leaf
		{
			@SuppressWarnings("unused")
			Object o = r.o;

			r_class = r.getClass().getName();
			l_class = l.getClass().getName();

			Assert.assertTrue(r_class.matches(".*CoW.*"));
			Assert.assertEquals(Leaf.class.getName(), l_class);
		}
	}

	@Test
	public void testCheckpointSimpleArrayAliasing()
	{
		Leaf l = new Leaf();
		ArrayRef r = new ArrayRef();
		r.arr[0] = l;

		int version = CheckpointRollbackAgent.getNewVersion();
		// Checkpoint both leaf and ref
		((CRIJInstrumented)r).$$crijCheckpoint(version);
		((CRIJInstrumented)l).$$crijCheckpoint(version);

		String r_class = r.getClass().getName();
		String l_class = l.getClass().getName();

		Assert.assertTrue(r_class.matches(".*SlowCheckpoint.*"));
		Assert.assertTrue(l_class.matches(".*SlowCheckpoint.*"));

		// Write leaf, ref unchanged
		{
			l.field = 1;

			r_class = r.getClass().getName();
			l_class = l.getClass().getName();

			Assert.assertTrue(r_class.matches(".*SlowCheckpoint.*"));
			Assert.assertEquals(Leaf.class.getName(), l_class);
		}

		// Read ref, checkpoint shouldn't get propagated to leaf
		{
			@SuppressWarnings("unused")
			Object o = r.arr[0];

			r_class = r.getClass().getName();
			l_class = l.getClass().getName();

			Assert.assertTrue(r_class.matches(".*CoW.*"));
			Assert.assertEquals(Leaf.class.getName(), l_class);
		}
	}

	static class ArrayRef {
		Leaf[] arr = new Leaf[1];
	}

	static class Ref {
		Leaf o;
	}

	static class Leaf {
		int field = 0;
	}
}