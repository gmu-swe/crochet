// -----------------------------------------------------------------------
// Gap 6 thread-safety patch sketch for CheckpointRollbackAgent.
// NOT APPLIED. This file is a design artifact — it illustrates the shape of
// the proposed changes. Full application requires matching edits to
// FieldAdder (sentinel-aware $$crochetCheckpoint bytecode), ClassMeta
// (KlassBinding holder), and ProxyTemplate (fastAccess entry order).
// -----------------------------------------------------------------------

package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import net.jonbell.crochet.transform.ProxyTemplate;
import net.jonbell.crochet.transform.Specializer;

import sun.misc.Unsafe;

public final class CheckpointRollbackAgent {

    private CheckpointRollbackAgent() {}

    static final Unsafe U;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static final long KLASS_OFFSET = 8L;

    /**
     * Fast template unchanged — DCL + volatile is correct here because the
     * byte[] is never mutated after publish.
     */
    private static volatile byte[] FAST_TEMPLATE;

    private static byte[] fastTemplate() {
        byte[] t = FAST_TEMPLATE;
        if (t == null) {
            synchronized (CheckpointRollbackAgent.class) {
                t = FAST_TEMPLATE;
                if (t == null) {
                    t = ProxyTemplate.emit();
                    FAST_TEMPLATE = t;
                }
            }
        }
        return t;
    }

    // ==== L1: version counter -> AtomicInteger with CAS loop ================
    //
    // Paper §3.4 para. 1: "uses atomic compare-and-swap operations".
    // Old: synchronized static method.
    // New: lock-free; a failed CAS means a peer advanced, retry.
    // Invariants: I1 (unique) and I2 (monotone) follow trivially from the
    // CAS-on-successor-value idiom; parity is derived from the observed
    // value, not stored separately.
    //
    private static final AtomicInteger VERSION_COUNTER = new AtomicInteger(0);

    public static int nextCheckpointVersion() {
        while (true) {
            int cur = VERSION_COUNTER.get();
            int next = cur + 1;
            if ((next & 1) == 0) next++;                     // force odd
            if (VERSION_COUNTER.compareAndSet(cur, next)) return next;
        }
    }

    public static int nextRollbackVersion() {
        while (true) {
            int cur = VERSION_COUNTER.get();
            int next = cur + 1;
            if ((next & 1) != 0) next++;                     // force even
            if (next == 0) next = 2;                         // skip 0 sentinel
            if (VERSION_COUNTER.compareAndSet(cur, next)) return next;
        }
    }

    // ==== User-facing API (unchanged surface) ===============================
    public static int checkpoint(Object target) {
        int v = nextCheckpointVersion();
        ((CRIJInstrumented) target).$$crochetCheckpoint(v);
        return v;
    }

    public static void rollback(Object target, int v) {
        int rv = nextRollbackVersion();
        ((CRIJInstrumented) target).$$crochetRollback(rv);
    }

    // ==== Called from instrumented code =====================================

    public static void swapToFastProxy(Object target, Class<?> userClass) {
        ClassMeta.FastBinding fb  = ClassMeta.of(userClass).fastBinding();
        ClassMeta.KlassBinding ub = ClassMeta.of(userClass).userBinding();
        // Caller (emitted $$crochetCheckpoint) is expected to already hold the
        // version sentinel. A failed CAS here means a peer swapped klass;
        // we continue — their version write will drive fastAccess.
        U.compareAndSwapInt(target, KLASS_OFFSET, ub.klass, fb.klass);
    }

