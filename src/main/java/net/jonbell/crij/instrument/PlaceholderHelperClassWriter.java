package net.jonbell.crij.instrument;

import java.util.HashSet;
import java.util.LinkedList;

import org.objectweb.asm.ClassWriter;

public class PlaceholderHelperClassWriter extends ClassWriter {

	public PlaceholderHelperClassWriter(int flags) {
		super(flags);
	}

	private int place;
	public int getPlace() {
		return place;
	}
	private int place2;
	public int getPlace2() {
		return place2;
	}
	private LinkedList<Integer> interfacePlaces = new LinkedList<Integer>();
	public LinkedList<Integer> getInterfacePlaces() {
		return interfacePlaces;
	}
	@Override
	public int newClass(String value) {
		return super.newClass(value);
	}

	@Override
	public int newUTF8(String value) {
		int ret = super.newUTF8(value);
		if ("Lnet/jonbell/PlaceHolder".equals(value)) {
			place = ret;
		} else if ("net/jonbell/PlaceHolder2".equals(value)) {
			place2 = ret;
		} else if (value != null && value.startsWith("net/jonbell/IFacePlaceHolder")) {
			interfacePlaces.add(ret);
		}
		return ret;
	}
	
}
