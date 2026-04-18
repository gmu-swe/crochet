package net.jonbell.crochet.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global checkpoint/rollback version counter. Paper §3.4: "uses atomic
 * compare-and-swap operations" — lock-free CAS retry loop gives I1 (unique)
 * and I2 (monotone) without locking.
 *
 * <p><b>Path A design (64-bit internal counter, 32-bit per-instance field)</b>:
 * this counter is 64-bit so long-running processes can mint &gt; 2^31
 * distinct versions without the internal arithmetic overflowing mid-CAS.
 * The {@code $$crochetVersion} field injected into every user class is
 * still 32-bit (int), because changing the field descriptor to {@code J}
 * is a cross-module rewrite of {@link net.jonbell.crochet.transform.FieldAdder}
 * and its sentinel/CAS emit. The tradeoff:
 * <ul>
 *   <li><b>Global uniqueness</b>: protected up to 2^62 checkpoints
 *       before the long arithmetic wraps.
 *   <li><b>Per-instance uniqueness</b>: at most 2^31 distinct values.
 *       Beyond that, the int truncation of the returned version makes the
 *       per-instance field wrap — a theoretical I1 violation for a single
 *       object, but only reachable on pathological workloads. A JVM
 *       doing 10k checkpoints per second would take 6 days to wrap int;
 *       real workloads amortize over tens of objects, not one.
 *   <li>We narrow {@code long → int} at return time; callers see int and
 *       the emitted bytecode stays int-descriptor.
 * </ul>
 *
 * <p>For full 64-bit per-instance versions (Path B), {@code FieldAdder}
 * would emit {@code $$crochetVersion} as a {@code J} (long) field and
 * update every sentinel CAS in the emitted bytecode to {@code compareAndSwapLong}.
 * Deferred as unnecessary for any realistic workload.
 */
final class VersionCounter {

    private VersionCounter() {}

    static final AtomicLong VERSION_COUNTER = new AtomicLong(0);

    /**
     * Opaque read of the raw long counter. See
     * {@link SfHelperFactory#noteStaticAccess} for the no-checkpoint fast-path
     * gate that uses this.
     */
    static long getOpaque() {
        return VERSION_COUNTER.getOpaque();
    }

    /**
     * Debug-only: once the underlying counter crosses this threshold, log a
     * one-shot warning that per-instance int wraparound is imminent. Gated
     * via {@code -Dcrochet.verboseCompat=true} to stay silent on production
     * builds. A constant below {@link Integer#MAX_VALUE} so we still emit the
     * warning before an actual collision is observable in user code.
     */
    private static final long PER_INSTANCE_WRAP_WARN_THRESHOLD = (1L << 30);

    /** One-shot latch for the per-instance overflow warning. */
    private static volatile boolean perInstanceOverflowWarned;

    static int nextCheckpointVersion() {
        while (true) {
            long cur = VERSION_COUNTER.get();
            long next = cur + 1;
            if ((next & 1L) == 0L) {
                next++; // force odd (checkpoint)
            }
            if (VERSION_COUNTER.compareAndSet(cur, next)) {
                maybeWarnPerInstanceOverflow(next);
                // Lift the RuntimeReady pre-checkpoint gate. Before this
                // point, pre-hooks in instrumented JDK code early-return
                // inside RuntimeReady without touching any other runtime
                // class. See {@code RuntimeReady#VERSION_GATE} for why
                // this gate is load-bearing during JVM bootstrap.
                RuntimeReady.VERSION_GATE = (int) next;
                // Gap 7 closure: a successful first checkpoint implies the
                // agent runtime is fully loaded. Flip the RuntimeReady gate
                // so instrumented JDK bytecode's pre-hooks start tracking
                // state. This handles the jlink-only mode where
                // CrochetAgent.premain doesn't run (no -javaagent attached).
                // Under the -javaagent path, premain already flipped it
                // earlier; this call is a cheap re-write of the same value.
                RuntimeReady.markReady();
                return (int) next;
            }
        }
    }

    static int nextRollbackVersion() {
        while (true) {
            long cur = VERSION_COUNTER.get();
            long next = cur + 1;
            if ((next & 1L) != 0L) {
                next++; // force even (rollback)
            }
            if (next == 0L) {
                next = 2L; // preserve 0 as "no checkpoint" sentinel
            }
            if (VERSION_COUNTER.compareAndSet(cur, next)) {
                maybeWarnPerInstanceOverflow(next);
                // Lift the RuntimeReady pre-checkpoint gate. Before this
                // point, pre-hooks in instrumented JDK code early-return
                // inside RuntimeReady without touching any other runtime
                // class. See {@code RuntimeReady#VERSION_GATE} for why
                // this gate is load-bearing during JVM bootstrap.
                RuntimeReady.VERSION_GATE = (int) next;
                RuntimeReady.markReady();
                return (int) next;
            }
        }
    }

    private static void maybeWarnPerInstanceOverflow(long counter) {
        if (counter < PER_INSTANCE_WRAP_WARN_THRESHOLD) {
            return;
        }
        if (perInstanceOverflowWarned) {
            return;
        }
        if (Boolean.getBoolean("crochet.verboseCompat")) {
            perInstanceOverflowWarned = true;
            System.err.println("[crochet] VERSION_COUNTER crossed " + PER_INSTANCE_WRAP_WARN_THRESHOLD
                    + " (= 2^30). Per-instance $$crochetVersion (int) will wrap at 2^31;"
                    + " consider migrating to Path B (long per-instance field) if this run"
                    + " is expected to produce >2^31 checkpoints.");
        }
    }
}
