package net.jonbell.crij.runtime;

public class ClassCoverageProbe {

	private final Class<?> c;

	public ClassCoverageProbe(Class<?> c) {
		this.c = c;
	}

	public Class<?> getC() {
		return c;
	}

	public void hit() {
		RootCollector.collectClass(this);
	}
}
