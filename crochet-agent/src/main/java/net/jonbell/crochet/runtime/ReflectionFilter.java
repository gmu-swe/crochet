package net.jonbell.crochet.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/**
 * Runtime-side reflection filter that hides CROCHET's injected members from
 * user code that enumerates fields / methods / interfaces via
 * {@link Class#getDeclaredFields()} &amp; friends.
 *
 * <p>Our agent injects these members into every non-skipped instrumented class
 * (see {@link net.jonbell.crochet.transform.FieldAdder}):
 * <ul>
 *   <li>{@code private transient synthetic int $$crochetVersion}</li>
 *   <li>{@code private transient synthetic Object $$crochetSnap}</li>
 *   <li>public synthetic {@code $$crochet*} methods declared by
 *       {@link CRIJInstrumented}</li>
 *   <li>{@link CRIJInstrumented} appended to {@code implements}</li>
 *   <li>{@code @net.jonbell.crochet.annotation.CrochetInstrumented} stamp</li>
 * </ul>
 *
 * <p>Frameworks that enumerate fields, methods, or interfaces via reflection
 * (Weld, Hibernate, Jackson, ByteBuddy, h2o's Schema) see these extra members
 * and break. Filtering the results at runtime — transparent to user code —
 * is the root-cause fix; the alternative (adding the owner class to the
 * skip-list of {@link net.jonbell.crochet.transform.CrochetTransformer})
 * excludes the class from checkpoint/rollback entirely.
 *
 * <p>Filter semantics are intentionally conservative:
 * <ul>
 *   <li>Fields/methods: drop only {@code synthetic &amp;&amp; name.startsWith("$$crochet")}.
 *       Compiler-generated bridges and other synthetic members that the JVM
 *       itself relies on are preserved.</li>
 *   <li>Interfaces: drop only {@link CRIJInstrumented}. {@code @CrochetInstrumented}
 *       is an annotation, not an interface, so it isn't exposed via
 *       {@link Class#getInterfaces()}.</li>
 *   <li>Constructors: no filter — we don't inject any.</li>
 * </ul>
 *
 * <p>In addition to the array filters, this class provides reflective
 * {@code Field.get*}/{@code Field.set*} replacements that trigger the same
 * {@code $$crochetAccess()} hook that bytecode-level GETFIELD/PUTFIELD
 * goes through (via {@link net.jonbell.crochet.transform.FieldAccessWrapper}).
 * Without these, reflective reads of an instrumented object's fields bypass
 * the snapshot machinery and may observe uncheckpointed values after a
 * rollback.
 *
 * <p>Performance: all call sites here are warm-ish (framework-scan code
 * paths, not hot-loop fields). Simple {@link ArrayList}-based filtering is
 * sufficient.
 */
public final class ReflectionFilter {

    private ReflectionFilter() {}

    private static final String CROCHET_PREFIX = "$$crochet";

    /**
     * Drop synthetic fields whose name starts with {@code $$crochet} — these
     * are the two injected fields ({@code $$crochetVersion},
     * {@code $$crochetSnap}). All other fields (including compiler-generated
     * synthetic bridges, outer-class references, etc.) are preserved.
     */
    public static Field[] filterFields(Field[] in) {
        if (in == null || in.length == 0) {
            return in;
        }
        ArrayList<Field> keep = null;
        for (int i = 0; i < in.length; i++) {
            Field f = in[i];
            if (f != null && f.isSynthetic() && isCrochetName(f.getName())) {
                if (keep == null) {
                    keep = new ArrayList<>(in.length - 1);
                    for (int j = 0; j < i; j++) {
                        keep.add(in[j]);
                    }
                }
            } else if (keep != null) {
                keep.add(f);
            }
        }
        if (keep == null) {
            return in;
        }
        return keep.toArray(new Field[0]);
    }

    /**
     * Drop synthetic methods whose name starts with {@code $$crochet} — these
     * are the twelve methods emitted by
     * {@link net.jonbell.crochet.transform.FieldAdder} plus
     * {@code $$crochetLookup} from
     * {@link net.jonbell.crochet.transform.LookupInjector}.
     */
    public static Method[] filterMethods(Method[] in) {
        if (in == null || in.length == 0) {
            return in;
        }
        ArrayList<Method> keep = null;
        for (int i = 0; i < in.length; i++) {
            Method m = in[i];
            if (m != null && m.isSynthetic() && isCrochetName(m.getName())) {
                if (keep == null) {
                    keep = new ArrayList<>(in.length - 1);
                    for (int j = 0; j < i; j++) {
                        keep.add(in[j]);
                    }
                }
            } else if (keep != null) {
                keep.add(m);
            }
        }
        if (keep == null) {
            return in;
        }
        return keep.toArray(new Method[0]);
    }

    /**
     * Drop {@link CRIJInstrumented} from a {@code getInterfaces()} result.
     * User code that enumerates an object's interfaces (Jackson mixin
     * resolution, Weld bean-type discovery, AOP proxy generation) must not
     * observe our marker.
     */
    public static Class<?>[] filterInterfaces(Class<?>[] in) {
        if (in == null || in.length == 0) {
            return in;
        }
        ArrayList<Class<?>> keep = null;
        for (int i = 0; i < in.length; i++) {
            Class<?> c = in[i];
            if (c == CRIJInstrumented.class) {
                if (keep == null) {
                    keep = new ArrayList<>(in.length - 1);
                    for (int j = 0; j < i; j++) {
                        keep.add(in[j]);
                    }
                }
            } else if (keep != null) {
                keep.add(c);
            }
        }
        if (keep == null) {
            return in;
        }
        return keep.toArray(new Class<?>[0]);
    }

