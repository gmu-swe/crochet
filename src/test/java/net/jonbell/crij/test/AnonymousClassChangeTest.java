package net.jonbell.crij.test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Arrays;

import net.jonbell.crij.instrument.CheckpointRollbackStubClassGenerator;
import net.jonbell.crij.instrument.Premain;
import net.jonbell.crij.runtime.CRIJInstrumented;
import net.jonbell.crij.runtime.CheckpointRollbackAgent;
import net.jonbell.crij.runtime.CheckpointRollbackAgent.RollbackState;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import sun.misc.Unsafe;

@Ignore
public class AnonymousClassChangeTest implements Cloneable, Serializable {

	@Test
	public void testGeneratesValidAnonClass() throws Exception {
		Class c = CheckpointRollbackAgent.generateClass(Holder.class, 1);
	}

	static class Holder
	{
		int[] ia;
		int i;
		String b;
		long j;
		static long zz;
		static int si;
	}
	
	@Test
	public void testChangeToAnonClass() throws Exception {
		ClassReader cr = new ClassReader("net.jonbell.crij.test.AnonymousClassChangeTest$A");
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);
		cn.fields.clear();
		cn.superName = "net/jonbell/crij/test/AnonymousClassChangeTest$A";
		for(Object o : cn.methods)
		{
			MethodNode mn = (MethodNode) o;
			if(mn.name.equals("foo"))
			{
				AbstractInsnNode ins = mn.instructions.getFirst();
				while(ins != null)
				{
					if(ins instanceof LdcInsnNode)
						((LdcInsnNode) ins).cst = "Anon A foo invoked";
					ins = ins.getNext();
				}
			}
			if(mn.name.equals("<init>"))
			{
				AbstractInsnNode ins = mn.instructions.getFirst();
				while(ins != null)
				{
					if(ins instanceof MethodInsnNode)
						((MethodInsnNode) ins).owner= "net/jonbell/crij/test/AnonymousClassChangeTest$A";
					ins = ins.getNext();
				}
			}
		}
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		byte[] b = cw.toByteArray();
		Class theAnon = u.defineAnonymousClass(A.class, b, null);
		
		A a = new A();
		a.foo();
		changeClass(a, theAnon);
		Assert.assertTrue(a.getClass().getName().contains("/"));
		a.foo();

	}
	@Test
	public void testChangeRegularClass() throws Exception {
		A a = new A();
		a.foo();
		changeClass(a, B.class);
		Assert.assertTrue(a instanceof B);
		a.foo();
	}
	
	Unsafe u;
	
	public AnonymousClassChangeTest() {
		try{
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			u = (Unsafe) f.get(null);
		}
		catch(Throwable t)
		{
			t.printStackTrace();
			Assert.fail();
		}
	}
	void changeClass(Object in, Class otherClass) throws InstantiationException
	{
		Object inst = u.allocateInstance(otherClass);
		int klass = u.getInt(inst,8L);
		u.putInt(in, 8L, klass);
	}
	static class A {
		int f;
		void foo()
		{
			System.out.println("A.foo invoked");
		}
	}
	static class B extends A {
		void foo()
		{
			System.out.println("B.foo invoked");
		}
	}
}
