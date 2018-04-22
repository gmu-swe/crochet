package net.jonbell.crij.runtime;

public class RollbackException extends RuntimeException {

	public RollbackException(int v) {
		this.version = v;
	}
	public int version;
	/**
	 * 
	 */
	private static final long serialVersionUID = 1486960037309581236L;

}
