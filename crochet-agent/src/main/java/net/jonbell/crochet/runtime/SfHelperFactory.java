package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Type;

/**
 * Gap 3 (bytecode): static-field helper lookup and generation. Owns the
 * {@link ClassValue} cache of per-user-class static-field helpers, the
 * lazy-materialisation policy, and the helper-class bytecode emission.
 *
 * <p>The legacy path used {@code synchronized(meta) + DCL} on a
 * {@link ClassMeta#sfHelper} field, which serialized ALL first-access
 * callers per class. {@link ClassValue#get} does one-shot lock-free
 * materialisation via a CAS-based internal table, so subsequent lookups
 * are a fast hash-table read without any monitor acquisition.
 *
 * <p>Classes our transformer skips (enums, annotations, classes without
 * {@code $$crochetLookup}) fall through to {@link NoopSFHelper#INSTANCE}
 * so the user's GETSTATIC/PUTSTATIC still hits the real static field —
 * checkpoint/rollback silently skip these statics, matching the final-
 * class proxy policy.
 *
 * <p>The ClassValue instance is stored on {@link ClassMeta} (a cached
 * per-class object) so the hot-path lookup is a direct field load of
 * {@code ClassMeta.sfHelper} and hits the DCL-style fast path without
 * traversing the ClassValue cache on every call. The ClassValue only
 * participates in first-access materialisation; after that the helper
 * is pinned on the ClassMeta.
 */
final class SfHelperFactory {

    private SfHelperFactory() {}

    static CRIJInstrumented sfHelperFor(Class<?> userClass) {
        if (RuntimeTracer.ENABLED) {
            RuntimeTracer.bumpSfHelper(userClass);
        }
        ClassMeta meta = ClassMeta.of(userClass);
        CRIJInstrumented h = meta.sfHelper;
        if (h != null) {
            return h;
        }
        // Cold path: delegate to ClassValue for lock-free one-shot
        // materialisation. Concurrent callers observe the same helper
        // without any synchronized() block. Once assigned, meta.sfHelper
        // is stable for the lifetime of the ClassMeta and the fast path
        // above covers all subsequent calls.
        return SF_HELPERS.get(userClass);
    }

    /**
     * Fused {@code sfHelperFor(owner).$$crochetAccess()} pre-hook emitted by
     * {@link net.jonbell.crochet.transform.StaticFieldRewriter}. The legacy
     * two-call pattern was a pure no-op for classes the agent chose not to
     * instrument (enums, interfaces, annotations, and classes whose
     * $$crochetLookup throws) — but the {@code $$crochetAccess} leg was an
     * {@code INVOKEINTERFACE} against an open polymorphic world (every
     * instrumented user class can contribute its own SF helper class to the
     * inline cache), which the JIT couldn't devirtualize. On WildFly
     * startup with {@code org.jboss.logging.Logger$Level} hit &gt;8M times,
     * the wasted itable lookup dominated.
     *
     * <p>Fusing into a single {@code INVOKESTATIC} lets the JIT inline the
     * entire fast path: a ClassValue read (into {@link ClassMeta}) + volatile
     * field load. If {@code meta.sfHelper} is already materialised we
     * return immediately — the original {@code $$crochetAccess} was a no-op
     * anyway, so nothing on the helper is actually exercised here.
     */
    static void noteStaticAccess(Class<?> userClass) {
        if (RuntimeTracer.ENABLED) {
            RuntimeTracer.bumpSfHelper(userClass);
        }
        // No-checkpoint fast path. Before any thread has ever called
        // checkpoint* / rollback*, VERSION_COUNTER stays at its initial 0 and
        // there is nothing to snapshot. Gate the whole pre-hook on this read —
        // once inlined, the fast path collapses to one plain load + branch.
        // Telemetry on tradebeans startup showed 3.2M wasted calls on a
        // single class (org.h2.value.ValueNull) before any checkpoint ever
        // fires; this gate elides all of them.
        //
        // Opaque read (JEP 193 access mode): the gate is a best-effort
        // early-exit, not a synchronizer. A stale 0 reading just means the
        // caller takes the same slow path it would have taken before; a stale
        // non-zero read is harmless because helper materialisation is
        // idempotent (ClassValue CAS-serialises the computeValue). We don't
        // need volatile's bidirectional happens-before here — the subsequent
        // sfHelperFor() call establishes its own happens-before through
        // ClassValue's internal synchronisation. Using opaque instead of
        // volatile removes the compiler's reorder barriers on this read,
        // which on ARM/POWER matters for the inlined JIT output; on x86 the
        // encoding is identical but the change documents the looser intent.
        //
        // Once VERSION_COUNTER becomes non-zero (any checkpoint or rollback),
        // we fall through to the DCL-style materialisation check below, which
        // matches the pre-gate semantics.
        if (VersionCounter.getOpaque() == 0L) {
            return;
        }
        ClassMeta meta = ClassMeta.of(userClass);
        if (meta.sfHelper != null) {
            return;
        }
        // Cold path: force helper materialisation so subsequent calls take
        // the inlined fast path above. sfHelperFor is the single entry point
        // that populates meta.sfHelper via the ClassValue computeValue path.
        sfHelperFor(userClass);
    }

