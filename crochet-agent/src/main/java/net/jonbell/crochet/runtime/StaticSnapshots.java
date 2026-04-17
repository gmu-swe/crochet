package net.jonbell.crochet.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gap 3: reflective static-field checkpoint / rollback. Per-class reflective
 * static-field snapshots. Separate from {@link ArrayRegistry} (which tracks
 * per-instance array state) and from the bytecode-level {@code sfHelper} path
 * (which operates on materialised hidden-helper instances). This map is only
 * populated by the direct {@link #checkpointStatics} / {@link #rollbackStatics}
 * entrypoints, used by clients that want a one-shot reflective snapshot
 * without paying the helper-generation cost.
 *
 * <p>Changed from {@code synchronizedMap(new IdentityHashMap<>())} to a
 * {@link ConcurrentHashMap}: {@link Class} keys already have identity
 * equality via {@link Object#equals}, and CHM avoids the coarse monitor
 * that {@code synchronizedMap} takes on every put/get.
 */
final class StaticSnapshots {

    private StaticSnapshots() {}

    private static final ConcurrentHashMap<Class<?>, Map<String, Object>> STATIC_FIELD_SNAPS =
            new ConcurrentHashMap<>();

    static int checkpointStatics(Class<?> c) {
        int v = VersionCounter.nextCheckpointVersion();
        Map<String, Object> snap = new LinkedHashMap<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    || Modifier.isFinal(f.getModifiers())
                    || f.isSynthetic()
                    || f.getName().startsWith("$$crochet")) {
                continue;
            }
            try {
                f.setAccessible(true);
                snap.put(f.getName(), f.get(null));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("checkpointStatics read failed: " + c.getName() + "." + f.getName(), e);
            }
        }
        STATIC_FIELD_SNAPS.put(c, snap);
        return v;
    }

    static void rollbackStatics(Class<?> c, int v) {
        VersionCounter.nextRollbackVersion();
        Map<String, Object> snap = STATIC_FIELD_SNAPS.remove(c);
        if (snap == null) {
            return;
        }
        for (Map.Entry<String, Object> e : snap.entrySet()) {
            try {
                Field f = c.getDeclaredField(e.getKey());
                f.setAccessible(true);
                f.set(null, e.getValue());
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("rollbackStatics write failed: " + c.getName() + "." + e.getKey(), ex);
            }
        }
    }
}
