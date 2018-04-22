package net.jonbell.crij.runtime.lock;

import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;

public class DummyLockPolicy implements LockPolicy {

	private static final int INVAL = -1;

	@Override
	public int lock(CRIJInstrumented obj) {
		if (obj instanceof CRIJInstrumented)
		{
			long versionOffset = obj.getClass().versionOffset;

			int v = CheckpointRollbackAgent.u.getInt(obj, versionOffset);
			return v;
//			return ((CRIJInstrumented)obj).$$CRIJgetVersion();
		}
		else
			return INVAL;
	}

	@Override
	public void unlock(CRIJInstrumented obj, int version) {
		long versionOffset = obj.getClass().versionOffset;

		CheckpointRollbackAgent.u.putInt(obj, versionOffset, version);
//		obj.$$CRIJsetVersion(version);
	}

	@Override
	public void lockThread(Thread t) {
		// TODO Auto-generated method stub

	}

	@Override
	public void unlockThread(Thread t) {
		// TODO Auto-generated method stub

	}

}
