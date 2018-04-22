package net.jonbell.crij.test;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.jonbell.crij.runtime.Tagger;
import net.jonbell.crij.runtime.wrapper.ArrayWrapper;

public class TaggingITCase {
	@Test
	public void testArray() throws Exception {

		Object[] arr = new Object[1];
		ArrayWrapper tag = new ArrayWrapper(arr);
		
		Tagger.setTag(arr, tag);
		assertEquals(tag, Tagger.getTag(arr));
	}

	public void testMultiArray() throws Exception {

		Object[][] arr = new Object[2][1];
		ArrayWrapper tag1 = new ArrayWrapper(arr);
		ArrayWrapper tag2 = new ArrayWrapper(arr);
		ArrayWrapper tag3 = new ArrayWrapper(arr);
		
		Tagger.setTag(arr, tag1);
		Tagger.setTag(arr[0], tag2);
		Tagger.setTag(arr[1], tag3);

		assertEquals(tag1, Tagger.getTag(arr));
		assertEquals(tag2, Tagger.getTag(arr[0]));
		assertEquals(tag3, Tagger.getTag(arr[1]));
	}
}
