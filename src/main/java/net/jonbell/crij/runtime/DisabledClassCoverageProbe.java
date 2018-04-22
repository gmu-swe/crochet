package net.jonbell.crij.runtime;

public class DisabledClassCoverageProbe extends ClassCoverageProbe{

	public DisabledClassCoverageProbe(Class<?> c) {
		super(c);
	}
	@Override
	public void hit() {
	}
}