    /**
     * No-op filter on {@code getDeclaredConstructors()} / {@code getConstructors()}
     * results — we don't inject constructors. Present so the rewriter's
     * generated code can stay uniform across the reflection surface.
     */
    public static Constructor<?>[] filterConstructors(Constructor<?>[] in) {
        return in;
    }

    /**
     * Unwrap a {@link Class} reference that might be a hidden Fast-proxy
     * class (created by {@link net.jonbell.crochet.transform.Specializer})
     * back to the user's declared class. Fast-proxy class names contain
     * {@code $$crochet}; they exist as per-user-class hidden subclasses that
     * the agent swaps onto {@link CheckpointRollbackAgent#checkpoint}.
     *
     * <p>Returns the superclass in that case, otherwise {@code c} unchanged.
     */
    public static Class<?> unwrapClass(Class<?> c) {
        if (c == null) {
            return null;
        }
        // Fast-proxy classes are defined via MethodHandles.Lookup#defineHiddenClass;
        // the JVM appends "/0x..." before the internal name. The class name
        // itself retains the $$crochet marker the specializer stamped.
        String name = c.getName();
        if (name.indexOf("$$crochet") >= 0) {
            Class<?> sup = c.getSuperclass();
            if (sup != null) {
                return sup;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------
    // Reflective Field.get* / Field.set* replacements that route through
    // $$crochetAccess() so snapshots trigger the same way bytecode-level
    // GETFIELD / PUTFIELD does.
    // ------------------------------------------------------------------

    /** Object-typed read. */
    public static Object getField(Field f, Object target) throws IllegalAccessException {
        noteAccess(f, target);
        return f.get(target);
    }

    /** Object-typed write. */
    public static void setField(Field f, Object target, Object value) throws IllegalAccessException {
        noteAccess(f, target);
        f.set(target, value);
    }

    public static boolean getBoolean(Field f, Object target) throws IllegalAccessException {
        noteAccess(f, target);
        return f.getBoolean(target);
    }

    public static void setBoolean(Field f, Object target, boolean value) throws IllegalAccessException {
        noteAccess(f, target);
        f.setBoolean(target, value);
    }

    public static byte getByte(Field f, Object target) throws IllegalAccessException {
        noteAccess(f, target);
        return f.getByte(target);
    }

    public static void setByte(Field f, Object target, byte value) throws IllegalAccessException {
        noteAccess(f, target);
        f.setByte(target, value);
    }

    public static char getChar(Field f, Object target) throws IllegalAccessException {
        noteAccess(f, target);
        return f.getChar(target);
    }

    public static void setChar(Field f, Object target, char value) throws IllegalAccessException {
        noteAccess(f, target);
        f.setChar(target, value);
    }

    public static short getShort(Field f, Object target) throws IllegalAccessException {
        noteAccess(f, target);
        return f.getShort(target);
    }

    public static void setShort(Field f, Object target, short value) throws IllegalAccessException {
        noteAccess(f, target);
        f.setShort(target, value);
    }

    public static int getInt(Field f, Object target) throws IllegalAccessException {
        noteAccess(f, target);
        return f.getInt(target);
    }

    public static void setInt(Field f, Object target, int value) throws IllegalAccessException {
        noteAccess(f, target);
        f.setInt(target, value);
    }

    public static long getLong(Field f, Object target) throws IllegalAccessException {
        noteAccess(f, target);
        return f.getLong(target);
    }

    public static void setLong(Field f, Object target, long value) throws IllegalAccessException {
        noteAccess(f, target);
        f.setLong(target, value);
    }

    public static float getFloat(Field f, Object target) throws IllegalAccessException {
        noteAccess(f, target);
        return f.getFloat(target);
    }

    public static void setFloat(Field f, Object target, float value) throws IllegalAccessException {
        noteAccess(f, target);
        f.setFloat(target, value);
    }

    public static double getDouble(Field f, Object target) throws IllegalAccessException {
        noteAccess(f, target);
        return f.getDouble(target);
    }

    public static void setDouble(Field f, Object target, double value) throws IllegalAccessException {
        noteAccess(f, target);
        f.setDouble(target, value);
    }

    /**
     * Fire the {@code $$crochetAccess()} hook on the target for instance
     * reflective field access. Static field reflection is <em>not</em>
     * routed here — static access is guarded at bytecode level by
     * {@code noteStaticAccess} emitted via
     * {@link net.jonbell.crochet.transform.StaticFieldRewriter}, which
     * handles its own per-class helper. Reflective static access on an
     * uninstrumented-via-reflection pathway stays a known gap (see the
     * legacy {@code $$crijSFHelper} lookup) and is out of scope for this
     * patch.
     *
     * <p>Defensive: if {@code target} is null or isn't a
     * {@link CRIJInstrumented} instance, skip the hook and let the
     * underlying {@link Field#get}/{@link Field#set} throw whatever NPE
     * or {@link IllegalArgumentException} it would normally throw.
     */
    private static void noteAccess(Field f, Object target) {
        if (target instanceof CRIJInstrumented
                && !Modifier.isStatic(f.getModifiers())) {
            try {
                ((CRIJInstrumented) target).$$crochetAccess();
            } catch (Throwable ignored) {
                // Defensive: never let a snapshot-trigger failure leak out
                // of a reflective read/write. Stock Field.get/set semantics
                // must still apply. The underlying checkpoint/rollback
                // machinery raises RollbackException on its own dedicated
                // code path.
            }
        }
    }

    private static boolean isCrochetName(String name) {
        return name != null && name.startsWith(CROCHET_PREFIX);
    }
}
