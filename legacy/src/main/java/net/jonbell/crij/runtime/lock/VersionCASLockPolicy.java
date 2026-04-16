package net.jonbell.crij.runtime.lock;

import java.lang.reflect.Field;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.jonbell.crij.instrument.CheckpointRollbackFieldCV;
import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.Tagger;

public class VersionCASLockPolicy implements LockPolicy {

	private static Lock fallbackLock = new ReentrantLock();
	private static ReadWriteLock threadLock = new ReentrantReadWriteLock();

	public static final int INVAL = -1;
	
	public static long conflicts = 0;
	public static long fast = 0;
	public static long slow = 0;
	public static long unsupported = 0;
	
	private int slowLock(Object obj)
	{
		if (obj instanceof CRIJInstrumented)
		{
			// I'm sure we can optimize these away
			Class c = obj.getClass();
			long versionOffset = c.versionOffset;
			if (versionOffset == 0) {
				throw new Error("No version offset for " + c);
			}
			int version = ((CRIJInstrumented)obj).$$CRIJgetVersion();
			short tid = (short) Thread.currentThread().tid;

			slow++;

			// Conflicts, take the slow path
			if (version < 0 && getTID(version) == tid) {
				// We already own the lock, just increment the cnt
				((CRIJInstrumented)obj).$$CRIJsetVersion(incCount(version));
				return INVAL;
			}

			while (true)
			{
				boolean addConflict = false;

				// Spin with volatile memory operations until something changes
				do {
					version = CheckpointRollbackAgent.u.getIntVolatile(obj, versionOffset);
				} while (version < 0);

				// Try to CAS
				int lockedVersion = incCount(getLockedVersion(tid));
				boolean success = CheckpointRollbackAgent.u.compareAndSwapInt(obj, versionOffset, version, lockedVersion);

				// Got the lock?
				if (success) {
					if (addConflict)
						conflicts++;
					return version;
				}

				addConflict = true;
			}
		} else {
			CheckpointRollbackAgent.u.monitorEnter(Tagger.lockPolicy);
			unsupported++;
//			fallbackLock.lock();
			return INVAL;
		}
	}

	@Override
	public int lock(CRIJInstrumented obj)
	{
//		threadLock.readLock().lock();
//		if (obj instanceof CRIJInstrumented)
//		{
//			Class c = obj.getClass();
			long versionOffset = obj.getClass().versionOffset;
//			if (versionOffset == 0) {
//				throw new Error("No version offset for " + c);
//			}
			int version = CheckpointRollbackAgent.u.getInt(obj, versionOffset);
			long tid = Thread.currentThread().tid;
			if (version > 0 && CheckpointRollbackAgent.u.compareAndSwapInt(obj, versionOffset, version, (int) tid | ONE_COUNT_MASK))
			{
				// Got it, the lock is ours
					return version;
				
			}
			else if (version < 0 && getTID(version) == tid) {
				//reentrant case
				return INVAL;
			}

//		}

		return slowLock(obj);
	}

	@Override
	public void unlock(CRIJInstrumented obj, int version)
	{
			Class c = obj.getClass();
			long versionOffset = c.versionOffset;
			int v = CheckpointRollbackAgent.u.getInt(obj, versionOffset);
			short tid = (short) Thread.currentThread().tid;

			int lockedVersionOne = tid | ONE_COUNT_MASK;

			if (v == lockedVersionOne)
			{
				// Fast path
				CheckpointRollbackAgent.u.putInt(obj, versionOffset, version);
				return;
			}

		slowUnlock(obj, version);
	}
	
	private static void slowUnlock(Object obj, int version)
	{
//		threadLock.readLock().unlock();
		if (obj instanceof CRIJInstrumented)
		{
			short tid = (short) Thread.currentThread().tid;
			int lockedVersion = ((CRIJInstrumented)obj).$$CRIJgetVersion();
			if (lockedVersion >= 0 || getTID(lockedVersion) != tid) {
				// This is not locked or we don't own the lock
				throw new Error("Bad unlock");
			}

			lockedVersion = decCount(lockedVersion);
			if (lockedVersion == getLockedVersion(tid))
			{
				// Unlock with normal memory operations should be fine
				// because the CAS already orders memory through a fence (in X86)
				((CRIJInstrumented)obj).$$CRIJsetVersion(version);
			} else {
				((CRIJInstrumented)obj).$$CRIJsetVersion(lockedVersion);
			}
		} else {
			CheckpointRollbackAgent.u.monitorExit(Tagger.lockPolicy);
//			fallbackLock.unlock();
		}
	}

	@Override
	public void lockThread(Thread t)
	{
//		threadLock.writeLock().lock();
	}

	@Override
	public void unlockThread(Thread t)
	{
//		threadLock.writeLock().unlock();
	}
	
	private static int getLockedVersion(short tid)
	{
		return Integer.MIN_VALUE | tid;
	}
	
	private static int ONE_COUNT_MASK   = 0x8001_0000;
	private static int COUNT_MASK       = 0x7FFF_0000;
	private static int VERSION_MASK     = 0x0000_FFFF;

	private static int oneCountLockedVersion(int version)
	{
		return version | ONE_COUNT_MASK;
	}

	private static int incCount(int version)
	{
		short count = (short) ((version & COUNT_MASK) >> 16);
		count += 1;
		int ret = version & (~COUNT_MASK) | (count << 16);
		return ret;
	}

	private static int decCount(int version)
	{
		short count = (short) ((version & COUNT_MASK) >> 16);
		count -= 1;

		if (count < 0)
			throw new Error("Too many unlocks");

		int ret = version & (~COUNT_MASK) | (count << 16);
		return ret;
	}

	private static int getTID(int version)
	{
		return version & VERSION_MASK;
	}

}
