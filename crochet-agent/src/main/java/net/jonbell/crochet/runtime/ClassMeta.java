package net.jonbell.crochet.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Per-user-class metadata that the legacy CROCHET attached by monkey-patching
 * {@code java.lang.Class} (see legacy/src/main/java/java/lang/Class.java). We
 * can't inject fields into {@code java.lang.Class} on modern JVMs, so this
 * state lives in a {@link ClassValue} keyed by the user class.
 */
public final class ClassMeta {

    private static final ClassValue<ClassMeta> CACHE = new ClassValue<>() {
        @Override
        protected ClassMeta computeValue(Class<?> userClass) {
            return new ClassMeta(userClass);
        }
    };

    public static ClassMeta of(Class<?> userClass) {
        return CACHE.get(userClass);
    }

    public final Class<?> userClass;

    /** A preallocated instance of {@link #userClass} whose klass pointer we read to set up {@link #userKlass}. */
    public volatile Object preallocInst;

    /** The klass-pointer int stored 8 bytes into {@link #preallocInst}'s header (compressed class pointer). */
    public volatile int userKlass;

    /** The specialized hidden-class proxy that implements {@link CRIJFast} and extends {@link #userClass}. */
    public volatile Class<?> fastProxyClass;

    /** Preallocated instance of {@link #fastProxyClass}; its header carries the klass pointer we swap into. */
    public volatile Object fastProxyPreallocInst;

    /** Klass pointer extracted from {@link #fastProxyPreallocInst}. */
    public volatile int fastProxyKlass;

    /** Offset of the injected {@code $$crochetVersion} int field. */
    public volatile long versionOffset;

    /** Offset of the injected {@code $$crochetSnap} Object field. */
    public volatile long snapOffset;

    /** Lookup obtained from the user class's injected {@code $$crochetLookup()} method. */
    public volatile MethodHandles.Lookup lookup;

    private ClassMeta(Class<?> userClass) {
        this.userClass = userClass;
    }

    public MethodHandles.Lookup resolveLookup() {
        MethodHandles.Lookup l = lookup;
        if (l != null) {
            return l;
        }
        try {
            Method m = userClass.getDeclaredMethod("$$crochetLookup");
            Object result = m.invoke(null);
            l = (MethodHandles.Lookup) result;
            lookup = l;
            return l;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "User class " + userClass.getName()
                            + " was not instrumented with $$crochetLookup; was the Java agent attached?",
                    e);
        }
    }
}
