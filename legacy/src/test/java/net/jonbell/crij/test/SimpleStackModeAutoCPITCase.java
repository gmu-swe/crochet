package net.jonbell.crij.test;

import static org.junit.Assert.*;

import org.junit.Ignore;
import org.junit.Test;

import net.jonbell.crij.runtime.CheckpointRollbackAgent;

public class SimpleStackModeAutoCPITCase {

	@Test
	public void testRBInSameMethod() throws Exception {
		int START_VERSION = CheckpointRollbackAgent.getCurrentVersion();
		int x = 100;
		
//		CheckpointRollbackAgent.checkpointFrame(); //checkpoint will be inserted by agent, #6
		int curVersion = CheckpointRollbackAgent.getCurrentVersion();
		assertEquals(100, x);
		x = 10;
		assertEquals(10, x);
		if(curVersion <= START_VERSION + 1) //Only checkpoint once
		{
			System.out.println("Doing an RB");
//			CheckpointRollbackAgent.rollbackFrame(); //rollback will be inserted by agent, #34
		}
		assertEquals(10, x);
		assertTrue(curVersion >= START_VERSION  + 2);
		System.out.println("Version: " + curVersion);
	}
	
	@Test
	public void testRBInOtherMethod() throws Exception {
		int START_VERSION = CheckpointRollbackAgent.getCurrentVersion();
		int x = 100;
//		CheckpointRollbackAgent.checkpointFrame(); //checkpoint will be inserted by agent
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
		
//		CheckpointRollbackAgent.checkpointFrame();
		int curVersion = CheckpointRollbackAgent.getCurrentVersion();
		assertEquals(100, x);
		x = 10;
		assertEquals(10, x);
		if(curVersion <= START_VERSION + 1) //Only checkpoint once
		{
			System.out.println("RB'ed");
//			CheckpointRollbackAgent.rollbackFrame(); //checkpoint will be inserted by agent
		}
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
//		CheckpointRollbackAgent.checkpointFrame();
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
//		CheckpointRollbackAgent.rollbackFrame(); //rollback will be inserted by agent
	}
	void foo()
	{
//		CheckpointRollbackAgent.rollbackFrame(); //rollback will be inserted by agent
	}
}
