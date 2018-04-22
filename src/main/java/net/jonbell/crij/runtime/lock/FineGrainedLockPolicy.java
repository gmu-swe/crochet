package net.jonbell.crij.runtime.lock;

import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.Tagger;

public class FineGrainedLockPolicy implements LockPolicy {
	private static final int INVAL = -1;

	@Override
	public int lock(CRIJInstrumented obj) {
		Class<?> c = obj.getClass();
		long lockOffset = c.lockOffset;
		if (lockOffset == 0)
			throw new Error("No lock offset for " + c);
		long versionOffset = c.versionOffset;
		if (versionOffset == 0) {
			throw new Error("No version offset for " + c);
		}
		int version = CheckpointRollbackAgent.u.getIntVolatile(obj, versionOffset);
		// Make sure it's init'ed
		Object lock = CheckpointRollbackAgent.u.getObjectVolatile(obj, lockOffset);
		if (lock == null) {
			lock = new Object();
			boolean success = CheckpointRollbackAgent.u.compareAndSwapObject(obj, lockOffset, null, lock);
			if (!success) {
				lock = CheckpointRollbackAgent.u.getObjectVolatile(obj, lockOffset);
			}
		}
		CheckpointRollbackAgent.u.monitorEnter(lock);
		return version;
	}

	@Override
	public void unlock(CRIJInstrumented obj, int version)
	{
		((CRIJInstrumented) obj).$$CRIJsetVersion(version);
		Class c = obj.getClass();
		long lockOffset = c.lockOffset;
		long versionOffset = c.versionOffset;
		CheckpointRollbackAgent.u.putIntVolatile(obj, versionOffset, version);
		Object lock = CheckpointRollbackAgent.u.getObjectVolatile(obj, lockOffset);
		CheckpointRollbackAgent.u.monitorExit(lock);
	}

	@Override
	public void lockThread(Thread t) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void unlockThread(Thread t) {
		throw new UnsupportedOperationException();
	}

}
