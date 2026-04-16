package net.jonbell.crij.test;

import static org.junit.Assert.*;

import org.junit.Test;

import net.jonbell.crij.runtime.CheckpointRollbackAgent;

public class ThreadGroupITCase {

//	@Test
	public void testAnotherTest() throws Exception {
		
	}
//	@Test
	public void testThreadGroupGetsPropogated() throws Exception {
		CheckpointRollbackAgent.checkpointAllRoots();
		CheckpointRollbackAgent.rollbackAllRoots();
		CheckpointRollbackAgent.checkpointAllRoots();
		CheckpointRollbackAgent.rollbackAllRoots();
		CheckpointRollbackAgent.checkpointAllRoots();
		CheckpointRollbackAgent.rollbackAllRoots();
		CheckpointRollbackAgent.checkpointAllRoots();
		CheckpointRollbackAgent.rollbackAllRoots();
	}
}
