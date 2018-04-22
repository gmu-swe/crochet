package net.jonbell.crij.test;

import java.util.Arrays;


import org.junit.Test;

import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import static org.junit.Assert.*;

public class ArrayCheckpointRollbackITCase {

	@Test
	public void testPrimitiveArray() {
		PrimitiveArray obj = new PrimitiveArray();
		String objStr = Arrays.toString(obj.numbers);
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);
		obj.numbers[1] = 40;
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijRollback(version);
		assertEquals(objStr, Arrays.toString(obj.numbers));
	}
	
	@Test
	public void testPrimitiveArrayDoubleCheckpoint() {
		PrimitiveArray obj = new PrimitiveArray();
		String objStr = Arrays.toString(obj.numbers);
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);
		
		obj.numbers[1] = 40;
		String objStr2 = Arrays.toString(obj.numbers);
		version = CheckpointRollbackAgent.getNewVersion(); //version is "rollback"
		version = CheckpointRollbackAgent.getNewVersion(); //bc we use even/odd version #s
		
		((CRIJInstrumented)obj).$$crijCheckpoint(version);
		
		obj.numbers[1] = 50;
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijRollback(version);
		assertEquals(objStr2, Arrays.toString(obj.numbers));
		
		version = CheckpointRollbackAgent.getNewVersion(); //version is "rollback"
		version = CheckpointRollbackAgent.getNewVersion(); //bc we use even/odd version #s
		
		((CRIJInstrumented)obj).$$crijRollback(version);
		assertEquals(objStr2, Arrays.toString(obj.numbers));
	}

	@Test
	public void testObjectArray() {
		ObjectArray obj = new ObjectArray();
		String objStr = Arrays.toString(obj.numbers);
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);
		obj.numbers[1] = new Integer(40);
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijRollback(version);
		assertEquals(objStr, Arrays.toString(obj.numbers));
	}

	@Test
	public void testMultidimensionalArray() {
		MultiDimArray obj = new MultiDimArray();
		String objStr = Arrays.deepToString(obj.matrix);
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);
		obj.matrix[0][0] = 100;
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented) obj).$$crijRollback(version);
		assertEquals(objStr, Arrays.deepToString(obj.matrix));

	}

	/**
	 * Should fail because inner was never included in the checkpoint.
	 */
	@Test(expected=AssertionError.class)
	public void testMultidimensionalArrayWithAliasingAndDanglingPointer() {
		MultiDimArray obj = new MultiDimArray();
		String objStr = Arrays.deepToString(obj.matrix);
		int[] inner = obj.matrix[0];
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);
		inner[0] = 100;
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented) obj).$$crijRollback(version);
		assertEquals(objStr, Arrays.deepToString(obj.matrix));
	}

	@Test
	public void testMultidimensionalArrayWithAliasing() {
		MultiDimArray obj = new MultiDimArray();
		String objStr = Arrays.deepToString(obj.matrix);
		obj.otherArray = obj.matrix[0];
		int version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)obj).$$crijCheckpoint(version);
		obj.otherArray[0] = 100;
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented) obj).$$crijRollback(version);
		assertEquals(objStr, Arrays.deepToString(obj.matrix));
	}
	
	static class PrimitiveArray {
		int [] numbers = new int[]{ 10, 20, 30 };
		boolean otherField = false;
	}

	static class ObjectArray {
		Integer [] numbers = new Integer[] { new Integer(10), new Integer(20), new Integer(30) };
		boolean otherField = false;
	}

	static class MultiDimArray {
		int [][] matrix = new int[3][3];
		int[] otherArray;
		boolean otherField = false;
	}
}
