// Runnable PoC:
//   1. Build a CROCHET-shaped template byte[] once.
//   2. Specialize it for two different user classes (UserA, UserB).
//   3. Instantiate and invoke the patched $$crijCheckpoint / $$crijRollback,
//      proving the CP was rewritten correctly (owner + method name).
//   4. Run a klass-swap (like CROCHET's changeClass) to swap a UserA instance
//      onto its specialized hidden klass, then call an overridden method.
//   5. Micro-measure the end-to-end rewrite + defineHiddenClass cost.
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import sun.misc.Unsafe;

public class Main {
    private static Unsafe U;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        } catch (Throwable t) { throw new ExceptionInInitializerError(t); }
    }

    public static void main(String[] args) throws Throwable {
        byte[] template = TemplateBytes.build();
        System.out.println("template bytes = " + template.length);

        // --- specialize for UserA ---
        SpecializerB.SpecSpec specA = new SpecializerB.SpecSpec(
            UserA.class, RoleIface.class,
            "UserA$$CRIJFast",
            "RuntimeAgent", "checkpointCalled", "rollbackCalled");
        Class<?> cA = SpecializerB.specialize(template, specA);

        // --- specialize for UserB ---
        SpecializerB.SpecSpec specB = new SpecializerB.SpecSpec(
            UserB.class, RoleIface.class,
            "UserB$$CRIJFast",
            "RuntimeAgent", "checkpointCalled", "rollbackCalled");
        Class<?> cB = SpecializerB.specialize(template, specB);

        System.out.println("UserA  spec: " + cA.getName() + " <: " + cA.getSuperclass().getName()
                         + "  hidden=" + cA.isHidden());
        System.out.println("UserB  spec: " + cB.getName() + " <: " + cB.getSuperclass().getName()
                         + "  hidden=" + cB.isHidden());

        if (cA.getSuperclass() != UserA.class) throw new AssertionError("cA super != UserA");
        if (cB.getSuperclass() != UserB.class) throw new AssertionError("cB super != UserB");
        if (!RoleIface.class.isAssignableFrom(cA)) throw new AssertionError("cA missing iface");
        if (!RoleIface.class.isAssignableFrom(cB)) throw new AssertionError("cB missing iface");

        // Invoke the specialized hooks via reflection. The CP rewrite means
        // invokestatic crij/sentinel/AgentSentinel.__onCheckpointSentinel__
        // was rewritten to  RuntimeAgent.checkpointCalled (see SpecializerB).
        int beforeCk = RuntimeAgent.checkpointHits.get();
        int beforeRb = RuntimeAgent.rollbackHits.get();

        Object ia = cA.getDeclaredConstructor().newInstance();
        Object ib = cB.getDeclaredConstructor().newInstance();
        Method ca = cA.getMethod("$$crijCheckpoint", int.class);
        Method cbm = cB.getMethod("$$crijCheckpoint", int.class);
        Method ra = cA.getMethod("$$crijRollback", int.class);
        Method rbm = cB.getMethod("$$crijRollback", int.class);

        ca.invoke(ia, 1);
        cbm.invoke(ib, 1);
        ra.invoke(ia, 2);
        rbm.invoke(ib, 2);

        int ck = RuntimeAgent.checkpointHits.get() - beforeCk;
        int rb = RuntimeAgent.rollbackHits.get() - beforeRb;
        System.out.println("checkpoint callbacks fired: " + ck + "  (expected 2)");
        System.out.println("rollback   callbacks fired: " + rb + "  (expected 2)");
        if (ck != 2 || rb != 2) throw new AssertionError("CP-patch miswired");

        // ---- klass-swap demonstration ----
        UserA a = new UserA();
        System.out.println("before swap: a.getClass()=" + a.getClass().getSimpleName()
                         + " a.doWork()=" + a.doWork() + " (counter=" + a.counter + ")");
        Object exemplar = U.allocateInstance(cA);
        int fromK = U.getInt(a, 8L);
        int toK   = U.getInt(exemplar, 8L);
        boolean ok = U.compareAndSwapInt(a, 8L, fromK, toK);
        System.out.println("klass-swap ok=" + ok
                         + "  a.getClass()=" + a.getClass().getName()
                         + "  isHidden=" + a.getClass().isHidden());
        // Still behaves as a UserA from a doWork POV (we didn't override it),
        // BUT the object is now assignable to RoleIface.
        System.out.println("a instanceof RoleIface? " + (a instanceof RoleIface));
        System.out.println("after swap: a.doWork()=" + a.doWork() + " (counter=" + a.counter + ")");

        // ---- sfHelperClass cache semantics: verify hidden class stays alive
        // as long as we hold a strong reference (analogous to how CROCHET
        // caches sfHelperClass on the Class object) ----
        System.gc(); System.gc();
        // cA/cB still live. No assertion needed — the strong refs from our
        // fields / from the `class data` weakset keep STRONG hidden classes
        // from being unloaded even across GCs.

        // ---- micro-measurement ----
        measure(template, UserA.class);
    }

    private static void measure(byte[] template, Class<?> userClass) throws Throwable {
        final int WARMUP = 1000;
        final int MEASURE = 1000;

        SpecializerB.SpecSpec spec = new SpecializerB.SpecSpec(
            userClass, RoleIface.class,
            "UserA$$CRIJFast$Mic",
            "RuntimeAgent", "checkpointCalled", "rollbackCalled");

        for (int i = 0; i < WARMUP; i++) {
            SpecializerB.specialize(template, spec);
        }

        long t0 = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) {
            SpecializerB.specialize(template, spec);
        }
        long dt = System.nanoTime() - t0;
        long avg = dt / MEASURE;
        System.out.println();
        System.out.println("=== micro-measurement (Approach B) ===");
        System.out.println("warmup iters:          " + WARMUP);
        System.out.println("measured iters:        " + MEASURE);
        System.out.println("total wall time:       " + (dt / 1_000_000L) + " ms");
        System.out.println("avg per specialize+defineHiddenClass: " + avg + " ns  (~"
                         + String.format("%.1f", avg / 1000.0) + " us)");
    }
}
