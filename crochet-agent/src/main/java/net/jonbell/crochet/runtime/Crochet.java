package net.jonbell.crochet.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.jonbell.crochet.annotation.Stable;

/**
 * User-facing facade over Crochet's checkpoint/diff API.
 *
 * <h2>Live-only contract</h2>
 *
 * <p>{@link #diff(Object)} and {@link #diffStatic(Class)} both operate
 * <em>only</em> on the current live snapshot — the single-slot
 * {@code $$crochetSnap} captured by the most recent
 * {@link CheckpointRollbackAgent#checkpoint(Object)} call on that object (or
 * class). If no checkpoint has been taken (snap is null), both methods return
 * an empty list rather than throwing.
 *
 * <p><b>What you get:</b> a list of {@link FieldDiff} entries for every
 * declared field whose value differs between the snapshot and the current live
 * state.
 *
 * <p><b>What you don't get:</b>
 * <ul>
 *   <li>Transitive / graph diffs. If field {@code f} points to another
 *       instrumented object, {@code diff} compares the <em>reference</em> in
 *       {@code f}, not the referent's internal fields. Call
 *       {@code Crochet.diff(obj.f)} separately if you need that.
 *   <li>Snap chains. Crochet uses a single snap slot; each checkpoint
 *       overwrites the previous one. {@code diff} reflects only the most
 *       recent checkpoint.
 *   <li>Array element diffs. Array-typed fields are treated as opaque
 *       references in v1 for reference arrays. Primitive arrays use
 *       element-level equality ({@link java.util.Arrays#equals}) so a
 *       different-identity copy with equal contents does not appear in the
 *       diff. No recursive element walk is performed.
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 *   Counter c = new Counter(1, "original");
 *   int v = CheckpointRollbackAgent.checkpoint(c);
 *   c.value = 42;
 *   c.label = "mutated";
 *
 *   // diff shows the two changed fields:
 *   for (FieldDiff d : Crochet.diff(c)) {
 *       System.out.println(d.fieldName() + ": " + d.snapValue() + " -> " + d.currentValue());
 *   }
 *   // output (order may vary):
 *   //   value: 1 -> 42
 *   //   label: original -> mutated
 *
 *   // No diff after rollback (snap is cleared):
 *   CheckpointRollbackAgent.rollback(c, v);
 *   Crochet.diff(c); // returns []
 * }</pre>
 *
 * <!-- TODO(A.4): apply @Stable once the annotation gate check is in place.
 *      This class already carries @Stable on FieldDiff; the facade methods are
 *      stable as of Phase A. -->
 */
// TODO(A.4): annotate with @Stable once unit/A.4-compose-kit lands.
public final class Crochet {

    private Crochet() {}

    // =========================================================================
    // D.1: External-state hook registry
    // =========================================================================

