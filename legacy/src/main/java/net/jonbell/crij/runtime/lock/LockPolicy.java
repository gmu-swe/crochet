package net.jonbell.crij.runtime.lock;

import net.jonbell.crij.runtime.CRIJInstrumented;

public interface LockPolicy {
	
	public int lock(CRIJInstrumented obj);
	
	public void unlock(CRIJInstrumented obj, int version);

	public void lockThread(Thread t);
	
	public void unlockThread(Thread t);
}
