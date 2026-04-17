// SKETCH — design-only. Not compiled. Shows the deltas to
// net/jonbell/crochet/runtime/CheckpointRollbackAgent.java for Gap 3.
// Copy these additions into the real Agent class when implementing.
// See DESIGN.md §4 and §5.

package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandles.Lookup.ClassOption;
import java.util.Set;

import net.jonbell.crochet.transform.StaticFieldHelperTemplate;
import org.objectweb.asm.Type;

public final class CheckpointRollbackAgentAdditions {

    // ---------- public API for statics ----------

    /**
     * Return the singleton {@link CRIJInstrumented} helper for {@code userClass},
     * generating it on first call. Called from rewritten GETSTATIC/PUTSTATIC
     * sites via {@link net.jonbell.crochet.transform.StaticFieldRewriter}.
     *
     * <p>The helper is a hidden class that extends {@code java.lang.Object},
     * implements {@link CRIJInstrumented}, and has one instance field per
     * static field of {@code userClass}. Generation is lazy: defer until the
     * first rewrite site for this class actually fires, so classes whose
     * statics are never touched pay no allocation cost.
     */
    public static CRIJInstrumented sfHelper(Class<?> userClass) {
        ClassMeta meta = ClassMeta.of(userClass);
        CRIJInstrumented h = meta.sfHelperInstance;
        if (h != null) return h;
        synchronized (meta) {
            h = meta.sfHelperInstance;
            if (h != null) return h;

            // Ensure user-class <clinit> has completed so our mirror sees
            // real values, not field defaults.
            try {
                Class.forName(userClass.getName(), true, userClass.getClassLoader());
            } catch (ClassNotFoundException cnf) {
                // already loaded or loader is gone; either way, proceed
            }

            byte[] bytes = StaticFieldHelperTemplate.emit(
                    Type.getInternalName(userClass),
                    ClassMeta.staticFieldsOf(userClass));
            Lookup lookup = meta.resolveLookup();
            try {
                Class<?> helperClass = lookup.defineHiddenClass(
                        bytes, true, ClassOption.NESTMATE, ClassOption.STRONG)
                        .lookupClass();
                Object inst = U.allocateInstance(helperClass);
                // $$crochetInitialMirror: GETSTATIC C.f → PUTFIELD helper.f for each.
                helperClass.getDeclaredMethod("$$crochetInitialMirror").invoke(inst);
                CRIJInstrumented crij = (CRIJInstrumented) inst;
                meta.sfHelperClass = helperClass;
                meta.sfHelperInstance = crij;
                return crij;
            } catch (Throwable t) {
                throw new IllegalStateException(
                        "Failed to generate SF helper for " + userClass, t);
            }
        }
    }

    /** Checkpoint the statics of one class. Returns the version id. */
    public static int checkpointStatics(Class<?> userClass) {
        // mirrors checkpoint(Object): bump version, swap helper to Fast proxy.
        int v = /* nextCheckpointVersion(); */ 0;
        CRIJInstrumented h = sfHelper(userClass);
        h.$$crochetCheckpoint(v);
        return v;
    }

    /** Roll back the statics of one class to {@code v}. */
    public static void rollbackStatics(Class<?> userClass, int v) {
        int rv = /* nextRollbackVersion(); */ 0;
        CRIJInstrumented h = sfHelper(userClass);
        h.$$crochetRollback(rv);
    }

    /**
     * Checkpoint the statics of every registered user class and every
     * registered root object. Maps to the paper's {@code checkpointAllRoots}.
     *
     * <p>"Registered" means a class whose {@link ClassMeta} has been created
     * (any code path through {@code ClassMeta.of}). We maintain a side-table
     * populated in {@code ClassMeta.of} because {@link ClassValue} is not
     * iterable.
     */
    public static int checkpointAll() {
        int v = /* nextCheckpointVersion(); */ 0;
        for (Class<?> c : ClassMeta.registeredClasses()) {
            if (ClassMeta.hasStatics(c)) {
                sfHelper(c).$$crochetCheckpoint(v);
            }
        }
        // roots registered via a (future) agent.registerRoot(obj) call get the
        // same treatment — V1's checkpoint(obj) would be applied here.
        return v;
    }

    public static void rollbackAll(int v) {
        int rv = /* nextRollbackVersion(); */ 0;
        for (Class<?> c : ClassMeta.registeredClasses()) {
            if (ClassMeta.hasStatics(c)) {
                sfHelper(c).$$crochetRollback(rv);
            }
        }
    }

    // U, KLASS_OFFSET, nextCheckpointVersion, nextRollbackVersion and the
    // Fast-proxy machinery are unchanged from the current Agent — elided here.
    static sun.misc.Unsafe U;

    // ---------- ClassMeta extensions (live on ClassMeta itself, shown here for shape) ----------

    interface ClassMetaShape {
        // existing
        Object getUserClass();
        volatile Object fastProxyClass();
        // NEW for Gap 3
        Class<?> getSfHelperClass();
        void setSfHelperClass(Class<?> c);
        CRIJInstrumented getSfHelperInstance();
        void setSfHelperInstance(CRIJInstrumented h);
    }

    /** The side-table added to ClassMeta. */
    static final Set<Class<?>> REGISTERED = java.util.concurrent.ConcurrentHashMap.newKeySet();
}
</content>
</invoke>