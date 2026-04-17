package net.jonbell.crochet.runtime;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SKETCH — not compiled. Design-only artifact for Gap 4 (array
 * checkpoint/rollback).
 *
 * <p>Global registry mapping each live array instance to its {@link ArrayMeta}.
 * The map is keyed by an identity-based weak reference so arrays that the user
 * drops can be garbage collected even though we hold metadata for them.
 *
 * <p>Call sites:
 * <ul>
 *   <li>Instrumented AASTORE/IASTORE/... call
 *       {@link #beforeStore(Object)} (or {@link #beforeStoreWide(Object)} for
 *       long/double values) immediately before the store lands.
 *   <li>{@link net.jonbell.crochet.runtime.CRIJInstrumented}
 *       {@code $$crochetPropagateCheckpoint} and {@code ...PropagateRollback}
 *       call {@link #propagateCheckpoint(Object, int)} /
 *       {@link #propagateRollback(Object, int)} for any array-typed field.
 * </ul>
 *
 * <p>Thread safety: a single per-array monitor (the {@link ArrayMeta} instance)
 * guards snapshot allocation and the System.arraycopy into/out of it. The
 * registry map itself is a {@link ConcurrentHashMap}.
 */
public final class ArrayRegistry {

    private ArrayRegistry() {}

    /* ------------------------------------------------------------ */
    /* Registry storage                                               */
    /* ------------------------------------------------------------ */

    private static final ReferenceQueue<Object> DEAD = new ReferenceQueue<>();
    private static final ConcurrentHashMap<IdKey, ArrayMeta> MAP = new ConcurrentHashMap<>();

    /** Weak identity-hashed key; == on the referent decides equality. */
    static final class IdKey extends WeakReference<Object> {
        private final int hash;
        IdKey(Object referent, ReferenceQueue<Object> q) {
            super(referent, q);
            this.hash = System.identityHashCode(referent);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof IdKey that)) return false;
            Object a = this.get();
            Object b = that.get();
            return a != null && a == b;
        }
    }

    /** Drain dead keys. Cheap — a spin on the ref queue. */
    private static void sweep() {
        Reference<?> r;
        while ((r = DEAD.poll()) != null) {
            MAP.remove(r);
        }
    }

    /** Return the meta for {@code array}, creating it if absent. */
    static ArrayMeta getOrCreate(Object array) {
        sweep();
        IdKey probe = new IdKey(array, DEAD);
        ArrayMeta m = MAP.get(probe);
        if (m != null) { probe.clear(); return m; }
        ArrayMeta created = new ArrayMeta(array);
        ArrayMeta prior = MAP.putIfAbsent(probe, created);
        return prior != null ? prior : created;
    }

    /* ------------------------------------------------------------ */
    /* Per-store barrier — called from instrumented bytecode         */
    /* ------------------------------------------------------------ */

    /**
     * Called immediately before AASTORE / IASTORE / FASTORE / BASTORE /
     * CASTORE / SASTORE. Takes the eager snapshot if this is the first store
     * after a checkpoint this array can see.
     */
    public static void beforeStore(Object array) {
        if (array == null) return;
        int v = CheckpointRollbackAgent.currentVersion();
        if (v == 0) return; // no checkpoint in flight
        snapshotIfNeeded(getOrCreate(array), array, v);
    }

    /** LASTORE/DASTORE variant — identical semantics, exposed for symmetry. */
    public static void beforeStoreWide(Object array) {
        beforeStore(array);
    }

    private static void snapshotIfNeeded(ArrayMeta m, Object live, int v) {
        if (m.snapVersion >= v && m.dirty) return;
        synchronized (m) {
            if (m.snapVersion >= v && m.dirty) return;
            int len = Array.getLength(live);
            if (m.snapshot == null || Array.getLength(m.snapshot) != len) {
                m.snapshot = Array.newInstance(
                        live.getClass().getComponentType(), len);
            }
            System.arraycopy(live, 0, m.snapshot, 0, len);
            m.snapVersion = v;
            m.dirty = true;
        }
    }

    /* ------------------------------------------------------------ */
    /* Reference-graph propagation                                   */
    /* ------------------------------------------------------------ */

    public static void propagateCheckpoint(Object array, int v) {
        if (array == null) return;
        ArrayMeta m = getOrCreate(array);
        snapshotIfNeeded(m, array, v);
        if (array instanceof Object[] refs) {
            for (Object e : refs) {
                if (e == null || e instanceof String || e instanceof Class<?>) continue;
                if (e instanceof CRIJInstrumented ci) {
                    if (ci.$$crochetGetVersion() < v) {
                        ci.$$crochetCheckpoint(v);
                        ci.$$crochetPropagateCheckpoint(v);
                    }
                } else if (e.getClass().isArray()) {
                    propagateCheckpoint(e, v);
                }
                // Non-instrumented plain object: V1 scope — skip.
            }
        }
    }

    public static void propagateRollback(Object array, int v) {
        if (array == null) return;
        ArrayMeta m = getOrCreate(array);
        synchronized (m) {
            if (m.dirty && m.snapshot != null && m.snapVersion == v - 1) {
                int len = Array.getLength(array);
                System.arraycopy(m.snapshot, 0, array, 0, len);
                m.dirty = false;
            }
        }
        if (array instanceof Object[] refs) {
            for (Object e : refs) {
                if (e == null || e instanceof String || e instanceof Class<?>) continue;
                if (e instanceof CRIJInstrumented ci) {
                    ci.$$crochetRollback(v);
                    ci.$$crochetPropagateRollback(v);
                } else if (e.getClass().isArray()) {
                    propagateRollback(e, v);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /* Meta record                                                   */
    /* ------------------------------------------------------------ */

    /**
     * Per-array bookkeeping. Instance identity is used as the monitor for
     * snapshot allocation and the {@code arraycopy} in/out.
     *
     * <p>Intentionally a class (not a record) so it can double as a monitor,
     * and so we can extend later with a per-slot undo log:
     * <pre>
     *   // future: long[] slotLog; int slotLogEntries;
     * </pre>
     */
    static final class ArrayMeta {
        /** Backing snapshot. Same component type, same length as live array. */
        volatile Object snapshot;
        /** Last checkpoint version at which snapshot was refreshed. */
        volatile int snapVersion;
        /** True if snapshot reflects a committed checkpoint awaiting rollback. */
        volatile boolean dirty;
        /** Component class, cached to avoid repeated {@code getClass()} calls. */
        final Class<?> componentType;

        ArrayMeta(Object array) {
            this.componentType = array.getClass().getComponentType();
        }
    }
}
