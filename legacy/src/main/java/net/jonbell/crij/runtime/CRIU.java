package net.jonbell.crij.runtime;

public class CRIU {
	/**
	 * Flag set by the JVMTI agent to indicate that it was successfully loaded
	 */
	public static int engaged = 0;

	private static native void _dump();

	private static native void _restore();

	public static void dump()
	{
		_dump();
	}

	public static void restore()
	{
		_restore();
	}

}
