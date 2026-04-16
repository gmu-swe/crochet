package net.jonbell.crij.test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;

import net.jonbell.crij.instrument.CheckpointRollbackStubClassGenerator;
import net.jonbell.crij.instrument.Premain;
import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.CheckpointRollbackAgent.RollbackState;
import net.jonbell.crij.runtime.lock.VersionCASLockPolicy;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import sun.misc.Unsafe;
@Ignore
public class CASLockTest {

	static class Holder implements CRIJInstrumented
	{
		public int $$crijVersion;
		public int counter = 0;

		@Override
		public void $$CRIJcopyFieldsTo(Object to) {
			throw new Error("Not implemented");
		}

		@Override
		public void $$CRIJpropagateCheckpoint(int version) {
			throw new Error("Not implemented");
		}

		@Override
		public void $$CRIJpropagateRollback(int version) {
			throw new Error("Not implemented");
		}

		@Override
		public void $$crijRollback(int version) {
			throw new Error("Not implemented");
		}

		@Override
		public void $$crijCheckpoint(int version) {
			throw new Error("Not implemented");
		}

		@Override
		public void $$CRIJcopyFieldsFrom(Object old) {
			throw new Error("Not implemented");
		}

		@Override
		public int $$CRIJgetVersion() {
			return $$crijVersion;
		}

		@Override
		public void $$CRIJsetVersion(int version) {
			$$crijVersion = version;
		}

		@Override
		public void $$crijAccess() {
			throw new Error("Not implemented");			
		}

		@Override
		public boolean $$crijIsRollbackState() {
			throw new Error("Not implemented");
		}

	}

	@Test
	public void testLockUnlock() throws Exception {
		VersionCASLockPolicy policy = new VersionCASLockPolicy();
		Holder h = new Holder();
		((CRIJInstrumented)h).$$CRIJsetVersion(10);

		int v = policy.lock(h);
		assertEquals(v, 10);
		policy.unlock(h, 10);
		assertEquals(((CRIJInstrumented)h).$$CRIJgetVersion(), 10);
	}

	@Test
	public void testReentrancy() throws Exception {
		VersionCASLockPolicy policy = new VersionCASLockPolicy();
		Holder h = new Holder();
		((CRIJInstrumented)h).$$CRIJsetVersion(10);

		int v = policy.lock(h);
		assertEquals(v, 10);
		policy.lock(h);
		policy.unlock(h, -1);
		policy.unlock(h, 10);
		assertEquals(((CRIJInstrumented)h).$$CRIJgetVersion(), 10);
	}

	@Test(expected=Error.class)
	public void testTooManyUnlocks() throws Exception {
		VersionCASLockPolicy policy = new VersionCASLockPolicy();
		Holder h1 = new Holder();
		Holder h2 = new Holder();
		((CRIJInstrumented)h1).$$CRIJsetVersion(10);

		policy.lock(h2);
		int v = policy.lock(h1);
		assertEquals(v, 10);
		policy.lock(h1);
		policy.unlock(h1, -1);
		policy.unlock(h1, 10);
		policy.unlock(h1, -1);
	}

	@Test()
	public void testMutex() throws Exception {
		VersionCASLockPolicy policy = new VersionCASLockPolicy();
		Holder h = new Holder();
		int n = 10;

		Thread[] threads = new Thread[100];

		for (int i = 0 ; i < threads.length ; i++)
			threads[i] = new Thread() {
				@Override
				public void run() {

					try {
						for (int i = 0 ; i < n ; i++) {
							int v = policy.lock(h);
							h.counter++;
							policy.unlock(h, v);
						}
					} catch (Throwable t) {
						t.printStackTrace();
					}
				}
			};

		for (int i = 0 ; i < threads.length ; i++)
			threads[i].start();

		for (int i = 0 ; i < threads.length ; i++)
			threads[i].join();

		assertEquals(h.counter, threads.length * n);
	}

	public void testWrongUnlock() throws Exception {
		// TODO
	}
}
