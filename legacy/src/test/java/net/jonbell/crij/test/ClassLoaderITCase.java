package net.jonbell.crij.test;

import static org.junit.Assert.*;

import java.net.URL;
import java.net.URLClassLoader;

import net.jonbell.crij.runtime.CRIJInstrumented;

import org.junit.Ignore;
import org.junit.Test;

/**
 * This test requires stack support to be enabled, which we skip by default.
 */
@Ignore
public class ClassLoaderITCase {

	@Test
	public void testClassLoader() throws Exception {
		Loader l = new Loader(new URL[]{ClassLoaderITCase.class.getProtectionDomain().getCodeSource().getLocation()});
		System.out.println(l);
		Class c = l.loadClass("net.jonbell.crij.test.support.LoadedClass");
		l = null;
		c.getMethod("run").invoke(null);
	}
	public static void main(String[] args) throws Throwable {
		new ClassLoaderITCase().testClassLoader();
	}

	static class Loader extends URLClassLoader {

		public Loader(URL[] urls) {
			super(urls);
		}

		int i = 0;
		@Override
		public Class<?> loadClass(String name) throws ClassNotFoundException {
			System.out.println("Invokes: " + i);
			i++;
			System.out.println("Loader version: " + ((CRIJInstrumented)this).$$CRIJgetVersion());
			try {
				return findClass(name);
			} catch (ClassNotFoundException ex) {
				return super.loadClass(name);
			}
		}
	}
}
