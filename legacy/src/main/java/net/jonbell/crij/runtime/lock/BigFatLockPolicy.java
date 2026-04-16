package net.jonbell.crij.runtime.lock;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.Tagger;

public class BigFatLockPolicy implements LockPolicy {

	private static Lock bigFatLock = new ReentrantLock();

	private static final int INVAL = -1;

	@Override
	public int lock(CRIJInstrumented obj)
	{
		CheckpointRollbackAgent.u.monitorEnter(Tagger.lockPolicy);
//		bigFatLock.lock();
		if (obj instanceof CRIJInstrumented)
			return ((CRIJInstrumented)obj).$$CRIJgetVersion();
		else
			return INVAL;
	}

	@Override
	public void unlock(CRIJInstrumented obj, int version)
	{
//		bigFatLock.unlock();
		if (obj instanceof CRIJInstrumented)
			((CRIJInstrumented)obj).$$CRIJsetVersion(version);
		CheckpointRollbackAgent.u.monitorExit(Tagger.lockPolicy);
	}

	@Override
	public void lockThread(Thread t)
	{
		CheckpointRollbackAgent.u.monitorEnter(Tagger.lockPolicy);
//		bigFatLock.lock();
	}

	@Override
	public void unlockThread(Thread t)
	{
		CheckpointRollbackAgent.u.monitorExit(Tagger.lockPolicy);
//		bigFatLock.unlock();
	}

}