    private static final ClassValue<CRIJInstrumented> SF_HELPERS = new ClassValue<>() {
        @Override
        protected CRIJInstrumented computeValue(Class<?> userClass) {
            // ClassValue serializes computeValue per key internally using a
            // CAS-based one-shot, so concurrent callers observe the same
            // helper without us doing any additional locking.
            CRIJInstrumented helper;
            if (!hasLookup(userClass)) {
                helper = NoopSFHelper.INSTANCE;
            } else {
                ClassMeta meta = ClassMeta.of(userClass);
                helper = generateSFHelper(userClass, meta);
            }
            // Back-compat + fast-path pin: populate the ClassMeta.sfHelper
            // volatile so subsequent sfHelperFor calls hit a single-load
            // DCL-style fast path (see sfHelperFor). Also readable by
            // rollbackClassAtVersion directly.
            ClassMeta.of(userClass).sfHelper = helper;
            return helper;
        }
    };

    private static boolean hasLookup(Class<?> userClass) {
        try {
            userClass.getDeclaredMethod("$$crochetLookup");
            return true;
        } catch (Throwable t) {
            // NoClassDefFoundError can fire here if the class was instrumented
            // but its classloader can't resolve CRIJInstrumented (happens in
            // plugin-style classloader hierarchies DaCapo uses). Treat any
            // failure as "not instrumented" so we fall back to the no-op
            // SF helper rather than breaking the user's program.
            return false;
        }
    }

    /** Placeholder helper for classes the agent chose not to instrument. */
    private static final class NoopSFHelper implements CRIJInstrumented {
        static final NoopSFHelper INSTANCE = new NoopSFHelper();
        @Override public void $$crochetCopyFieldsTo(Object to) {}
        @Override public void $$crochetCopyFieldsFrom(Object old) {}
        @Override public void $$crochetCheckpoint(int version) {}
        @Override public void $$crochetRollback(int version) {}
        @Override public void $$crochetPropagateCheckpoint(int version) {}
        @Override public void $$crochetPropagateRollback(int version) {}
        @Override public int $$crochetGetVersion() { return 0; }
        @Override public void $$crochetSetVersion(int version) {}
        @Override public Object $$crochetGetSnap() { return null; }
        @Override public void $$crochetSetSnap(Object snap) {}
        @Override public void $$crochetAccess() {}
        @Override public boolean $$crochetIsRollbackState() { return false; }
    }

    private static CRIJInstrumented generateSFHelper(Class<?> userClass, ClassMeta meta) {
        try {
            String userInternal = userClass.getName().replace('.', '/');
            String helperInternal = userInternal + "$$crochetSFHelper";
            List<net.jonbell.crochet.transform.StaticFieldHelperTemplate.FieldRecord> statics =
                    staticFieldsOf(userClass);
            byte[] bytes = net.jonbell.crochet.transform.StaticFieldHelperTemplate.emit(
                    userInternal, helperInternal, statics);
            MethodHandles.Lookup lookup = meta.resolveLookup();
            // Drop ClassOption.STRONG: the helper is held alive by
            // ClassMeta#sfHelper, which is itself pinned by the ClassMeta
            // entry in the per-class ClassValue cache, which is pinned by
            // the user class's classloader for as long as the class is
            // loaded. That chain keeps the helper reachable; STRONG would
            // additionally tie the helper's lifetime to its defining
            // lookup class-loader, which blocks unloading in testing /
            // redefinition scenarios without buying any safety we don't
            // already have.
            Class<?> helperClass = lookup.defineHiddenClass(bytes, true,
                            MethodHandles.Lookup.ClassOption.NESTMATE)
                    .lookupClass();
            meta.sfHelperClass = helperClass;
            Object instance = FastProxySupport.allocateShadow(helperClass);
            return (CRIJInstrumented) instance;
        } catch (Throwable t) {
            if (Boolean.getBoolean("crochet.verboseCompat")) {
                System.err.println("Crochet SF helper gen FAILED for " + userClass.getName()
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
            }
            throw new IllegalStateException("Failed to generate SF helper for " + userClass, t);
        }
    }

    private static List<net.jonbell.crochet.transform.StaticFieldHelperTemplate.FieldRecord>
    staticFieldsOf(Class<?> c) {
        List<net.jonbell.crochet.transform.StaticFieldHelperTemplate.FieldRecord> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (Modifier.isFinal(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            if (f.getName().startsWith("$$crochet")) continue;
            String desc = Type.getDescriptor(f.getType());
            out.add(new net.jonbell.crochet.transform.StaticFieldHelperTemplate.FieldRecord(
                    f.getName(), desc));
        }
        return out;
    }
}
