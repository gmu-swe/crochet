package net.jonbell.crochet.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the paper's top-level {@link CheckpointRollbackAgent#checkpointAll()}
 * / {@link CheckpointRollbackAgent#rollbackAll(int)} API.
 *
 * <p>Uses hand-written {@link CRIJInstrumented} mocks rather than running
 * under an instrumented JDK — the runtime only requires that {@code target}
 * implement {@link CRIJInstrumented}, so mocks exercise the full code path
 * (version CAS, array-registry propagation, SF-helper stub dispatch) without
 * needing bytecode rewriting in the test JVM.
 */
class CheckpointRollbackAgentTest {

    /** Minimal {@link CRIJInstrumented} that records the version + snapshots its mutable state. */
    private static final class MockCell implements CRIJInstrumented {
        int value;
        String label;

        int snapValue;
        String snapLabel;
        int version;
        Object snap;

        @Override public void $$crochetCopyFieldsTo(Object to) {
            MockCell dst = (MockCell) to;
            dst.value = this.value;
            dst.label = this.label;
        }

        @Override public void $$crochetCopyFieldsFrom(Object old) {
            MockCell src = (MockCell) old;
            this.value = src.value;
            this.label = src.label;
        }

        @Override public void $$crochetCheckpoint(int v) {
            this.snapValue = this.value;
            this.snapLabel = this.label;
            this.version = v;
        }

        @Override public void $$crochetRollback(int v) {
            this.value = this.snapValue;
            this.label = this.snapLabel;
            this.version = 0;
            this.snap = null;
        }

        @Override public void $$crochetPropagateCheckpoint(int version) {}
        @Override public void $$crochetPropagateRollback(int version) {}
        @Override public int $$crochetGetVersion() { return version; }
        @Override public void $$crochetSetVersion(int v) { this.version = v; }
        @Override public Object $$crochetGetSnap() { return snap; }
        @Override public void $$crochetSetSnap(Object s) { this.snap = s; }
        @Override public void $$crochetAccess() {}
        @Override public boolean $$crochetIsRollbackState() { return false; }
    }

    @Test
    void individualCheckpointRollbackOnThreeObjects() {
        // Per-object {@code checkpoint(obj)} / {@code rollback(obj, v)} path.
        // Exercises the primary user API: each object sees exactly one
        // checkpoint + one rollback.
        MockCell a = new MockCell();
        a.value = 1;
        a.label = "a-init";
        MockCell b = new MockCell();
        b.value = 2;
        b.label = "b-init";
        MockCell c = new MockCell();
        c.value = 3;
        c.label = "c-init";

        int av0 = a.value, bv0 = b.value, cv0 = c.value;
        String al0 = a.label, bl0 = b.label, cl0 = c.label;

        int v = CheckpointRollbackAgent.checkpoint(a);
        CheckpointRollbackAgent.checkpoint(b);
        CheckpointRollbackAgent.checkpoint(c);

        assertTrue(v > 0, "checkpoint must yield a positive version");

        a.value = 99;
        a.label = "a-mut";
        b.value = 98;
        b.label = "b-mut";
        c.value = 97;
        c.label = "c-mut";

        CheckpointRollbackAgent.rollback(a, v);
        CheckpointRollbackAgent.rollback(b, v);
        CheckpointRollbackAgent.rollback(c, v);

        assertEquals(av0, a.value);
        assertEquals(al0, a.label);
        assertEquals(bv0, b.value);
        assertEquals(bl0, b.label);
        assertEquals(cv0, c.value);
        assertEquals(cl0, c.label);
    }

    @Test
    void checkpointAllPairWithMultipleObjectsRestoresAll() {
        // Paper's top-level API: user calls {@code checkpointAll} + individual
        // {@code checkpoint(obj)} on every root they care about. {@code
        // rollbackAll} walks classes + threads + sys-CL; for arbitrary user
        // objects the user must rely on the per-object API too.
        //
        // This test mixes both: {@code checkpointAll} for the class/thread
        // roots plus individual {@code checkpoint(obj)} for three Cell
        // instances. The rollback pairing {@code rollback(obj, v)} +
        // {@code rollbackAll(v)} must leave every object restored.
        System.setProperty("crochet.checkpointAll.skipSystem", "true");

        MockCell a = new MockCell();
        a.value = 1;
        a.label = "a-init";
        MockCell b = new MockCell();
        b.value = 2;
        b.label = "b-init";
        MockCell c = new MockCell();
        c.value = 3;
        c.label = "c-init";

        int v = CheckpointRollbackAgent.checkpointAll();
        CheckpointRollbackAgent.checkpoint(a);
        CheckpointRollbackAgent.checkpoint(b);
        CheckpointRollbackAgent.checkpoint(c);

        a.value = 99;
        a.label = "a-mut";
        b.value = 98;
        b.label = "b-mut";
        c.value = 97;
        c.label = "c-mut";

        CheckpointRollbackAgent.rollback(a, v);
        CheckpointRollbackAgent.rollback(b, v);
        CheckpointRollbackAgent.rollback(c, v);
        CheckpointRollbackAgent.rollbackAll(v);

        assertEquals(1, a.value);
        assertEquals("a-init", a.label);
        assertEquals(2, b.value);
        assertEquals("b-init", b.label);
        assertEquals(3, c.value);
        assertEquals("c-init", c.label);
    }

    @Test
    void checkpointAllWithSkipSystemBumpsVersion() {
        // Even without any registered classes or instrumented threads, a bare
        // checkpointAll/rollbackAll call must at minimum advance the version
        // counter (paper I1/I2).
        System.setProperty("crochet.checkpointAll.skipSystem", "true");
        int before = CheckpointRollbackAgent.nextCheckpointVersion();
        // Advance one to make room for the pair we're about to test.
        CheckpointRollbackAgent.nextRollbackVersion();
        int v = CheckpointRollbackAgent.checkpointAll();
        assertTrue(v > before, "checkpointAll must bump the version monotonically");
        CheckpointRollbackAgent.rollbackAll(v);
        // Next checkpoint should still be strictly greater (monotone).
        int after = CheckpointRollbackAgent.nextCheckpointVersion();
        assertTrue(after > v, "version counter must stay monotone across rollbackAll");
    }

    @Test
    void checkpointArrayRoundTrip() {
        int[] arr = {1, 2, 3, 4, 5};
        int v = CheckpointRollbackAgent.checkpointArray(arr);
        arr[0] = 99;
        arr[4] = 55;
        CheckpointRollbackAgent.rollbackArray(arr, v);
        assertEquals(1, arr[0]);
        assertEquals(5, arr[4]);
    }

    @Test
    void classMetaOfRegistersTouchedClass() {
        ClassMeta m = ClassMeta.of(MockCell.class);
        assertNotNull(m);
        assertTrue(CheckpointRollbackAgent.TOUCHED_CLASSES.contains(MockCell.class),
                "ClassMeta.of should register class with TOUCHED_CLASSES");
    }
}
