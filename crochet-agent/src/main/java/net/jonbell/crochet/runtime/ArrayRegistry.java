package net.jonbell.crochet.runtime;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gap 4 (bytecode): per-array metadata registry.
 *
 * <p>Weak-keyed, identity-hashed map from array instance to its snapshot
 * metadata. {@link #beforeStore} is called from the emitted xASTORE pre-hook;
 * {@link #registerForCheckpoint} / {@link #rollback} are called from the
 * user-facing checkpoint/rollback paths.
 */
public final class ArrayRegistry {

    private ArrayRegistry() {}

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
            m.snapshot = null;
            m.dirty = false;
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

    public static void propagateCheckpoint(Object root, int v) {
        if (root == null) {
            return;
        }
        if (root.getClass().isArray()) {
            registerForCheckpoint(root, v);
            return;
        }
        List<java.lang.reflect.Field> fields = arrayFieldsOf(root.getClass());
        for (java.lang.reflect.Field f : fields) {
            try {
                Object val = f.get(root);
                if (val != null) {
                    registerForCheckpoint(val, v);
                }
            } catch (ReflectiveOperationException ignore) {
            }
        }
    }

    public static void propagateRollback(Object root, int v) {
        if (root == null) {
            return;
        }
        if (root.getClass().isArray()) {
            rollback(root, v);
            return;
        }
        List<java.lang.reflect.Field> fields = arrayFieldsOf(root.getClass());
        for (java.lang.reflect.Field f : fields) {
            try {
                Object val = f.get(root);
                if (val != null) {
                    rollback(val, v);
                }
            } catch (ReflectiveOperationException ignore) {
            }
        }
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
}
