package net.jonbell.crij.test;

import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.*;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;

import net.jonbell.crij.runtime.CRIJCoW;
import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CRIJSFHelper;
import net.jonbell.crij.runtime.CRIJSlowCheckpoint;
import net.jonbell.crij.runtime.CRIJSlowRollback;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.RootCollector;


/**
 * SF root collection (which this tries to test) is not used...
 *
 */
@Ignore
public class StartCheckpointITCase {
	
	public static void main(String[] args) throws Throwable
	{
		new StartCheckpointITCase().testCollectReferences();
	}

	@Test
	public void testCollectReferences()
	{
		Root root = new Root();
		Root anotherRoot = new Root();
		Leaf initial = new Leaf(10);
		Leaf modified = new Leaf(20);

		root.leaf = initial;
		anotherRoot.leaf = initial;

		SoftReference<Root> ref = new SoftReference<>(anotherRoot);

		// Collect roots
		RootCollector.startCollectingRoots();
		RootCollector.collectObject((CRIJInstrumented)root);
		RootCollector.collectObject((CRIJInstrumented)ref);

		// Checkpoint
		int version = CheckpointRollbackAgent.getNewVersion();
		RootCollector.checkpointCollectedRoots(version);

		// Everything checkpointed as expected
		assertTrue(root instanceof CRIJSlowCheckpoint);
		assertTrue(ref instanceof CRIJSlowCheckpoint);
		assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		assertEquals(version, ((CRIJInstrumented)ref).$$CRIJgetVersion());
		assertEquals(Leaf.class, initial.getClass());
		assertEquals(Leaf.class, modified.getClass());

		// Modify through root
		root.leaf.state = 30;
		root.leaf = modified;

		// Everything is in the expected state
		assertEquals(initial, ((Root)CheckpointRollbackAgent.getOld(root)).leaf);

		// Rollback
		version = CheckpointRollbackAgent.getNewVersion();
		RootCollector.rollbackCollectedRoots(version);

		// Everything rolled-back as expected
		assertTrue(root instanceof CRIJSlowRollback);
		assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		assertTrue(ref instanceof CRIJSlowRollback);
		assertEquals(version, ((CRIJInstrumented)ref).$$CRIJgetVersion());

		// Checkpoint
		version = CheckpointRollbackAgent.getNewVersion();
		RootCollector.checkpointCollectedRoots(version);

		// Modify through ref
		ref.get().leaf.state = 30;
		ref.get().leaf = modified;

		// Everything checkpointed as expected
		assertTrue(root instanceof CRIJSlowCheckpoint);
		assertEquals(Root.class, anotherRoot.getClass());
//		assertTrue(ref instanceof CRIJCoW);
		assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		assertEquals(version, ((CRIJInstrumented)ref).$$CRIJgetVersion());
		assertEquals(version, ((CRIJInstrumented)anotherRoot).$$CRIJgetVersion());
		assertEquals(Leaf.class, initial.getClass());
		assertEquals(Leaf.class, modified.getClass());
		assertEquals(initial, ((Root)CheckpointRollbackAgent.getOld(anotherRoot)).leaf);

		// Rollback
		version = CheckpointRollbackAgent.getNewVersion();
		RootCollector.rollbackCollectedRoots(version);

		// Everything is as initially
		assertEquals(root.leaf, initial);
		assertEquals(ref.get().leaf, initial);
		assertEquals(root.leaf.state, 10);
	}

