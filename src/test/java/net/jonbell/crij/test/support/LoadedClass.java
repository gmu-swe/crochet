package net.jonbell.crij.test.support;

import org.junit.Ignore;

import net.jonbell.crij.runtime.CheckpointRollbackAgent;

@Ignore
public class LoadedClass {
	public int i;
	public static void run()
	{
		CheckpointRollbackAgent.checkpointAllRoots();
		Loaded2 t = new Loaded2();
		CheckpointRollbackAgent.rollbackAllRoots();
		Loaded3 t2 = new Loaded3();
	}
	static class Loaded2{
		int n;
	}
	static class Loaded3{
		int n;
	}
}
