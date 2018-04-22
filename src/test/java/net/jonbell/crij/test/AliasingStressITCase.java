package net.jonbell.crij.test;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;


public class AliasingStressITCase {

	static final int DIMENSION_1 = 10;
	static final int DIMENSION_2 = 5;
	static final int DIMENSION_3 = 2;

	static final int ALIAS_DIMENSION_1 = 5;
	static final int ALIAS_DIMENSION_2 = 2;

	@Test
	public void test() throws InstantiationException, IllegalAccessException
	{
		test(100, new HashMap<Class<? extends Traversable>, Double>(){{
			put(SingleRef.class,  0.25);
			put(SuperRef.class,   0.2);
			put(ArrayRef.class,   0.1);
			put(Array2dRef.class, 0.05);
			put(Array3dRef.class, 0.05);
		}});
	}

	public void test(int count, Map<Class<? extends Traversable>, Double> config) throws InstantiationException, IllegalAccessException
	{
		// Check percentages
		{
			double total = 0.0;
			for (Entry<Class<? extends Traversable>, Double> e : config.entrySet())
				total += e.getValue();

			Assert.assertTrue(total <= 1.0);
		}

		TopLevel top = new TopLevel();
		top.objects = new Traversable[count];

		// Create all objects
		{
			int i = 0;

			for (Entry<Class<? extends Traversable>, Double> e : config.entrySet())
			{
				int n = (int) (count * e.getValue());
				for (int j = 0 ; j < n ; i++ , j++)
					top.objects[i] = e.getKey().newInstance();
			}

			// Add leafs to fill rest
			for (; i < count ; i++)
				top.objects[i] = new Leaf();
		}


		Random rnd = new Random();

		// Ensure all objects are aliased
		for (Traversable t : top.objects)
			while (!t.setAlias(top.objects[rnd.nextInt(count)]));

		// Serialize object graph to a string
		String before = top.toString();

		int version = CheckpointRollbackAgent.getNewVersion();
		// Checkpoint
		((CRIJInstrumented)top).$$crijCheckpoint(version);

		// Mutate random parts of the object graph
		{
			for (Traversable t : top.objects)
				t.reset();

			for (Traversable t : top.objects)
				while (!t.setAlias(top.objects[rnd.nextInt(count)]));
		}

		// Rollback
		version = CheckpointRollbackAgent.getNewVersion();
		((CRIJInstrumented)top).$$crijRollback(version);

		// Serialize object graph to a string
		String after = top.toString();

		// Compare results
		Assert.assertEquals(before, after);
	}
	
}

interface Traversable {
	public boolean setAlias(Object a);

	public void reset();
}

class TopLevel {
	Traversable[] objects;

	@Override
	public String toString() {
		String ret = "";

		for (int i = 0 ; i < objects.length ; i++)
			ret += "" + i + ": " + objects[i] + "\n";

		return ret;
	}
}

class Leaf implements Traversable {
	Object field = new Object();

	@Override
	public boolean setAlias(Object a) {
		return true;
	}

	@Override
	public void reset() {
		field = new Object();
	}

	@Override
	public String toString() {
		return "Leaf : " + System.identityHashCode(this);
	}
}

class SingleRef implements Traversable {
	Object field = new Object();
	Object object;
	
	@Override
	public boolean setAlias(Object a) {
		this.object = a;
		return true;
	}

	@Override
	public void reset() {
		field = new Object();
	}

	@Override
	public String toString() {
		return "SingleRef :" + System.identityHashCode(this) + " [object=" + System.identityHashCode(object) + "]";
	}
}

class SuperRef extends SingleRef {
	@Override
	public String toString() {
		return "SuperRef :" + System.identityHashCode(this) + " [object=" + System.identityHashCode(object) + "]";
	}
}

class ArrayRef implements Traversable {
	final Object[] arr = new Object[AliasingStressITCase.DIMENSION_1];
	int traversal = 0;

	@Override
	public boolean setAlias(Object a) {
		arr[traversal] = a;

		traversal = (traversal + 1) % arr.length;

		return traversal == 0;
	}

	@Override
	public void reset() {
		traversal = 0;
	}

	@Override
	public String toString() {
		String ret = "ArrayRef :" + System.identityHashCode(this) + " [arr=" + System.identityHashCode(arr) + "]";

		for (Object a : arr)
			ret += "\t" + System.identityHashCode(a) + "\n";

		return ret;
	}
}