	@Test
	public void testCollectThreadLocals()
	{
		final Root root = new Root();
		final Root anotherRoot = new Root();
		Leaf initial = new Leaf(10);
		Leaf modified = new Leaf(20);

		root.leaf = initial;
		anotherRoot.leaf = initial;

		ThreadLocal<Root> threadLocalRoot = new ThreadLocal<Root>(){
			@Override
			protected Root initialValue() {
				return anotherRoot;
			}
		};

		int version = CheckpointRollbackAgent.getNewVersion();
		// Collect root
		RootCollector.startCollectingRoots();
		RootCollector.collectObject((CRIJInstrumented)root);
		RootCollector.collectObject((CRIJInstrumented)threadLocalRoot);

		// Checkpoint
		RootCollector.checkpointCollectedRoots(version);

		// Everything checkpointed as expected
		assertTrue(root instanceof CRIJSlowCheckpoint);
		assertTrue(threadLocalRoot instanceof CRIJSlowCheckpoint);
		assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		assertEquals(version, ((CRIJInstrumented)threadLocalRoot).$$CRIJgetVersion());
		assertEquals(Leaf.class, initial.getClass());
		assertEquals(Leaf.class, modified.getClass());

		// Modify through root
		root.leaf.state = 30;
		root.leaf = modified;

		// Everything is in the expected state
		assertEquals(initial, ((Root)CheckpointRollbackAgent.getOld(root)).leaf);

		// Rollback
		version = CheckpointRollbackAgent.getNewVersion();
		RootCollector.rollbackCollectedRoots(version);

		// Everything rolled-back as expected
		assertTrue(root instanceof CRIJSlowRollback);
		assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		assertTrue(threadLocalRoot instanceof CRIJSlowRollback);
		assertEquals(version, ((CRIJInstrumented)threadLocalRoot).$$CRIJgetVersion());

		// Checkpoint
		version = CheckpointRollbackAgent.getNewVersion();
		RootCollector.checkpointCollectedRoots(version);

		// Modify through threadlocal
		threadLocalRoot.get().leaf.state = 30;
		threadLocalRoot.get().leaf = modified;

		// Everything checkpointed as expected
		assertTrue(root instanceof CRIJSlowCheckpoint);
		assertEquals(Root.class, anotherRoot.getClass());
		assertTrue(threadLocalRoot instanceof CRIJCoW);
		assertEquals(version, ((CRIJInstrumented)root).$$CRIJgetVersion());
		assertEquals(version, ((CRIJInstrumented)threadLocalRoot).$$CRIJgetVersion());
		assertEquals(version, ((CRIJInstrumented)anotherRoot).$$CRIJgetVersion());
		assertEquals(Leaf.class, initial.getClass());
		assertEquals(Leaf.class, modified.getClass());
		assertEquals(initial, ((Root)CheckpointRollbackAgent.getOld(anotherRoot)).leaf);

		// Rollback
		version = CheckpointRollbackAgent.getNewVersion();
		RootCollector.rollbackCollectedRoots(version);

		// Everything is as initially
		assertEquals(root.leaf, initial);
		assertEquals(threadLocalRoot.get().leaf, initial);
		assertEquals(root.leaf.state, 10);
	}

	@Test
	public void testCollectAllCorrectRoots() throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException
	{
		Leaf initial = new Leaf(10);
		Leaf modified = new Leaf(20);
		Root root = new Root();
		Root.rootReference = root;

		int version = CheckpointRollbackAgent.getNewVersion();

		// Collect root
		RootCollector.startCollectingRoots();
		Root.rootReference.leaf = initial;

		// Checkpoint
		RootCollector.checkpointCollectedRoots(version);

		// Everything checkpointed as expected
		CRIJSFHelper sfHelper = (CRIJSFHelper) Root.class.getDeclaredMethod("$$CRIJGetSFHelper",new Class[0]).invoke(new Object[0]);
		assertTrue(sfHelper instanceof CRIJSlowCheckpoint);
		assertEquals(version, sfHelper.$$CRIJgetVersion());
		assertEquals(Root.class, root.getClass());

		// Modify
		Root.rootReference.leaf.state = 30;
		Root.rootReference.leaf = modified;

		// Everything is in the expected state
		assertTrue(sfHelper instanceof CRIJCoW);
		assertEquals(Root.class, root.getClass());
		assertEquals(initial, ((Root)CheckpointRollbackAgent.getOld(root)).leaf);

		// Rollback
		version = CheckpointRollbackAgent.getNewVersion();
		RootCollector.rollbackCollectedRoots(version);

		// Everything rolled-back as expected
		assertTrue(sfHelper instanceof CRIJSlowRollback);
		assertEquals(version, sfHelper.$$CRIJgetVersion());

		// Access modified state
		assertEquals(initial, Root.rootReference.leaf);
		assertEquals(initial.state, 10);
	}

	@Test
	public void testCollectMissingRoot() throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException
	{
		Leaf initial = new Leaf(10);
		Leaf modified = new Leaf(20);
		Root root = new Root();
		Root.rootReference = root;

		int version = CheckpointRollbackAgent.getNewVersion();

		// Set up aliasing
		AnotherRoot.rootReference = new Root();
		AnotherRoot.rootReference.leaf = initial;

		// Collect root
		RootCollector.startCollectingRoots();
		Root.rootReference.leaf = initial;

		// Checkpoint
		RootCollector.checkpointCollectedRoots(version);

		// Modify through illegal alias
		AnotherRoot.rootReference.leaf.state = 100;

		// Modify through legal alias
		Root.rootReference.leaf.state = 30;
		Root.rootReference.leaf = modified;

		// Rollback
		version = CheckpointRollbackAgent.getNewVersion();
		RootCollector.rollbackCollectedRoots(version);

		// Everything rolled-back as expected
		CRIJSFHelper sfHelper = (CRIJSFHelper) Root.class.getDeclaredMethod("$$CRIJGetSFHelper",new Class[0]).invoke(new Object[0]);
		assertTrue(sfHelper instanceof CRIJSlowRollback);
		assertEquals(version, sfHelper.$$CRIJgetVersion());

		// Access modified state
		assertEquals(initial, Root.rootReference.leaf);
		assertEquals(100, Root.rootReference.leaf.state); // This fails because 100 <> 10
	}

	public static class Root
	{
		public static Root rootReference;
		public Leaf leaf;
	}

	public static class Leaf
	{
		public int state;

		public Leaf(int state) {
			this.state = state;
		}
	}

	public static class AnotherRoot
	{
		public static Root rootReference;
	}

}