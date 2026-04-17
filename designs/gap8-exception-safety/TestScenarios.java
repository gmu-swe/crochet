// Gap 8 fault-injection test scenarios — sketches only. NOT executed
// from the test harness in this directory; intended to be dropped under
// crochet-agent/src/test/java/ when the design is accepted.
//
// Each scenario exercises one of the F1..F7 failure modes from DESIGN.md.

package net.jonbell.crochet.runtime;

import static org.junit.jupiter.api.Assertions.*;

import net.jonbell.crochet.runtime.CheckpointRollbackAgent;
import net.jonbell.crochet.runtime.RollbackException;

public final class Gap8ExceptionSafetyTests {

    // --- Scenario A: F1 — CopyFieldsTo throws by design ------------------
    //
    // A user class overrides the generated $$crochetCopyFieldsTo via a
    // pre-existing interface method of the same name (simulated by a
    // subclass whose *own* copy method throws). Expected: checkpoint()
    // raises RollbackException.isPoison(), target's fields are unchanged,
    // version is rolled back to prior, snap is null.

    static class FaultyCopy {
        int value = 5;
        // simulate: before instrumentation runs, user code wraps the
        // bulk-copy method in a throwing adapter via reflective override
        // in a testing subclass. In practice we swap in a test hook.
    }

    public void a_copyFieldsTo_throws_preserves_state() {
        FaultyCopy f = new FaultyCopy();
        TestHooks.nextCopyFieldsToThrows(f,
                new RuntimeException("sim: field copy exploded"));
        int prior = ((CRIJInstrumented) f).$$crochetGetVersion();

        RollbackException re = assertThrows(RollbackException.class,
                () -> CheckpointRollbackAgent.checkpoint(f));
        assertTrue(re.isPoison());
        assertEquals(5, f.value, "original field intact");
        assertEquals(prior, ((CRIJInstrumented) f).$$crochetGetVersion(),
                "version rolled back");
        assertNull(((CRIJInstrumented) f).$$crochetGetSnap(),
                "snap not installed");
    }

    // --- Scenario B: F2/F3 — Fast-proxy generation fails ----------------
    //
    // A user class whose superclass is null on some path, or whose
    // MethodHandles.Lookup rejects defineHiddenClass. Simulated by
    // corrupting the class-metadata cache. Expected: checkpoint()
    // raises RollbackException.isPoison() and version is rolled back.

    static class BadProxyGen {
        int x = 1;
    }

    public void b_proxy_generation_fails_rolls_back_version() {
        BadProxyGen b = new BadProxyGen();
        TestHooks.poisonFastProxyFor(BadProxyGen.class);
        int prior = ((CRIJInstrumented) b).$$crochetGetVersion();

        RollbackException re = assertThrows(RollbackException.class,
                () -> CheckpointRollbackAgent.checkpoint(b));
        assertTrue(re.isPoison());
        assertNotNull(re.getCause(), "underlying cause propagated");
        assertEquals(prior, ((CRIJInstrumented) b).$$crochetGetVersion());
        assertEquals(1, b.x);
    }

    // --- Scenario C: F1 on rollback path — partial restore --------------
    //
    // CopyFieldsFrom throws halfway through restoring. Expected: the
    // snap is PRESERVED (so the user can retry), klass has been swapped
    // back to userClass (future accesses don't re-enter fastAccess),
    // and RollbackException.isPoison() is raised. The object is in a
    // partially-restored state; we document and test this explicitly.

    static class PartialRestore {
        int a = 10;
        int b = 20;
    }

    public void c_rollback_copyFrom_throws_midway_preserves_snap() {
        PartialRestore p = new PartialRestore();
        int v = CheckpointRollbackAgent.checkpoint(p);
        p.a = 100;
        p.b = 200;

        TestHooks.nextCopyFieldsFromThrowsAfter(p, 1,
                new RuntimeException("sim: halfway through"));

        RollbackException re = assertThrows(RollbackException.class,
                () -> CheckpointRollbackAgent.rollback(p, v));
        assertTrue(re.isPoison());
        // snap preserved for retry
        assertNotNull(((CRIJInstrumented) p).$$crochetGetSnap());
        // klass is back to userClass (no proxy hook on further field reads)
        assertSame(PartialRestore.class, p.getClass());
        // partial state — document, don't assert specific fields;
        // user's recovery path is to retry rollback().
    }

    // --- Scenario D: F4 — CAS race between two checkpointers ------------
    //
    // Two threads call checkpoint() on the same object before either
    // gets a chance to access a field. changeClass CAS succeeds for one
    // and fails for the other. Expected: no exception, the newer version
    // wins, snap reflects the state at the time of whichever thread's
    // fastAccess fires first.

    static class Racey {
        int n = 0;
    }

    public void d_concurrent_checkpoint_no_exception() throws Exception {
        Racey r = new Racey();
        Thread t1 = new Thread(() -> CheckpointRollbackAgent.checkpoint(r));
        Thread t2 = new Thread(() -> CheckpointRollbackAgent.checkpoint(r));
        t1.start(); t2.start();
        t1.join();  t2.join();

        // Force fastAccess by touching a field.
        r.n = 99;

        assertEquals(99, r.n);
        // Invariant: the object's klass is the userClass again post-access.
        assertSame(Racey.class, r.getClass());
    }

    // --- Scenario E (bonus): F7 — user mutation throws after checkpoint -
    //
    // Invariant: snap should remain valid. User catches the exception,
    // calls rollback(), and sees pre-mutation state.

    static class UserMutationThrows {
        int v = 7;
    }

    public void e_user_code_throws_mid_mutation_snap_still_valid() {
        UserMutationThrows u = new UserMutationThrows();
        int ck = CheckpointRollbackAgent.checkpoint(u);
        try {
            u.v = 42;
            throw new RuntimeException("sim: user code failed after mutation");
        } catch (RuntimeException ignore) {
            CheckpointRollbackAgent.rollback(u, ck);
        }
        assertEquals(7, u.v, "rollback recovered pre-mutation state");
    }


    // --- Test hook facade -------------------------------------------------
    //
    // TestHooks is a tiny internal API that the test harness uses to
    // inject a Throwable into the NEXT invocation of a specific
    // instrumented method on a specific object, or to poison the
    // fastProxy cache for a specific Class. Implementation sketch: a
    // ConcurrentHashMap<Key,Throwable> queried by instrumented bodies
    // compiled under a "test" profile of the transformer.

    static final class TestHooks {
        static void nextCopyFieldsToThrows(Object target, Throwable t)    {}
        static void nextCopyFieldsFromThrowsAfter(Object target, int n, Throwable t) {}
        static void poisonFastProxyFor(Class<?> userClass)                {}
    }
}
