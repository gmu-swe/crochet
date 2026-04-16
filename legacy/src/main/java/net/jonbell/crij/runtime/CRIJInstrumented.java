package net.jonbell.crij.runtime;

public interface CRIJInstrumented {
	public void $$CRIJcopyFieldsTo(Object to);
	public void $$CRIJpropagateCheckpoint(int version);
	public void $$CRIJpropagateRollback(int version);
	public void $$crijRollback(int version);
	public void $$crijCheckpoint(int version);
	public void $$CRIJcopyFieldsFrom(Object old);
	public int $$CRIJgetVersion();
	public void $$CRIJsetVersion(int version);
	public void $$crijAccess();
	public boolean $$crijIsRollbackState();
}
