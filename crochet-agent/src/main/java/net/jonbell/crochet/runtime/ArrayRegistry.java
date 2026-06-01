package net.jonbell.crochet.runtime;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.jonbell.crochet.annotation.Internal;

/**
 * Gap 4 (bytecode): per-array metadata registry.
 *
 * <p>Weak-keyed, identity-hashed map from array instance to its snapshot
 * metadata. {@link #beforeStore} is called from the emitted xASTORE pre-hook;
 * {@link #registerForCheckpoint} / {@link #rollback} are called from the
 * user-facing checkpoint/rollback paths.
 *
 * <p><b>Reflective graph fallback</b>: {@link #propagateCheckpoint} /
 * {@link #propagateRollback} walk direct array-typed fields of the root. When
 * the system property {@code crochet.reflectiveGraphFallback=true} is set, the
 * walk additionally recurses through non-{@link CRIJInstrumented} reference
 * fields (skipping String / boxed primitives / JDK value types), so arrays
 * buried inside uninstrumented JDK objects are registered too. Cycle
 * termination is via an {@link IdentityHashMap} seen-set. This is OFF by
 * default because the walk is substantially slower than the direct-field
 * path.
 */
@Internal
public final class ArrayRegistry {

    private ArrayRegistry() {}

    /**
     * Opt-in reflective graph fallback. Walks non-{@code CRIJInstrumented}
     * reference fields of the root to discover arrays buried inside JDK
     * objects the selective JDK pipeline (Gap 7) left uninstrumented.
     *
     * <p>Read once at class-init to avoid a volatile load per
     * {@code propagate*} call on hot paths.
     */
    private static final boolean REFLECTIVE_GRAPH_FALLBACK =
            Boolean.getBoolean("crochet.reflectiveGraphFallback");

    /**
     * Depth cap for the reflective walk. Deep JDK object graphs (e.g.
     * {@code ThreadGroup} chains, classloader parent pointers) can blow the
     * stack without one. 16 is empirically more than enough for the shallow
     * graphs the fallback is intended to cover (arrays hanging off collection
     * backing nodes) while cheap to budget.
     */
    private static final int REFLECTIVE_MAX_DEPTH = 16;

    static final class IdKey extends WeakReference<Object> {
        private final int hash;

        IdKey(Object referent, ReferenceQueue<Object> q) {
            super(referent, q);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (!(o instanceof IdKey other)) return false;
            Object a = this.get();
            Object b = other.get();
            return a != null && a == b;
        }
    }

    static final class ArrayMeta {
        volatile int ckptVersion;
        volatile Object snapshot;
        volatile boolean dirty;
    }

    private static final ConcurrentHashMap<IdKey, ArrayMeta> META = new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();

    private static final class ProbeKey {
        final Object array;
        final int hash;

