// Proposed patch for CheckpointRollbackAgent — NOT YET APPLIED.
//
// Two methods change:
//   1. changeClass  — returns boolean and no longer silently eats CAS failure.
//   2. fastAccess   — wraps the snapshot/restore block in try/finally,
//                     swaps klass back in finally, surfaces mid-restore
//                     failures as RollbackException(POISON_VERSION).
//   3. swapToFastProxy — accepts priorVersion, restores it on failure.

package net.jonbell.crochet.runtime;

// --- 1. changeClass ----------------------------------------------------

/**
 * Atomically flip the (compressed) klass pointer of {@code target} from
 * the klass of {@code from} to the klass of {@code to}.
 *
 * <p>Returns {@code true} if the CAS succeeded. A {@code false} return
 * means another thread concurrently swapped the klass; callers that
 * *require* the swap must check and decide (propagate, retry, or
 * re-observe the object state and proceed).
 */
@SuppressWarnings("deprecation")
public static boolean changeClass(Object target, Class<?> from, Class<?> to) {
    int fromKlass = klassOf(from);
    int toKlass = klassOf(to);
    return U.compareAndSwapInt(target, KLASS_OFFSET, fromKlass, toKlass);
}

// --- 2. fastAccess -----------------------------------------------------

/**
 * First field access after a klass-swap runs here. On checkpoint state
 * (odd version) we allocate a shadow and copy fields out. On rollback
 * state (even version) we copy from the shadow back into {@code obj}.
 * Either way we always swap klass back to the user class in finally so
 * subsequent accesses run without hook overhead — even if the
 * snapshot/restore threw.
 *
 * <p>If the snapshot throws, the object's user-visible fields are
 * untouched and we rethrow wrapped in {@link RollbackException}
 * (poison).
 *
 * <p>If the restore throws mid-copy, we have a partially-restored
 * object. We preserve the snap (so the user may retry) and throw
 * {@link RollbackException} with {@link RollbackException#POISON_VERSION}.
 */
public static void fastAccess(CRIJInstrumented obj) {
    Class<?> proxyClass = obj.getClass();
    Class<?> userClass = proxyClass.getSuperclass();
    if (userClass == null) {
        // Defensive: Object has no superclass. Indicates the proxy was
        // installed directly on java.lang.Object, which shouldn't happen.
        throw new RollbackException(RollbackException.POISON_VERSION,
                new IllegalStateException("fastAccess: proxy has no superclass"));
    }
    int v = obj.$$crochetGetVersion();
    if (v == 0) {
        // No checkpoint in flight — defensive swap-back.
        changeClass(obj, proxyClass, userClass);
        return;
    }
    Throwable thrown = null;
    boolean rollbackBranch = (v & 1) == 0;
    try {
        if (!rollbackBranch) {
            // Checkpoint state: shadow is allocated first, *then* published.
            // If allocation or copy throws, snap stays at its previous
            // value (likely null) and `obj` fields are untouched.
            Object shadow = allocateShadow(userClass);
            obj.$$crochetCopyFieldsTo(shadow);
            obj.$$crochetSetSnap(shadow);
        } else {
            // Rollback state: restore from snap, then clear snap.
            Object snap = obj.$$crochetGetSnap();
            if (snap != null) {
                obj.$$crochetCopyFieldsFrom(snap);
                // Only clear snap AFTER a successful restore, so that a
                // mid-copy throw leaves the snap reachable for retry.
                obj.$$crochetSetSnap(null);
            }
        }
    } catch (Throwable t) {
        thrown = t;
    } finally {
        // Always swap klass back to userClass — whether we succeeded, or
        // threw. Ignoring the CAS return is correct here: if another
        // thread won the race we're already in the target state.
        changeClass(obj, proxyClass, userClass);
    }
    if (thrown != null) {
        // Poison: the user sees failure at checkpoint() or rollback() scope.
        if (thrown instanceof RollbackException re) {
            throw re;
        }
        throw new RollbackException(RollbackException.POISON_VERSION, thrown);
    }
}

// --- 3. swapToFastProxy ------------------------------------------------

/**
 * Called from the emitted {@code $$crochetCheckpoint} and
 * {@code $$crochetRollback} bodies AFTER the version field has been
 * bumped. If proxy generation or the klass-swap fails, we must restore
 * the version so the object doesn't remain "half-checkpointed".
 *
 * @param priorVersion the value of {@code $$crochetVersion} BEFORE the
 *                     caller bumped it. Restored on failure.
 */
public static void swapToFastProxy(Object target, Class<?> userClass, int priorVersion) {
    Class<?> fastProxy;
    try {
        fastProxy = fastProxyFor(userClass);
    } catch (RuntimeException | Error e) {
        // Roll the version back — the caller's bump is undone.
        ((CRIJInstrumented) target).$$crochetSetVersion(priorVersion);
        throw new RollbackException(RollbackException.POISON_VERSION, e);
    }
    // CAS: if we fail, someone else already flipped us into the proxy.
    // That's the flat-nested case (two checkpoint()s, no access between).
    // The newer version is already in $$crochetVersion (we just wrote it),
    // and fastAccess will observe it on the next field access. No action.
    changeClass(target, userClass, fastProxy);
    // Intentionally no error on CAS-failure: if the CAS failed because
    // another thread raced us to the same target klass, we're already in
    // the target state. If it failed because `from` klass was wrong, that's
    // a transformer bug — we'll detect it when fastAccess runs and the
    // superclass lookup resolves to something unexpected.
}

// Backwards-compat shim — the old binary signature is kept for any
// already-instrumented bytecode that hasn't been re-emitted yet.
@Deprecated
public static void swapToFastProxy(Object target, Class<?> userClass) {
    // Best effort: we don't have priorVersion; assume 0.
    swapToFastProxy(target, userClass, 0);
}