class Array2dRef implements Traversable {
	Object[][] arr = new Object[AliasingStressITCase.DIMENSION_2][AliasingStressITCase.DIMENSION_1];
	int traversal_x = 0;
	int traversal_y = 0;

	int alias_y = 0;

	@Override
	public boolean setAlias(Object a) {

		if (alias_y < traversal_y && alias_y < AliasingStressITCase.ALIAS_DIMENSION_1)
		{
			if (a instanceof ArrayRef)
			{
				alias_y += 1;
				arr[traversal_y-1] = ((ArrayRef)a).arr;
				return false;
			}
			if (a instanceof Array2dRef && a != this)
			{
				alias_y += 1;
				arr[traversal_y-1] = ((Array2dRef)a).arr[arr.length-1-traversal_y];
				return false;
			}
			if (a instanceof Array3dRef)
			{
				alias_y += 1;
				arr[traversal_y-1] = ((Array3dRef)a).arr[0][arr.length-1-traversal_y];
				return false;
			}
		}

		arr[traversal_y][traversal_x] = a;

		traversal_x = (traversal_x + 1) % arr[0].length;
		if (traversal_x == 0)
		{
			traversal_y = (traversal_y + 1) % arr.length;
			return traversal_y == 0;
		}

		return false;
	}

	@Override
	public void reset() {
		alias_y = 0;
	}

	@Override
	public String toString() {
		String ret = "Array2dRef : " + System.identityHashCode(this) + " [arr=" + System.identityHashCode(arr) + "]\n";

		for (Object[] a : arr)
		{
			ret += "\t" + System.identityHashCode(a) + "\n";
			for (Object b : a)
				ret += "\t\t" + System.identityHashCode(b) + "\n";
		}

		return ret;
	}
}

class Array3dRef implements Traversable {
	Object[][][] arr = new Object[AliasingStressITCase.DIMENSION_3][AliasingStressITCase.DIMENSION_2][AliasingStressITCase.DIMENSION_1];
	int traversal_x = 0;
	int traversal_y = 0;
	int traversal_z = 0;

	int alias_y;
	int alias_z;

	@Override
	public boolean setAlias(Object a) {

		if (alias_y < traversal_y && alias_y < AliasingStressITCase.ALIAS_DIMENSION_1)
		{
			if (a instanceof ArrayRef)
			{
				alias_y += 1;
				arr[traversal_z][traversal_y-1] = ((ArrayRef)a).arr;
				return false;
			}
			if (a instanceof Array2dRef)
			{
				alias_y += 1;
				arr[traversal_z][traversal_y-1] = ((Array2dRef)a).arr[arr[0].length-1-traversal_y];
				return false;
			}
			if (a instanceof Array3dRef && a != this)
			{
				alias_y += 1;
				arr[traversal_z][traversal_y-1] = ((Array3dRef)a).arr[arr.length-1-traversal_z][arr[0].length-1-traversal_y];
				return false;
			}
		} else if (alias_z < traversal_z && alias_z < AliasingStressITCase.ALIAS_DIMENSION_2)
		{
			if (a instanceof Array2dRef)
			{
				alias_z += 1;
				arr[traversal_z-1] = ((Array2dRef)a).arr;
				return false;
			}
			if (a instanceof Array3dRef && a != this)
			{
				alias_z += 1;
				arr[traversal_z-1] = ((Array3dRef)a).arr[arr.length-1-traversal_z];
				return false;
			}
		}


		arr[traversal_z][traversal_y][traversal_x] = a;
		
		traversal_x = (traversal_x + 1) % arr[0][0].length;
		if (traversal_x == 0)
		{
			traversal_y = (traversal_y + 1) % arr[0].length;
			if (traversal_y == 0)
			{
				traversal_z = (traversal_z + 1) % arr.length;
				return traversal_z == 0;
			}

		}
		
		return false;
	}

	@Override
	public void reset() {
		alias_y = 0;
		alias_z = 0;
		
		traversal_x = 0;
		traversal_y = 0;
		traversal_z = 0;
	}

	@Override
	public String toString() {
		String ret = "Array3dRef : " + System.identityHashCode(this) + " [arr=" + System.identityHashCode(arr) + "]\n";
		
		for (Object[][] a : arr)
		{
			ret += "\t" + System.identityHashCode(a) + "\n";
			for (Object[] b : a)
			{
				ret += "\t\t" + System.identityHashCode(b) + "\n";
				for (Object c : b)
					ret += "\t\t\t" + System.identityHashCode(c) + "\n";
				
			}
		}
		
		return ret;
	}
}