        ProbeKey(Object array) {
            this.array = array;
            this.hash = System.identityHashCode(array);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof IdKey other) {
                return this.array == other.get();
            }
            return false;
        }
    }

    private static void drainQueue() {
        Reference<?> ref;
        while ((ref = QUEUE.poll()) != null) {
            META.remove(ref);
        }
    }

    private static ArrayMeta metaFor(Object array) {
        drainQueue();
        ProbeKey probe = new ProbeKey(array);
        ArrayMeta m = META.get(probe);
        if (m != null) {
            return m;
        }
        ArrayMeta candidate = new ArrayMeta();
        IdKey key = new IdKey(array, QUEUE);
        ArrayMeta prior = META.putIfAbsent(key, candidate);
        return prior != null ? prior : candidate;
    }

    /**
     * Force-load the inner-class dependency closure of ArrayRegistry
     * ({@code ProbeKey}, {@code IdKey}, {@code ArrayMeta}) by exercising
     * {@link #metaFor} once. Called from
     * {@link net.jonbell.crochet.agent.CrochetAgent#install} so the first
     * post-checkpoint {@code beforeStore} call never triggers a lazy
     * inner-class load while the caller is already inside
     * {@link net.jonbell.crochet.agent.TransformerWrapper#transform} —
     * which recursively re-enters the transformer and fires
     * {@link ClassCircularityError}.
     *
     * <p>The {@code new Object[0]} probe flows through the full path:
     * {@code metaFor} → {@code new ProbeKey}, {@code META.get}, and
     * (on miss) {@code new IdKey} / {@code new ArrayMeta}. After return
     * the probe and its meta are eligible for GC (META's keys are
     * {@link WeakReference}s), so this leaves no runtime state behind.
     */
    public static void warmup() {
        metaFor(new Object[0]);
    }

    public static void registerForCheckpoint(Object array, int v) {
        if (array == null) {
            return;
        }
        ArrayMeta m = metaFor(array);
        synchronized (m) {
            if (m.ckptVersion == v) {
                return;
            }
            m.ckptVersion = v;
            // Eager snapshot. The per-slot xASTORE pre-hook
            // ({@link #beforeStore}) handles the common case, but JDK
            // classes reach into arrays through {@code Unsafe.compareAndSet*}
            // and {@code VarHandle.set*} — both of which bypass xASTORE
            // entirely. {@link java.util.concurrent.ConcurrentHashMap#casTabAt}
            // is the motivating example: every table-slot mutation goes
            // through {@code U.compareAndSetReference}, so a lazy "snap on
            // first write" strategy would miss all of them and rollback
            // would return the current array contents unchanged.
            //
            // Paying the {@link System#arraycopy} up front guarantees we
            // have the pre-state regardless of which write mechanism the
            // owner uses. For small backing arrays (paper §5.1 sizes 10-100,
            // which correspond to CHM tables of 16-128 slots) the copy is a
            // handful of words; for large arrays this is an eager-mode
            // perf trade, but correctness is the gating concern this round.
            int len = java.lang.reflect.Array.getLength(array);
            Class<?> componentType = array.getClass().getComponentType();
            Object copy = java.lang.reflect.Array.newInstance(componentType, len);
            System.arraycopy(array, 0, copy, 0, len);
            m.snapshot = copy;
            m.dirty = true;
        }
    }

    public static void beforeStore(Object array) {
        if (array == null) {
            return;
        }
        ArrayMeta m = metaFor(array);
        if (m.ckptVersion == 0 || m.dirty) {
            return;
        }
        synchronized (m) {
            if (m.dirty || m.ckptVersion == 0) {
                return;
            }
            int len = java.lang.reflect.Array.getLength(array);
            Class<?> componentType = array.getClass().getComponentType();
            Object copy = java.lang.reflect.Array.newInstance(componentType, len);
            System.arraycopy(array, 0, copy, 0, len);
            m.snapshot = copy;
            m.dirty = true;
        }
    }

    public static void rollback(Object array, int v) {
        if (array == null) {
            return;
        }
        ArrayMeta m = metaFor(array);
        synchronized (m) {
            if (m.ckptVersion != v || !m.dirty) {
                m.ckptVersion = 0;
                m.snapshot = null;
                m.dirty = false;
                return;
            }
            int len = java.lang.reflect.Array.getLength(m.snapshot);
            System.arraycopy(m.snapshot, 0, array, 0, len);
            m.ckptVersion = 0;
            m.snapshot = null;
            m.dirty = false;
        }
    }

    /**
     * Unregister {@code array} entirely. Used when
     * {@link CheckpointRollbackAgent#checkpointArray} needs to eagerly snap
     * the array's contents (not just arm the pre-store hook) so the caller
     * can mutate via any path and still roll back — historically the
     * reflective {@code ARRAY_SNAPS} codepath. Leaves dirty=true so
     * {@link #rollback} copies the snapshot back.
     */
    public static void snapNow(Object array, int v) {
        if (array == null) {
            return;
        }
        ArrayMeta m = metaFor(array);
        synchronized (m) {
            m.ckptVersion = v;
            int len = java.lang.reflect.Array.getLength(array);
            Class<?> componentType = array.getClass().getComponentType();
            Object copy = java.lang.reflect.Array.newInstance(componentType, len);
            System.arraycopy(array, 0, copy, 0, len);
            m.snapshot = copy;
            m.dirty = true;
        }
    }

    public static void propagateCheckpoint(Object root, int v) {
        if (root == null) {
            return;
        }
        if (root.getClass().isArray()) {
            registerForCheckpoint(root, v);
            propagateArrayElementsCheckpoint(root, v);
            return;
        }
        List<java.lang.reflect.Field> fields = arrayFieldsOf(root.getClass());
        for (java.lang.reflect.Field f : fields) {
            try {
                Object val = f.get(root);
                if (val != null) {
                    registerForCheckpoint(val, v);
                    propagateArrayElementsCheckpoint(val, v);
                }
            } catch (ReflectiveOperationException ignore) {
            }
        }
        if (REFLECTIVE_GRAPH_FALLBACK) {
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            seen.put(root, Boolean.TRUE);
            for (java.lang.reflect.Field f : referenceFieldsOf(root.getClass())) {
                try {
                    Object val = f.get(root);
                    reflectiveWalkCheckpoint(val, v, seen, 1);
                } catch (ReflectiveOperationException ignore) {
                }
            }
        }
    }

    /**
     * Walk {@code root}'s array-typed fields, restore each registered array
     * from its checkpoint snapshot, and propagate {@code $$crochetRollback}
     * into the (now-restored) array's elements.
     *
     * @param v  the checkpoint version the array snapshot was registered
     *           under. Used to match {@link #rollback(Object, int)}'s
     *           {@code m.ckptVersion == v} guard.
     * @param rv the rollback version just issued by
     *           {@link VersionCounter#nextRollbackVersion}. Passed through
     *           to each element's {@code $$crochetRollback(rv)} so the
     *           I2 monotone-guard inside the element's version-guarded
     *           entry admits the call (its node.version was bumped to
     *           {@code v} at checkpoint time, and the rollback needs a
     *           strictly-greater version to pass the {@code realV < rv}
     *           guard).
     */
    public static void propagateRollback(Object root, int v, int rv) {
        if (root == null) {
            return;
        }
        if (root.getClass().isArray()) {
            propagateArrayElementsRollback(root, rv);
            rollback(root, v);
            return;
        }
        List<java.lang.reflect.Field> fields = arrayFieldsOf(root.getClass());
        for (java.lang.reflect.Field f : fields) {
            try {
                Object val = f.get(root);
                if (val != null) {
                    // Propagate into the CURRENT (possibly mutated) array
                    // contents first so any element-level $$crochetRollback
                    // runs before the array itself is restored to its
                    // snapshot state. Restoring the array reference to the
                    // snapshot before walking it would leave post-checkpoint
                    // writes on the restored entries un-rolled-back.
                    propagateArrayElementsRollback(val, rv);
                    rollback(val, v);
                }
            } catch (ReflectiveOperationException ignore) {
            }
        }
        if (REFLECTIVE_GRAPH_FALLBACK) {
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            seen.put(root, Boolean.TRUE);
            for (java.lang.reflect.Field f : referenceFieldsOf(root.getClass())) {
                try {
                    Object val = f.get(root);
                    reflectiveWalkRollback(val, v, seen, 1);
                } catch (ReflectiveOperationException ignore) {
                }
            }
        }
    }

    /**
     * Overload preserved for existing callers (e.g. tests / legacy code
     * paths) that don't have a rollback version on hand. Synthesises
     * {@code rv} as {@code v + 1} — correct only when the caller is
     * dispatching outside the {@link CheckpointRollbackAgent#rollback}
     * orchestration. Prefer the 3-arg overload from the main rollback path.
     */
    public static void propagateRollback(Object root, int v) {
        propagateRollback(root, v, v + 1);
    }

    /**
     * Walk {@code arr}'s elements and call {@code $$crochetCheckpoint(v)} on
     * each {@link CRIJInstrumented} entry. For {@code HashMap.table},
     * {@code ConcurrentHashMap.table}, {@code ArrayList.elementData}, etc.
     * the entries hold the live object state — without this, rollback would
     * only restore the array slots, leaving any post-checkpoint writes on
     * the entries themselves (e.g. {@code Node.value = newValue}, linked-list
     * {@code next} rewiring) unreverted.
     *
     * <p>Primitive-element arrays and non-reference arrays have no
     * instrumented elements, so we early-out via the component-type check.
     */
    /**
     * Called from the emitted {@code $$crochetPropagateCheckpoint} body for
     * reference-element array fields. Registers the array for snapshot
     * capture and propagates {@code $$crochetCheckpoint(v)} into each
     * {@link CRIJInstrumented} element — mirrors the direct-field branch
     * of {@link #propagateCheckpoint} but reachable through instance-level
     * propagation (so e.g. a user class holding a {@code HashMap} walks
     * into that HashMap's {@code table} array too).
     */
    public static void propagateArrayCheckpoint(Object arr, int v) {
        if (arr == null) {
            return;
        }
        registerForCheckpoint(arr, v);
        propagateArrayElementsCheckpoint(arr, v);
    }

    public static void propagateArrayRollback(Object arr, int v) {
        if (arr == null) {
            return;
        }
        propagateArrayElementsRollback(arr, v);
        rollback(arr, v - 1); // checkpoint version was rv - 1
    }

    private static void propagateArrayElementsCheckpoint(Object arr, int v) {
        if (arr == null) {
            return;
        }
        Class<?> c = arr.getClass();
        if (!c.isArray()) {
            return;
        }
        Class<?> comp = c.getComponentType();
        if (comp == null || comp.isPrimitive()) {
            return;
        }
        Object[] elems = (Object[]) arr;
        for (Object e : elems) {
            if (e instanceof CRIJInstrumented i) {
                try {
                    i.$$crochetCheckpoint(v);
                } catch (Throwable ignore) {
                    // A per-element failure (e.g. a hostile override throwing
                    // from $$crochetCheckpoint) must not abort the walk — the
                    // remaining entries still need propagation for rollback
                    // correctness.
                }
            }
        }
    }

    private static void propagateArrayElementsRollback(Object arr, int v) {
        if (arr == null) {
            return;
        }
        Class<?> c = arr.getClass();
        if (!c.isArray()) {
            return;
        }
        Class<?> comp = c.getComponentType();
        if (comp == null || comp.isPrimitive()) {
            return;
        }
        Object[] elems = (Object[]) arr;
        for (Object e : elems) {
            if (e instanceof CRIJInstrumented i) {
                try {
                    i.$$crochetRollback(v);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static void reflectiveWalkCheckpoint(Object o, int v,
                                                 IdentityHashMap<Object, Boolean> seen,
                                                 int depth) {
        if (o == null || depth > REFLECTIVE_MAX_DEPTH) {
            return;
        }
        if (seen.put(o, Boolean.TRUE) != null) {
            return;
        }
        Class<?> c = o.getClass();
        if (c.isArray()) {
            // Eagerly snapshot: JDK classes run through the minimal pipeline
            // (no xASTORE pre-hook), so writes from inside JDK methods bypass
            // the lazy snap machinery. Do a full System.arraycopy up front
            // to guarantee restore fidelity. If it's an Object[], recurse
            // into its elements — they may reference further mutable state.
            snapNow(o, v);
            if (!c.getComponentType().isPrimitive()) {
                Object[] arr = (Object[]) o;
                for (Object e : arr) {
                    reflectiveWalkCheckpoint(e, v, seen, depth + 1);
                }
            }
            return;
        }
        if (isOpaqueLeaf(c)) {
            return;
        }
        // Walk reference fields of both instrumented and uninstrumented
        // objects: instrumented objects already propagate their own version
        // via $$crochetPropagateCheckpoint, but that emission only recurses
        // into reference fields whose STATIC type is CRIJInstrumented — it
        // cannot see array-typed fields buried behind a reference to a
        // non-instrumented referent (ArrayList#elementData etc.). The
        // reflective fallback explicitly covers the gap.
        for (java.lang.reflect.Field f : referenceFieldsOf(c)) {
            try {
                Object val = f.get(o);
                reflectiveWalkCheckpoint(val, v, seen, depth + 1);
            } catch (ReflectiveOperationException ignore) {
            } catch (RuntimeException ignore) {
                // InaccessibleObjectException etc. — skip.
            }
        }
    }

    private static void reflectiveWalkRollback(Object o, int v,
                                               IdentityHashMap<Object, Boolean> seen,
                                               int depth) {
        if (o == null || depth > REFLECTIVE_MAX_DEPTH) {
            return;
        }
        if (seen.put(o, Boolean.TRUE) != null) {
            return;
        }
        Class<?> c = o.getClass();
        if (c.isArray()) {
            rollback(o, v);
            if (!c.getComponentType().isPrimitive()) {
                Object[] arr = (Object[]) o;
                for (Object e : arr) {
                    reflectiveWalkRollback(e, v, seen, depth + 1);
                }
            }
            return;
        }
        if (isOpaqueLeaf(c)) {
            return;
        }
        for (java.lang.reflect.Field f : referenceFieldsOf(c)) {
            try {
                Object val = f.get(o);
                reflectiveWalkRollback(val, v, seen, depth + 1);
            } catch (ReflectiveOperationException ignore) {
            } catch (RuntimeException ignore) {
            }
        }
    }

    /**
     * True for classes whose interior state we never need to reach into:
     * immutable value types and small numeric holders. Prevents descending
     * into {@code Class}, {@code ClassLoader} (huge graphs), interned
     * {@code String} pools, etc.
     */
    private static boolean isOpaqueLeaf(Class<?> c) {
        if (c == String.class) return true;
        if (c == Integer.class || c == Long.class || c == Short.class
                || c == Byte.class || c == Character.class || c == Boolean.class
                || c == Float.class || c == Double.class) {
            return true;
        }
        if (c == Class.class) return true;
        if (ClassLoader.class.isAssignableFrom(c)) return true;
        String name = c.getName();
        // Avoid descending into JDK lock primitives and the reflection /
        // invoke machinery — they contain back-pointers that blow up cost
        // without yielding interesting mutable-array state.
        if (name.startsWith("java.lang.invoke.")) return true;
        if (name.startsWith("java.lang.reflect.")) return true;
        if (name.startsWith("java.util.concurrent.locks.")) return true;
        return false;
    }

    private static final ClassValue<List<java.lang.reflect.Field>> ARRAY_FIELDS_CACHE = new ClassValue<>() {
        @Override
        protected List<java.lang.reflect.Field> computeValue(Class<?> type) {
            List<java.lang.reflect.Field> out = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.isSynthetic()) continue;
                    if (f.getName().startsWith("$$crochet")) continue;
                    if (!f.getType().isArray()) continue;
                    try {
                        f.setAccessible(true);
                    } catch (RuntimeException ignore) {
                        continue;
                    }
                    out.add(f);
                }
            }
            return Collections.unmodifiableList(out);
        }
    };

    private static List<java.lang.reflect.Field> arrayFieldsOf(Class<?> c) {
        return ARRAY_FIELDS_CACHE.get(c);
    }

    /**
     * All non-static, non-synthetic, non-$$crochet reference fields along the
     * superclass chain. Used only on the reflective-fallback cold path.
     * Cached per class because {@code Class.getDeclaredFields} is not cheap.
     */
    private static final ClassValue<List<java.lang.reflect.Field>> REFERENCE_FIELDS_CACHE = new ClassValue<>() {
        @Override
        protected List<java.lang.reflect.Field> computeValue(Class<?> type) {
            List<java.lang.reflect.Field> out = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.isSynthetic()) continue;
                    if (f.getName().startsWith("$$crochet")) continue;
                    if (f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                    } catch (RuntimeException ignore) {
                        continue;
                    }
                    out.add(f);
                }
            }
            return Collections.unmodifiableList(out);
        }
    };

    private static List<java.lang.reflect.Field> referenceFieldsOf(Class<?> c) {
        return REFERENCE_FIELDS_CACHE.get(c);
    }
}