    // ==== L6/L8: fastAccess with race-winner pattern ========================
    //
    // Paper: "all threads race to update the object snapshot ... with a CAS
    // operation. One thread wins and keeps its snapshot, all the other
    // threads discard their (equivalent) snapshots."
    //
    // The klass CAS at the top claims the proxy→user transition exclusively.
    // Snap install is CAS null→fresh. Rollback uses a monitor on the snap
    // (paper line 43) so overwrite + null happen atomically.
    //
    public static void fastAccess(CRIJInstrumented obj) {
        Class<?> proxyClass = obj.getClass();
        Class<?> userClass  = proxyClass.getSuperclass();
        ClassMeta m = ClassMeta.of(userClass);
        int proxyK = m.fastBinding().klass;
        int userK  = m.userBinding().klass;

        // Race winner: only one thread's CAS succeeds; peers return.
        if (!U.compareAndSwapInt(obj, KLASS_OFFSET, proxyK, userK)) {
            return;
        }

        int v = obj.$$crochetGetVersion();    // volatile read in Gap 6 emit
        if (v == 0) return;                   // defensive; shouldn't occur

        if ((v & 1) == 1) {
            // --- CHECKPOINT path ---
            Object fresh = U.allocateInstance(userClass);
            obj.$$crochetCopyFieldsTo(fresh);
            // CAS null → fresh: loser drops its (equivalent) allocation.
            long snapOff = m.snapOffset;
            U.compareAndSwapObject(obj, snapOff, null, fresh);
        } else {
            // --- ROLLBACK path ---
            Object snap = obj.$$crochetGetSnap();
            if (snap == null) return;                // peer rolled us back
            synchronized (snap) {
                if (obj.$$crochetGetSnap() != snap) return;   // recheck
                obj.$$crochetCopyFieldsFrom(snap);
                obj.$$crochetSetSnap(null);
            }
        }
    }

    public static Object allocateShadow(Class<?> c) {
        try {
            return U.allocateInstance(c);
        } catch (InstantiationException e) {
            throw new IllegalStateException("allocateInstance failed for " + c, e);
        }
    }

    // ==== L6: klass-swap with inspected CAS result ==========================
    //
    // Returning the bool lets callers distinguish:
    //   - benign loss (peer already did user→proxy)
    //   - semantic loss (we expected proxy→user but peer finished first)
    //
    public static boolean changeClass(Object target, int from, int to) {
        return U.compareAndSwapInt(target, KLASS_OFFSET, from, to);
    }

    // ==== L3/L4: klassOf via immutable KlassBinding =========================
    //
    // ClassMeta.userBinding() / fastBinding() lazily compute a KlassBinding
    // (final { Object prealloc; int klass; }). One volatile publish;
    // final-field semantics guarantee readers see both fields initialized.
    //
    public static int klassOf(Class<?> c) {
        return ClassMeta.of(c).userBinding().klass;
    }

    public static Class<?> fastProxyFor(Class<?> userClass) {
        return ClassMeta.of(userClass).fastBinding().clazz;
    }

    // ==== generateFastProxy unchanged =======================================
    private static Class<?> generateFastProxy(Class<?> userClass) {
        try {
            MethodHandles.Lookup lookup = Specializer.lookupFromUserClass(userClass);
            return Specializer.specializeFast(fastTemplate(), userClass, lookup);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to generate Fast proxy for " + userClass, t);
        }
    }
}

// -----------------------------------------------------------------------
// Companion sketch — FieldAdder emits sentinel-aware $$crochetCheckpoint.
// Pseudo-Java; the actual implementation is a sequence of ASM MethodVisitor
// calls analogous to the existing emitCheckpoint()/emitRollback().
// -----------------------------------------------------------------------
//
// public void $$crochetCheckpoint(int v) {
//     int cur   = U.getIntVolatile(this, VERSION_OFFSET);
//     int realV = (cur < 0) ? -cur : cur;
//     if (realV >= v) return;                                           // I2
//     if (!U.compareAndSwapInt(this, VERSION_OFFSET, cur, -v)) return;  // peer won
//     CheckpointRollbackAgent.swapToFastProxy(this, ThisClass.class);
//     U.compareAndSwapInt(this, VERSION_OFFSET, -v, v);                 // finalize
// }
//
// $$crochetRollback symmetric. $$crochetIsRollbackState must decode the
// sentinel: realV = (v<0) ? -v : v; return (realV != 0) && ((realV & 1) == 0).
