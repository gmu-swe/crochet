package net.jonbell.crij.test;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;

public class LinkedListITCase {

	@Test
	public void testSizeOne() throws Exception {
		System.out.print("Testing list of size one, no nesting: ");
		testSize(1, 0);
		System.out.print("Testing list of size one, nesting one: ");
		testSize(1, 1);
		System.out.print("Testing list of size one, nesting two: ");
		testSize(1, 2);
	}
	@Test
	public void testSizeTwo() throws Exception {
		System.out.print("Testing list of size two, no nesting: ");
		testSize(2, 0);
		System.out.print("Testing list of size two, nesting one: ");
		testSize(2, 1);
		System.out.print("Testing list of size two, nesting two: ");
		testSize(2, 2);
	}
	@Test
	public void testSizeThree() throws Exception {
		System.out.print("Testing list of size three, no nesting: ");
		testSize(3, 0);
		System.out.print("Testing list of size three, nesting one: ");
		testSize(3, 1);
		System.out.print("Testing list of size three, nesting two: ");
		testSize(3, 2);
	}
	@Test
	public void testSizeFour() throws Exception {
		System.out.print("Testing list of size four, no nesting: ");
		testSize(4, 0);
		System.out.print("Testing list of size four, nesting one: ");
		testSize(4, 1);
		System.out.print("Testing list of size four, nesting two: ");
		testSize(4, 2);
	}
	void testIter(int size, int iter, int nesting)
	{
		test(size, iter, iter+1, nesting, true);
	}
	void testSize(int size, int nesting)
	{
		test(size, 0, Integer.MAX_VALUE, nesting, false);
	}
	void test(int size, int from, int to, int nesting, boolean describe)
	{
		boolean success = true;
		for (int i = from ; i < to ; i++)
		{
			Node lst = createList(size);
			String str = lst.toString();
			System.out.print(" " + i);
			try {
				str = lst.toString();
				int version = CheckpointRollbackAgent.getNewVersion();
				((CRIJInstrumented)lst).$$crijCheckpoint(version);
				boolean res = iterate(lst, i, describe);
				for (int j = 1 ; j < nesting ; j++)
				{
					str = lst.toString();
					version = CheckpointRollbackAgent.getNewVersion();
					((CRIJInstrumented)lst).$$crijCheckpoint(version);
					iterate(lst, i, describe);
				}
				version = CheckpointRollbackAgent.getNewVersion();
				((CRIJInstrumented)lst).$$crijRollback(version);
				if (!str.equals(lst.toString())) {
					System.out.print("M");
					if (describe)
						System.out.println("List after rollback: " + lst.toString());
					success = false;
				}
				if (!res)
					break;
			} catch (Throwable t) {
				System.out.println("F");
				success = false;
				t.printStackTrace();
			}
		}
		System.out.println();
		assertTrue(success);
	}
	static boolean iterate(Node list, int id, boolean describe)
	{
		Node next = list.next;

		switch (id % 5)
		{
		case 0:
			if (describe)
				System.out.println("Skip");
			break;
		case 1:
			if (describe)
				System.out.println("Read");
			list.readData();
			break;
		case 2:
			if (describe)
				System.out.println("Mutate");
			list.mutateData();
			break;
		case 3:
			if (describe)
				System.out.println("Add");
			list.addNext();
			break;
		case 4:
			if (describe)
				System.out.println("Remove");
			list.removeNext();
			next = list.next;
			break;
		default:
			throw new Error("Unreachable");
		}

		id = id / 5;

		if (next == null)
			return id == 0;
		else
			return iterate(next, id, describe);

	}
	static Node createList(int size)
	{
		Node first = null;
		Node last  = null;

		for (int i = 0 ; i < size ; i++) {
			Node n = new Node();
			n.data = Integer.toString(i);
			if (first == null)
			{
				first = last = n;
			}
			else
			{
				last.next = n;
				last = n;
			}
		}

		return first;
	}
	static Node createList(int size, Node lst)
	{
		if (size == 0)
		{
			return lst;
		}
		else
		{
			Node n = new Node();
			n.data = Integer.toString(size);
			n.next = lst;
			return createList(size - 1, n);
		}
	}
	static class Node
	{
		String data;
		Node next;

		@Override
		public String toString() {
			return data + " " + (next == null ? "" : next.toString());
		}
		public String readData()
		{
			return data;
		}
		public void mutateData()
		{
			data = data + "_modified";
		}
		public void addNext()
		{
			Node n = new Node();
			n.data = data + "_next";
			n.next = next;
			next = n;
		}
		public void removeNext()
		{
			if (next != null)
				next = next.next;
		}
	}
}
