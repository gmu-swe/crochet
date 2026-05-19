package net.jonbell.crochet.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Registry for external-state hooks that participate in
 * {@link CheckpointRollbackAgent#checkpointAll()} /
 * {@link CheckpointRollbackAgent#rollbackAll(int)}.
 *
 * <h2>What this is</h2>
 *
 * <p>A lightweight, ordered registry of {@link Hook} entries. Each hook
 * captures external state (file-descriptor offsets, DB cursors, socket
 * buffers, Redis keys, etc.) that is invisible to Crochet's heap walk.
 * At {@code checkpointAll} time each hook's {@link Hook#snapshot()} runs
 * serially, before the root walk, on the calling thread. At
 * {@code rollbackAll} time each hook's {@link Hook#restore()} runs after the
 * heap has been restored, in the same registration order.
 *
 * <h2>Adapter refusal</h2>
 *
 * <p><b>This class ships no adapters.</b> No JDBC, Redis, or filesystem
 * implementations are provided in-tree. The adapter long-tail is unbounded,
 * and coupling Crochet to third-party library ABIs would make breakage from
 * one library version propagate to unrelated users. Users are expected to
 * own their adapter code; this registry is the hook point. Wiring an adapter
 * is three lines of user code:
 *
 * <pre>{@code
 *   Crochet.registerExternalState("my-db",
 *       () -> db.savepoint(),            // snapshot: returns the savepoint handle
 *       sp -> db.rollbackTo(sp));        // restore: accepts the savepoint handle
 * }</pre>
 *
 * <h2>Storage</h2>
 *
 * <p>Hooks are stored in a {@link CopyOnWriteArrayList} for wait-free snapshot
 * iteration (read-heavy, write-rare workload), backed by a
 * {@link ConcurrentHashMap} for O(1) duplicate detection and removal by name.
 * Registration order is the canonical iteration order for both snapshot and
 * restore passes.
 *
 * <h2>Ordering</h2>
 *
 * <p>Snapshot hooks fire in <em>registration order</em> (oldest first).
 * Restore hooks fire in the same registration order. This is symmetric and
 * predictable; hooks are not expected to have inter-hook dependencies.
 * If a hook needs LIFO restore semantics it can register two separate hooks
 * or reverse the order itself.
 *
 * <h2>Zero-allocation cold path</h2>
 *
 * <p>When no hooks are registered, both {@link #fireSnapshots()} and
 * {@link #fireRestores()} return immediately after a single volatile array-
 * length read on {@link CopyOnWriteArrayList#isEmpty()} — no iterator, no
 * array copy, no allocation. This satisfies universal gate 7.
 *
 * <h2>Snapshot-result plumbing</h2>
 *
 * <p>Each hook's {@code snapshot} {@link Supplier} may return a value (the
 * "savepoint handle") that is passed to its {@code restore} {@link Consumer}
 * at rollback time. The results array produced by {@link #fireSnapshots()} is
 * stored in {@link #lastSnapResults} and consumed by the next
 * {@link #fireRestores()} call. This field is {@code volatile} and written
 * atomically; under the paper's flat-nested, sequential checkpoint/rollback
 * model this is safe. Concurrent {@code checkpointAll} calls would overwrite
 * it — but the paper does not support concurrent checkpoints.
 *
 * <h2>Throws-in-snapshot</h2>
 *
 * <p>If any hook's {@code snapshot} throws, that exception propagates
 * immediately from {@link #fireSnapshots()} and no subsequent hook snapshots
 * run. The checkpoint is aborted; {@link #lastSnapResults} is set to
 * {@code null}. The heap version counter has already been bumped by
 * {@code checkpointAll} before the snapshot hooks fire — this is acceptable
 * under the same contract as class-walk failures, which also leave the version
 * bumped.
 *
 * <h2>Throws-in-restore</h2>
 *
 * <p>If any hook's {@code restore} throws, the exception is caught, and the
 * remaining restore hooks continue to run. After all hooks have been attempted,
 * if any threw, a {@link RollbackException.HookFailure} is thrown with all
 * collected exceptions attached via {@link Throwable#addSuppressed}. The
 * failing hook's name appears in the suppressed exception's message.
 */
final class ExternalStateRegistry {

    private ExternalStateRegistry() {}

    private static final Logger LOG = Logger.getLogger(ExternalStateRegistry.class.getName());

    /**
     * Internal hook record. The {@code restore} consumer is typed as
     * {@code Consumer<Object>} (erased from the user-provided wildcard) so we
     * can call it with the result of {@code snapshot.get()} without an
     * unchecked cast warning at the call site.
     */
    @SuppressWarnings("ClassCanBeRecord") // keep mutable-field option open for tests
    static final class Hook {
        final String name;
        final Supplier<?> snapshot;
        final Consumer<Object> restore;

        @SuppressWarnings("unchecked")
        Hook(String name, Supplier<?> snapshot, Consumer<?> restore) {
            this.name = name;
            this.snapshot = snapshot;
            this.restore = (Consumer<Object>) restore;
        }
    }

    /**
     * Ordered list of hooks. CopyOnWriteArrayList gives wait-free reads
     * (snapshot pass) with safe mutation under the monitor on {@link #LOCK}.
     */
    private static final CopyOnWriteArrayList<Hook> HOOKS = new CopyOnWriteArrayList<>();

    /**
     * Name-to-hook index for O(1) duplicate detection and removal.
     * Must be kept consistent with {@link #HOOKS} under {@link #LOCK}.
     */
    private static final ConcurrentHashMap<String, Hook> BY_NAME = new ConcurrentHashMap<>();

    /**
     * Mutation lock. Protects the HOOKS + BY_NAME pair during register/
     * unregister. The lock is never held during snapshot/restore dispatch.
     */
    private static final Object LOCK = new Object();

    /**
     * Last snapshot result array, produced by {@link #fireSnapshots()} and
     * consumed by the next {@link #fireRestores()}. {@code null} means either
     * no snapshot was taken or the registry was empty at checkpoint time.
     *
     * <p>{@code volatile} for safe publication between the
     * {@code checkpointAll} and {@code rollbackAll} threads (in practice the
     * same thread, but volatile is free insurance).
     */
    static volatile Object[] lastSnapResults;

    // -------------------------------------------------------------------------
    // Public mutation API
    // -------------------------------------------------------------------------

    /**
     * Registers a hook under {@code name}. If a hook with the same name is
     * already registered it is replaced (with a warning logged), preserving
     * stable iteration order: the existing slot is updated in place.
     *
     * @param name     unique name; used as the registry handle and appears in
     *                 failure messages
     * @param snapshot called before {@code checkpointAll}'s root walk; the
     *                 return value is passed to {@code restore}
     * @param restore  called after {@code rollbackAll}'s heap restore; receives
     *                 the value returned by the corresponding {@code snapshot}
     */
    static void register(String name, Supplier<?> snapshot, Consumer<?> restore) {
        if (name == null) throw new NullPointerException("hook name must not be null");
        if (snapshot == null) throw new NullPointerException("snapshot supplier must not be null");
        if (restore == null) throw new NullPointerException("restore consumer must not be null");
        Hook hook = new Hook(name, snapshot, restore);
        synchronized (LOCK) {
            Hook old = BY_NAME.put(name, hook);
            if (old != null) {
                LOG.warning("ExternalStateRegistry: replacing existing hook \"" + name + "\"");
                // Replace in-place in HOOKS to keep stable position.
                int idx = HOOKS.indexOf(old);
                if (idx >= 0) {
                    HOOKS.set(idx, hook);
                } else {
                    // Shouldn't happen, but be safe.
                    HOOKS.add(hook);
                }
            } else {
                HOOKS.add(hook);
            }
        }
    }

    /**
     * Removes the hook registered under {@code name}. No-op if not registered.
     *
     * @param name the name passed to {@link #register}
     */
    static void unregister(String name) {
        if (name == null) return;
        synchronized (LOCK) {
            Hook old = BY_NAME.remove(name);
            if (old != null) {
                HOOKS.remove(old);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch API (called from CheckpointRollbackAgent)
    // -------------------------------------------------------------------------

    /**
     * Fires all registered snapshot hooks in registration order.
     *
     * <p>Called from {@link CheckpointRollbackAgent#checkpointAll()} BEFORE
     * the root walk, so hooks see the pre-checkpoint heap. If any hook throws,
     * the exception propagates immediately (fail-fast), subsequent hooks do not
     * run, and {@link #lastSnapResults} is set to {@code null}.
     *
     * <p>Zero-allocation cold path: returns immediately when no hooks are
     * registered.
     */
    static void fireSnapshots() {
        if (HOOKS.isEmpty()) {
            lastSnapResults = null;
            return;
        }
        // Take a stable snapshot of the hook list for this pass.
        Object[] hooks = HOOKS.toArray();
        Object[] results = new Object[hooks.length];
        try {
            for (int i = 0; i < hooks.length; i++) {
                Hook h = (Hook) hooks[i];
                results[i] = h.snapshot.get();
            }
        } catch (Throwable t) {
            // Fail-fast: abort checkpoint.
            lastSnapResults = null;
            throw t;
        }
        lastSnapResults = results;
    }

    /**
     * Fires all registered restore hooks in registration order, passing each
     * the result produced by the corresponding snapshot.
     *
     * <p>Called from {@link CheckpointRollbackAgent#rollbackAll(int)} AFTER
     * the heap has been restored. All hooks are attempted even if some throw;
     * collected throws are surfaced as suppressed exceptions on a
     * {@link RollbackException.HookFailure}.
     *
     * <p>Zero-allocation cold path: returns immediately when no hooks are
     * registered.
     *
     * @throws RollbackException.HookFailure if any hook's restore threw; all
     *         exceptions attached via {@link Throwable#addSuppressed}
     */
    static void fireRestores() {
        if (HOOKS.isEmpty()) {
            return;
        }
        // Take a stable snapshot of the hook list for this pass.
        Object[] hooks = HOOKS.toArray();
        Object[] snapResults = lastSnapResults;
        // Clear eagerly so a second rollbackAll sees null.
        lastSnapResults = null;

        List<Throwable> failures = null;
        for (int i = 0; i < hooks.length; i++) {
            Hook h = (Hook) hooks[i];
            Object snapResult = (snapResults != null && i < snapResults.length) ? snapResults[i] : null;
            try {
                h.restore.accept(snapResult);
            } catch (Throwable t) {
                if (failures == null) {
                    failures = new ArrayList<>();
                }
                // Wrap with hook name so the user can identify the offending adapter.
                failures.add(new RuntimeException("hook \"" + h.name + "\" restore failed", t));
            }
        }
        if (failures != null) {
            RollbackException.HookFailure ex = new RollbackException.HookFailure(
                    failures.size() + " external-state hook(s) failed during rollback");
            for (Throwable f : failures) {
                ex.addSuppressed(f);
            }
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // Package-private accessors for tests
    // -------------------------------------------------------------------------

    /** Returns the current number of registered hooks. */
    static int size() {
        return HOOKS.size();
    }

    /** Removes all registered hooks. For test teardown only. */
    static void clearAll() {
        synchronized (LOCK) {
            HOOKS.clear();
            BY_NAME.clear();
            lastSnapResults = null;
        }
    }
}
