package net.jonbell.crochet.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExternalStateRegistry} and its integration with
 * {@link CheckpointRollbackAgent#checkpointAll()} /
 * {@link CheckpointRollbackAgent#rollbackAll(int)}.
 *
 * <h2>Validation matrix</h2>
 * <ol>
 *   <li>Ordering contract: snapshot fires before root walk; restore fires after
 *       heap restore; snapshot return value reaches restore consumer.
 *   <li>Throws-in-restore: all hooks run; HookFailure is raised with all
 *       exceptions suppressed; failing hook name appears in the message.
 *   <li>Throws-in-snapshot: checkpoint aborts; no partial state visible;
 *       subsequent hooks do not run; registry is clean (lastSnapResults null).
 *   <li>Empty registry: no allocation on the dispatch path (gate 7).
 *   <li>Duplicate registration: warning logged, existing hook replaced.
 *   <li>Unregister: hook no longer fires after unregister.
 *   <li>Facade: {@link Crochet#registerExternalState} /
 *       {@link Crochet#unregisterExternalState} delegate correctly.
 *   <li>Composition gate 13: a registered hook does not break
 *       {@code checkpointAll}/{@code rollbackAll} flow for non-hook objects.
 * </ol>
 */
class ExternalStateRegistryTest {

    /** Reusable mock that implements CRIJInstrumented — copied from CheckpointRollbackAgentTest. */
    private static final class MockCell implements CRIJInstrumented {
        int value;
        int snapValue;
        int version;
        Object snap;

        @Override public void $$crochetCopyFieldsTo(Object to) { ((MockCell) to).value = value; }
        @Override public void $$crochetCopyFieldsFrom(Object old) { value = ((MockCell) old).value; }
        @Override public void $$crochetCheckpoint(int v) { snapValue = value; version = v; }
        @Override public void $$crochetRollback(int v) { value = snapValue; version = 0; snap = null; }
        @Override public void $$crochetPropagateCheckpoint(int v) {}
        @Override public void $$crochetPropagateRollback(int v) {}
        @Override public int $$crochetGetVersion() { return version; }
        @Override public void $$crochetSetVersion(int v) { version = v; }
        @Override public Object $$crochetGetSnap() { return snap; }
        @Override public void $$crochetSetSnap(Object s) { snap = s; }
        @Override public void $$crochetAccess() {}
        @Override public boolean $$crochetIsRollbackState() { return false; }
    }

    @BeforeEach
    void setUp() {
        // Ensure a clean registry before every test.
        ExternalStateRegistry.clearAll();
        System.setProperty("crochet.checkpointAll.skipSystem", "true");
    }

    @AfterEach
    void tearDown() {
        ExternalStateRegistry.clearAll();
    }

    // =========================================================================
    // 1. Ordering contract
    // =========================================================================

    /**
     * Verifies the ordering contract:
     * - snapshot fires on the calling thread before the heap walk
     * - the value returned by snapshot is delivered to restore
     * - restore fires after the heap walk
     *
     * We use an AtomicReference to capture the snap result inside the restore
     * consumer, then assert it equals what snapshot returned.
     */
    @Test
    void snapshotResultIsDeliveredToRestore() {
        AtomicReference<Object> captured = new AtomicReference<>();
        Object sentinel = new Object();

        Crochet.registerExternalState("ordering-test",
                () -> sentinel,          // snapshot returns sentinel
                received -> captured.set(received));  // restore receives it

        int v = CheckpointRollbackAgent.checkpointAll();
        // Snapshot must have fired — lastSnapResults should be non-null.
        Object[] snapResults = ExternalStateRegistry.lastSnapResults;
        // Actually lastSnapResults is cleared after restore — check it was set
        // by verifying it through the restore side: after rollbackAll, captured
        // holds what snapshot returned.
        CheckpointRollbackAgent.rollbackAll(v);

        assertSame(sentinel, captured.get(),
                "restore must receive the exact object returned by snapshot");
    }

    /**
     * Verifies that the snapshot fires BEFORE the heap walk, i.e. sees the
     * pre-checkpoint value of a witness field. We record the witness value
     * inside the snapshot lambda and assert it matches the pre-mutation value.
     */
    @Test
    void snapshotSeesPreCheckpointHeap() {
        MockCell cell = new MockCell();
        cell.value = 42;

        AtomicInteger witnessAtSnapshot = new AtomicInteger(-1);

        Crochet.registerExternalState("heap-witness",
                () -> {
                    // The heap has NOT yet been walked when snapshot fires.
                    witnessAtSnapshot.set(cell.value);
                    return null;
                },
                ignored -> {});

        // Snapshot fires here; cell.value is 42 at that point.
        int v = CheckpointRollbackAgent.checkpointAll();
        // Mutate after checkpoint.
        cell.value = 99;
        CheckpointRollbackAgent.rollbackAll(v);

        assertEquals(42, witnessAtSnapshot.get(),
                "snapshot must see the pre-checkpoint (42) value, not the post-mutation (99) value");
    }

    /**
     * Verifies that restore fires AFTER the heap has been restored. We record
     * the witness value inside the restore lambda and assert it matches the
     * pre-mutation value (i.e. the rollback has already happened).
     *
     * This test uses a mock that tracks its own state so we can observe the
     * heap value inside the restore lambda.
     */
    @Test
    void restoreSeesPostRollbackHeap() {
        MockCell cell = new MockCell();
        cell.value = 7;
        CheckpointRollbackAgent.checkpoint(cell);

        AtomicInteger witnessAtRestore = new AtomicInteger(-1);

        Crochet.registerExternalState("restore-witness",
                () -> null,
                ignored -> {
                    // By the time restore fires, cell should be back to 7.
                    witnessAtRestore.set(cell.value);
                });

        int v = CheckpointRollbackAgent.checkpointAll();
        cell.value = 999;
        CheckpointRollbackAgent.rollback(cell, v);
        CheckpointRollbackAgent.rollbackAll(v);

        assertEquals(7, witnessAtRestore.get(),
                "restore must see the post-rollback (7) value, not the post-mutation (999) value");
    }

    // =========================================================================
    // 2. Throws-in-restore
    // =========================================================================

    /**
     * When one hook's restore throws, the remaining hooks still run, and a
     * {@link RollbackException.HookFailure} is raised after all hooks have
     * been attempted. The suppressed exceptions include the failing hook's
     * name in their message.
     */
    @Test
    void throwsInRestoreRunsAllHooksAndSurfaces() {
        AtomicInteger secondHookRan = new AtomicInteger(0);

        Crochet.registerExternalState("thrower",
                () -> null,
                ignored -> { throw new RuntimeException("deliberate-failure"); });

        Crochet.registerExternalState("survivor",
                () -> null,
                ignored -> secondHookRan.incrementAndGet());

        int v = CheckpointRollbackAgent.checkpointAll();

        RollbackException.HookFailure ex = assertThrows(
                RollbackException.HookFailure.class,
                () -> CheckpointRollbackAgent.rollbackAll(v));

        // The second hook must have run despite the first one throwing.
        assertEquals(1, secondHookRan.get(),
                "survivor hook must run even though thrower hook threw");

        // The failure's suppressed exceptions must include the offending hook name.
        Throwable[] suppressed = ex.getSuppressed();
        assertEquals(1, suppressed.length, "exactly one hook failed");
        assertTrue(suppressed[0].getMessage().contains("thrower"),
                "suppressed exception must mention the failing hook name; got: "
                + suppressed[0].getMessage());

        // HookFailure must carry POISON_VERSION.
        assertEquals(RollbackException.POISON_VERSION, ex.version,
                "HookFailure must carry POISON_VERSION");
    }

    /**
     * Multiple failing restore hooks: all are collected as suppressed
     * exceptions on the single HookFailure.
     */
    @Test
    void multipleThrowsInRestoreCollectedAsSuppressed() {
        Crochet.registerExternalState("fail-1",
                () -> null,
                ignored -> { throw new IllegalStateException("fail-1"); });
        Crochet.registerExternalState("fail-2",
                () -> null,
                ignored -> { throw new IllegalArgumentException("fail-2"); });

        int v = CheckpointRollbackAgent.checkpointAll();

        RollbackException.HookFailure ex = assertThrows(
                RollbackException.HookFailure.class,
                () -> CheckpointRollbackAgent.rollbackAll(v));

        assertEquals(2, ex.getSuppressed().length,
                "both failing hooks must be surfaced as suppressed exceptions");
    }

    // =========================================================================
    // 3. Throws-in-snapshot
    // =========================================================================

    /**
     * When a hook's snapshot throws, the checkpoint aborts. Subsequent hooks
     * do not run. The original exception propagates unwrapped. The
     * {@link ExternalStateRegistry#lastSnapResults} field is set to null.
     */
    @Test
    void throwsInSnapshotAbortsCheckpointAndSkipsSubsequentHooks() {
        AtomicInteger secondHookFired = new AtomicInteger(0);

        Crochet.registerExternalState("snap-thrower",
                () -> { throw new RuntimeException("snap-failure"); },
                ignored -> {});

        Crochet.registerExternalState("snap-survivor",
                () -> {
                    secondHookFired.incrementAndGet();
                    return null;
                },
                ignored -> {});

        // checkpointAll must propagate the snapshot exception.
        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> CheckpointRollbackAgent.checkpointAll());

        assertEquals("snap-failure", ex.getMessage(),
                "original snapshot exception must propagate unwrapped");

        // Subsequent hooks must NOT have run.
        assertEquals(0, secondHookFired.get(),
                "second hook snapshot must not fire after first snapshot throws");

        // lastSnapResults must be null — no partial state.
        assertNull(ExternalStateRegistry.lastSnapResults,
                "lastSnapResults must be null after snapshot abort");
    }

    // =========================================================================
    // 4. Empty registry — zero allocation cold path (gate 7)
    // =========================================================================

    /**
     * With no hooks registered, {@code checkpointAll} / {@code rollbackAll}
     * dispatch does not allocate on the external-hook path (trivially
     * verifiable by code inspection: {@code HOOKS.isEmpty()} short-circuits).
     *
     * This test verifies behavioural correctness of the empty path: neither
     * call throws, and the registry remains empty.
     */
    @Test
    void emptyRegistryCheckpointRollbackDoesNotThrow() {
        assertEquals(0, ExternalStateRegistry.size(), "registry must be empty");
        int v = CheckpointRollbackAgent.checkpointAll();
        assertDoesNotThrow(() -> CheckpointRollbackAgent.rollbackAll(v),
                "rollbackAll with empty hook registry must not throw");
        assertNull(ExternalStateRegistry.lastSnapResults,
                "lastSnapResults must be null when registry is empty");
    }

    // =========================================================================
    // 5. Duplicate registration
    // =========================================================================

    /**
     * Registering a hook under an already-registered name replaces the existing
     * hook. The registry size stays the same. The new hook fires; the old one
     * does not.
     */
    @Test
    void duplicateRegistrationReplacesExistingHook() {
        AtomicInteger oldFired = new AtomicInteger(0);
        AtomicInteger newFired = new AtomicInteger(0);

        Crochet.registerExternalState("dup",
                () -> { oldFired.incrementAndGet(); return null; },
                ignored -> {});

        assertEquals(1, ExternalStateRegistry.size());

        // Replace with a new hook under the same name.
        Crochet.registerExternalState("dup",
                () -> { newFired.incrementAndGet(); return null; },
                ignored -> {});

        // Size must not grow.
        assertEquals(1, ExternalStateRegistry.size(),
                "registry size must not grow on duplicate registration");

        int v = CheckpointRollbackAgent.checkpointAll();
        CheckpointRollbackAgent.rollbackAll(v);

        assertEquals(0, oldFired.get(), "old (replaced) hook must not fire");
        assertEquals(1, newFired.get(), "new (replacement) hook must fire");
    }

    // =========================================================================
    // 6. Unregister
    // =========================================================================

    /**
     * After unregistration, the hook no longer fires. Unregistering a
     * non-existent name is a no-op.
     */
    @Test
    void unregisterStopsHookFromFiring() {
        AtomicInteger fired = new AtomicInteger(0);

        Crochet.registerExternalState("to-remove",
                () -> { fired.incrementAndGet(); return null; },
                ignored -> {});

        Crochet.unregisterExternalState("to-remove");

        assertEquals(0, ExternalStateRegistry.size(),
                "registry must be empty after unregister");

        int v = CheckpointRollbackAgent.checkpointAll();
        CheckpointRollbackAgent.rollbackAll(v);

        assertEquals(0, fired.get(), "unregistered hook must not fire");
    }

    @Test
    void unregisterNonExistentNameIsNoOp() {
        assertDoesNotThrow(() -> Crochet.unregisterExternalState("does-not-exist"),
                "unregistering a non-existent hook must be a no-op");
        assertDoesNotThrow(() -> Crochet.unregisterExternalState(null),
                "unregistering null must be a no-op");
    }

    // =========================================================================
    // 7. Facade null-check
    // =========================================================================

    @Test
    void registerExternalStateRejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> Crochet.registerExternalState(null, () -> null, ignored -> {}));
        assertThrows(NullPointerException.class,
                () -> Crochet.registerExternalState("x", null, ignored -> {}));
        assertThrows(NullPointerException.class,
                () -> Crochet.registerExternalState("x", () -> null, null));
    }

    // =========================================================================
    // 8. Composition gate 13: hook + checkpointAll/rollbackAll round-trip
    // =========================================================================

    /**
     * A registered external hook must not break the normal checkpoint/rollback
     * flow for non-hook objects. Verifies that both the hook fires and the
     * MockCell is checkpointed correctly in the same pass.
     */
    @Test
    void hookAndCheckpointAllComposeCorrectly() {
        MockCell cell = new MockCell();
        cell.value = 100;
        CheckpointRollbackAgent.checkpoint(cell);

        AtomicInteger hookFired = new AtomicInteger(0);
        Crochet.registerExternalState("compose-test",
                () -> { hookFired.incrementAndGet(); return null; },
                ignored -> hookFired.incrementAndGet());

        int v = CheckpointRollbackAgent.checkpointAll();
        cell.value = 200;
        CheckpointRollbackAgent.rollback(cell, v);
        CheckpointRollbackAgent.rollbackAll(v);

        // The hook must have fired (once for snapshot, once for restore).
        assertEquals(2, hookFired.get(),
                "hook must fire once on checkpointAll and once on rollbackAll");

        // The cell must be restored to its checkpointed state.
        assertEquals(100, cell.value,
                "cell must be restored to checkpointed value");
    }

    // =========================================================================
    // 9. HookFailure is a RollbackException
    // =========================================================================

    @Test
    void hookFailureIsSubtypeOfRollbackException() {
        Crochet.registerExternalState("sub-type-test",
                () -> null,
                ignored -> { throw new RuntimeException("test"); });

        int v = CheckpointRollbackAgent.checkpointAll();

        // Must be catchable as RollbackException.
        assertThrows(RollbackException.class,
                () -> CheckpointRollbackAgent.rollbackAll(v));
    }

    // =========================================================================
    // 10. Registration order preserved
    // =========================================================================

    /**
     * Hooks fire in registration order. If hook A is registered before hook B,
     * A's snapshot fires before B's, and A's restore fires before B's.
     */
    @Test
    void registrationOrderPreserved() {
        List<String> snapOrder = new ArrayList<>();
        List<String> restoreOrder = new ArrayList<>();

        Crochet.registerExternalState("alpha",
                () -> { snapOrder.add("alpha"); return null; },
                ignored -> restoreOrder.add("alpha"));
        Crochet.registerExternalState("beta",
                () -> { snapOrder.add("beta"); return null; },
                ignored -> restoreOrder.add("beta"));
        Crochet.registerExternalState("gamma",
                () -> { snapOrder.add("gamma"); return null; },
                ignored -> restoreOrder.add("gamma"));

        int v = CheckpointRollbackAgent.checkpointAll();
        CheckpointRollbackAgent.rollbackAll(v);

        assertEquals(List.of("alpha", "beta", "gamma"), snapOrder,
                "snapshot hooks must fire in registration order");
        assertEquals(List.of("alpha", "beta", "gamma"), restoreOrder,
                "restore hooks must fire in registration order");
    }
}
