package net.jonbell.crij.test;

import static org.junit.Assert.*;

import org.junit.Test;

import net.jonbell.crij.runtime.CheckpointRollbackAgent;

public class SimpleStackModeITCase {

	@Test
	public void testRBInSameMethod() throws Exception {
		int START_VERSION = CheckpointRollbackAgent.getCurrentVersion();
		int x = 100;
		
		CheckpointRollbackAgent.checkpointFrame();
		int curVersion = CheckpointRollbackAgent.getCurrentVersion();
		assertEquals(100, x);
		x = 10;
		assertEquals(10, x);
		if(curVersion <= START_VERSION + 1) //Only checkpoint once
			CheckpointRollbackAgent.rollbackFrame();
		assertEquals(10, x);
		assertTrue(curVersion >= START_VERSION  + 2);
	}
	
	@Test
	public void testRBInOtherMethod() throws Exception {
		int START_VERSION = CheckpointRollbackAgent.getCurrentVersion();
		int x = 100;
		CheckpointRollbackAgent.checkpointFrame();
		int curVersion = CheckpointRollbackAgent.getCurrentVersion();
		assertEquals(100, x);
		x = 10;
		assertEquals(10, x);
		if(curVersion <= START_VERSION + 1) //Only checkpoint once
			foo();
		assertEquals(10, x);
		assertTrue(curVersion >= START_VERSION  + 2);

	}
	static void doStaticTest(){
		int START_VERSION = CheckpointRollbackAgent.getCurrentVersion();
		int x = 100;
		
		CheckpointRollbackAgent.checkpointFrame();
		int curVersion = CheckpointRollbackAgent.getCurrentVersion();
		assertEquals(100, x);
		x = 10;
		assertEquals(10, x);
		if(curVersion <= START_VERSION + 1) //Only checkpoint once
			CheckpointRollbackAgent.rollbackFrame();
		assertEquals(10, x);
		assertTrue(curVersion >= START_VERSION  + 2);
	}
	@Test
	public void testRBInSameStaticMethod() throws Exception {
		doStaticTest();
	}
	
	@Test
	public void testRBInOtherStaticMethod() throws Exception {
		int START_VERSION = CheckpointRollbackAgent.getCurrentVersion();
		int x = 100;
		CheckpointRollbackAgent.checkpointFrame();
		int curVersion = CheckpointRollbackAgent.getCurrentVersion();
		assertEquals(100, x);
		x = 10;
		assertEquals(10, x);
		if(curVersion <= START_VERSION + 1) //Only checkpoint once
			foos();
		assertEquals(10, x);
		assertTrue(curVersion >= START_VERSION  + 2);

	}
	static void foos()
	{
		CheckpointRollbackAgent.rollbackFrame();
	}
	void foo()
	{
		CheckpointRollbackAgent.rollbackFrame();
	}
}