    /**
     * Registers an external-state hook under {@code name}.
     *
     * <p>The {@code snapshot} supplier is called <em>serially on the calling
     * thread, before {@link CheckpointRollbackAgent#checkpointAll()}'s root
     * walk</em>, so it sees the pre-checkpoint heap. Its return value —
     * typically a "savepoint handle" (a cursor position, a transaction
     * savepoint, a copy of a file-descriptor offset) — is stored and later
     * passed to {@code restore}.
     *
     * <p>The {@code restore} consumer is called <em>after
     * {@link CheckpointRollbackAgent#rollbackAll(int)}'s heap restore</em>, so
     * it sees the post-rollback heap. It receives the value returned by the
     * corresponding {@code snapshot} call.
     *
     * <p>If {@code snapshot} throws, the checkpoint is aborted (fail-fast; no
     * subsequent hooks run). If {@code restore} throws, the remaining restore
     * hooks still run, and a {@link RollbackException.HookFailure} is raised
     * after all hooks have been attempted.
     *
     * <p>Hooks fire in <em>registration order</em> (oldest first) for both
     * snapshot and restore passes. Registering a hook with the same {@code name}
     * as an existing hook replaces it (with a warning logged); the hook's
     * position in iteration order is preserved.
     *
     * <h2>No adapters in-tree</h2>
     *
     * <p><b>Crochet ships no JDBC, Redis, filesystem, or other adapters.</b>
     * The adapter long-tail is unbounded, and coupling Crochet to third-party
     * library ABIs would propagate breakage across unrelated users. This method
     * is the hook point; users are expected to own their adapter code. A typical
     * adapter is three lines:
     *
     * <pre>{@code
     *   // DB savepoint adapter (user code, not in Crochet):
     *   Crochet.registerExternalState("my-db",
     *       () -> connection.setSavepoint("crochet"),   // snapshot
     *       sp  -> connection.rollback(sp));            // restore
     * }</pre>
     *
     * <h2>Stability</h2>
     *
     * <p>This method is {@code @Stable} user-facing API. Its signature and
     * ordering contract will not change in a backwards-incompatible way.
     *
     * @param name     a unique name for this hook; used as the registry handle
     *                 and appears in failure messages. Duplicate names replace
     *                 the existing hook.
     * @param snapshot called before {@code checkpointAll}'s root walk; the
     *                 return value is passed to {@code restore}. Must not be
     *                 {@code null}.
     * @param restore  called after {@code rollbackAll}'s heap restore; receives
     *                 the value returned by the corresponding {@code snapshot}.
     *                 Must not be {@code null}.
     * @throws NullPointerException if {@code name}, {@code snapshot}, or
     *                              {@code restore} is {@code null}
     */
    public static void registerExternalState(String name,
                                             Supplier<?> snapshot,
                                             Consumer<?> restore) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(restore, "restore");
        ExternalStateRegistry.register(name, snapshot, restore);
    }

    /**
     * Removes the external-state hook registered under {@code name}.
     *
     * <p>No-op if no hook with that name is currently registered. Thread-safe;
     * may be called concurrently with {@link #registerExternalState}.
     *
     * <h2>Stability</h2>
     *
     * <p>This method is {@code @Stable} user-facing API.
     *
     * @param name the name passed to {@link #registerExternalState}; if
     *             {@code null}, this method is a no-op.
     */
    public static void unregisterExternalState(String name) {
        ExternalStateRegistry.unregister(name);
    }

    /**
     * Checkpoints the given root object and returns the version token.
     *
     * <p>This is the INVOKESTATIC target emitted by
     * {@link net.jonbell.crochet.transform.CheckpointWrapper} for methods
     * annotated with {@link net.jonbell.crochet.annotation.CrochetCheckpoint}.
     * Application code may also call it directly.
     *
     * @param root the object to checkpoint; must not be {@code null}
     * @return the checkpoint version token (pass to {@link #rollback} to restore)
     */
    @Stable
    public static int checkpoint(Object root) {
        return CheckpointRollbackAgent.checkpoint(root);
    }

    /**
     * Rolls the root object back to the snapshot taken at version {@code v}.
     *
     * <p>This is the INVOKESTATIC target emitted by
     * {@link net.jonbell.crochet.transform.CheckpointWrapper} for methods
     * annotated with {@link net.jonbell.crochet.annotation.CrochetCheckpoint}.
     * Application code may also call it directly.
     *
     * @param root the object to roll back; must not be {@code null}
     * @param v    the version token returned by the corresponding
     *             {@link #checkpoint(Object)} call
     */
    @Stable
    public static void rollback(Object root, int v) {
        CheckpointRollbackAgent.rollback(root, v);
    }

    /**
     * Returns the list of instance fields that differ between the live object
     * and its most-recently checkpointed snapshot.
     *
     * <p><b>Live-only:</b> if the object has no live checkpoint (its
     * {@code $$crochetSnap} slot is null, either because no checkpoint was ever
     * taken or because a rollback has already cleared it), this method returns
     * an empty list. It never throws in this case.
     *
     * <p><b>Non-instrumented objects:</b> if {@code obj} does not implement
     * {@link CRIJInstrumented} (i.e. Crochet was not attached or the class was
     * excluded from instrumentation), this method returns an empty list.
     *
     * <p><b>Cycle safety:</b> this method does not recurse into reference
     * fields. A self-edge or back-edge in the object graph produces no infinite
     * loop and no stack overflow.
     *
     * <p><b>Primitives:</b> primitive field values are boxed in the returned
     * {@link FieldDiff} records (e.g. {@code int} becomes {@link Integer}).
     *
     * <p><b>Primitive arrays:</b> compared element-by-element via
     * {@link java.util.Arrays#equals}. Two arrays with equal contents but
     * different identity will NOT appear in the diff.
     *
     * <p><b>Reference arrays:</b> compared as opaque references. A different
     * array object (even with the same element values) will appear in the diff.
     * Use {@link java.util.Arrays#equals} externally if you need element
     * comparison.
     *
     * @param obj the live object to inspect; must not be {@code null}
     * @return an unmodifiable list of {@link FieldDiff} entries, one per
     *         differing field; empty if no checkpoint is live
     * @throws NullPointerException if {@code obj} is null
     */
    public static List<FieldDiff> diff(Object obj) {
        Objects.requireNonNull(obj, "obj");
        if (!(obj instanceof CRIJInstrumented instrumented)) {
            return Collections.emptyList();
        }
        Object snap = instrumented.$$crochetGetSnap();
        if (snap == null) {
            return Collections.emptyList();
        }
        // Walk from the real user class (strip any Fast-proxy layer).
        Class<?> userClass = realUserClassOf(obj);
        List<FieldDiff> result = new ArrayList<>();
        walkInstanceFields(obj, snap, userClass, result);
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the list of static fields of {@code clazz} that differ between
     * the live class state and the most-recently checkpointed snapshot.
     *
     * <p>Uses the same field-discovery and equality logic as {@link #diff(Object)},
     * so the two methods share their field-walk code path for the purpose of
     * testing static-field diff equivalence.
     *
     * <p><b>Live-only:</b> if no checkpoint has been taken for the class's
     * static fields, returns an empty list.
     *
     * <p><b>Non-instrumented classes:</b> if the class has no SF helper or the
     * helper has no live snap, returns an empty list.
     *
     * @param clazz the class whose static fields to diff; must not be {@code null}
     * @return an unmodifiable list of {@link FieldDiff} entries for differing
     *         static fields; empty if no checkpoint is live
     * @throws NullPointerException if {@code clazz} is null
     */
    public static List<FieldDiff> diffStatic(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz");
        ClassMeta meta = ClassMeta.of(clazz);
        CRIJInstrumented helper = meta.sfHelper;
        if (helper == null) {
            // No helper yet — no checkpoint could have been taken for statics.
            return Collections.emptyList();
        }
        // The sfHelper uses EAGER snapshot semantics: StaticFieldHelperTemplate
        // emits $$crochetCheckpoint(v) to copy user-class static fields directly
        // into the helper's own mirror instance fields (not into a $$crochetSnap
        // shadow). So the snap values ARE the helper's own fields, populated only
        // after a checkpoint has been taken.
        //
        // We detect "has a checkpoint been taken" by checking the helper's version
        // field (non-zero iff $$crochetCheckpoint has fired at least once).
        if (helper.$$crochetGetVersion() == 0) {
            return Collections.emptyList();
        }
        // Walk the helper's mirror fields. For each one, compare helper.field
        // (the snap) against the live user-class static field.
        List<FieldDiff> result = new ArrayList<>();
        walkStaticFields(clazz, helper, result);
        return Collections.unmodifiableList(result);
    }

    // -------------------------------------------------------------------------
    // Internal field-walk helpers
    // -------------------------------------------------------------------------

    /**
     * Walks the declared instance fields of {@code clazz} and its
     * instrumented supers, comparing {@code live} against {@code snap} for
     * each field. Appends {@link FieldDiff} entries to {@code out} for fields
     * whose values differ.
     *
     * <p>Uses the same filter as {@link net.jonbell.crochet.transform.FieldAdder}:
     * non-static, non-final, non-synthetic, name does not start with
     * {@code $$crochet}. This ensures the diff covers exactly the fields that
     * checkpoint/rollback operate on.
     */
    private static void walkInstanceFields(Object live, Object snap,
                                           Class<?> clazz, List<FieldDiff> out) {
        if (clazz == null || clazz == Object.class) {
            return;
        }
        // Recurse to super first (mirrors $$crochetCopyFieldsTo's super chain).
        Class<?> sup = clazz.getSuperclass();
        if (sup != null && sup != Object.class && CRIJInstrumented.class.isAssignableFrom(sup)) {
            walkInstanceFields(live, snap, sup, out);
        }
        for (Field f : clazz.getDeclaredFields()) {
            if (!shouldIncludeInstanceField(f)) {
                continue;
            }
            f.setAccessible(true);
            try {
                Object snapVal = f.get(snap);
                Object liveVal = f.get(live);
                if (!fieldValuesEqual(f.getType(), snapVal, liveVal)) {
                    out.add(new FieldDiff(f.getName(), snapVal, liveVal));
                }
            } catch (IllegalAccessException e) {
                // Should not happen after setAccessible(true); skip silently.
            }
        }
    }

    /**
     * Walks the mirror instance fields of the static-field helper, comparing
     * each one against the live static field on the user class. Appends diffs
     * to {@code out}.
     *
     * <p>{@link net.jonbell.crochet.transform.StaticFieldHelperTemplate} emits
     * {@code $$crochetCheckpoint} to copy each user-class static field directly
     * into the helper's own instance field of the same name. The snap values
     * are therefore <em>the helper's instance fields themselves</em> — there is
     * no intermediate {@code $$crochetSnap} shadow for the static case. We read
     * snap values from {@code helper.field} and live values from the user
     * class's matching static field.
     *
     * <p>This is the shared code path with {@link #walkInstanceFields} — both
     * use the same equality logic ({@link #fieldValuesEqual}).
     */
    private static void walkStaticFields(Class<?> userClass,
                                         CRIJInstrumented helper,
                                         List<FieldDiff> out) {
        Class<?> helperClass = helper.getClass();
        for (Field hf : helperClass.getDeclaredFields()) {
            if (!shouldIncludeHelperField(hf)) {
                continue;
            }
            hf.setAccessible(true);
            // Find the matching static field on the user class.
            Field uf;
            try {
                uf = userClass.getDeclaredField(hf.getName());
            } catch (NoSuchFieldException e) {
                // Mirror field exists but user class field gone — skip.
                continue;
            }
            if (!Modifier.isStatic(uf.getModifiers())) {
                continue;
            }
            uf.setAccessible(true);
            try {
                // Snap value = what the helper stored at checkpoint time.
                Object snapVal = hf.get(helper);
                // Live value = current value of the user class's static field.
                Object liveVal = uf.get(null);
                if (!fieldValuesEqual(uf.getType(), snapVal, liveVal)) {
                    out.add(new FieldDiff(uf.getName(), snapVal, liveVal));
                }
            } catch (IllegalAccessException e) {
                // Should not happen after setAccessible(true); skip silently.
            }
        }
    }

    /**
     * True iff {@code f} should be included in the instance-field diff walk.
     * Mirrors the {@link net.jonbell.crochet.transform.FieldAdder} filter:
     * non-static, non-final, non-synthetic, name does not start with
     * {@code $$crochet}.
     */
    private static boolean shouldIncludeInstanceField(Field f) {
        int mod = f.getModifiers();
        if (Modifier.isStatic(mod)) return false;
        if (Modifier.isFinal(mod)) return false;
        if (f.isSynthetic()) return false;
        if (f.getName().startsWith("$$crochet")) return false;
        return true;
    }

    /**
     * True iff a helper instance field should be included in the static-field
     * diff walk. Excludes the CRIJ machinery fields ({@code $$crochetVersion},
     * {@code $$crochetSnap}) and any static fields; keeps the mirror fields
     * that correspond to user-class statics.
     */
    private static boolean shouldIncludeHelperField(Field f) {
        int mod = f.getModifiers();
        if (Modifier.isStatic(mod)) return false;
        // Helper fields are all public|synthetic per StaticFieldHelperTemplate.
        // The $$crochetVersion/$$crochetSnap mirror fields must be excluded.
        if (f.getName().startsWith("$$crochet")) return false;
        return true;
    }

    /**
     * Compares two field values for equality, using type-appropriate semantics.
     *
     * <ul>
     *   <li>Primitive fields: {@link Objects#equals} on the boxed values
     *       ({@link Field#get} always boxes).
     *   <li>Primitive array fields: the appropriate
     *       {@link java.util.Arrays#equals} overload. Two arrays with
     *       identical contents compare as equal; two arrays with the same
     *       elements but different identities also compare as equal. This
     *       matches rollback semantics — rollback restores the reference, so
     *       if the reference is unchanged the array is unchanged.
     *   <li>Reference array fields: compared as opaque references (identity
     *       via {@link Objects#equals}, which delegates to
     *       {@link Object#equals}).
     *   <li>All other reference fields: {@link Objects#equals}.
     * </ul>
     *
     * @param type     the declared type of the field
     * @param snapVal  the snapshotted value (may be null)
     * @param liveVal  the live value (may be null)
     * @return true iff the values are considered equal under the above rules
     */
    static boolean fieldValuesEqual(Class<?> type, Object snapVal, Object liveVal) {
        if (type.isPrimitive()) {
            // Boxed by Field.get(); Objects.equals handles null (impossible for
            // primitives in practice, but safe).
            return Objects.equals(snapVal, liveVal);
        }
        if (type.isArray() && type.getComponentType().isPrimitive()) {
            // Primitive arrays: element-level equality via Arrays.equals.
            if (snapVal == null && liveVal == null) return true;
            if (snapVal == null || liveVal == null) return false;
            return primitiveArrayEquals(type, snapVal, liveVal);
        }
        // Reference types (including reference arrays): reference-based equals.
        return Objects.equals(snapVal, liveVal);
    }

    /**
     * Dispatches to the correct {@link java.util.Arrays#equals} overload for
     * the given primitive array type.
     */
    private static boolean primitiveArrayEquals(Class<?> type, Object a, Object b) {
        if (type == int[].class)     return Arrays.equals((int[])    a, (int[])    b);
        if (type == long[].class)    return Arrays.equals((long[])   a, (long[])   b);
        if (type == double[].class)  return Arrays.equals((double[]) a, (double[]) b);
        if (type == float[].class)   return Arrays.equals((float[])  a, (float[])  b);
        if (type == boolean[].class) return Arrays.equals((boolean[])a, (boolean[])b);
        if (type == byte[].class)    return Arrays.equals((byte[])   a, (byte[])   b);
        if (type == char[].class)    return Arrays.equals((char[])   a, (char[])   b);
        if (type == short[].class)   return Arrays.equals((short[])  a, (short[])  b);
        // Should not reach here for primitive arrays.
        return Objects.equals(a, b);
    }

    /**
     * Walks the supertype chain past any Fast-proxy layers. Mirrors
     * {@link CheckpointRollbackAgent}'s private {@code realUserClassOf}.
     */
    private static Class<?> realUserClassOf(Object target) {
        Class<?> c = target.getClass();
        while (c != null && CRIJFast.class.isAssignableFrom(c)) {
            c = c.getSuperclass();
        }
        return c;
    }
}
