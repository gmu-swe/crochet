package net.jonbell.crochet.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in marker that selects the <em>eager</em> checkpoint strategy for
 * instances of the annotated class.
 *
 * <p>By default every instrumented user class uses the <em>Fast-proxy</em>
 * strategy: {@code $$crochetCheckpoint(v)} records the version, installs a
 * sentinel, and swaps the object's klass pointer to a hidden Fast-proxy
 * subclass. The actual field snapshot is deferred until the next read or
 * write on the instance ({@code $$crochetAccess} on the proxy routes into
 * {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#fastAccess}
 * which lazily copies fields into a shadow). This amortizes heap-traversal
 * cost over the first post-checkpoint touch and is a strict win when the
 * workload mutates only a fraction of the live object graph.
 *
 * <p>For small objects with heavy state-sharing, proxy dispatch overhead
 * (klass CAS, {@code VarHandle} chain, stripe-lock) can eclipse the cost of
 * an immediate shallow copy. The <em>eager</em> strategy takes a shadow at
 * checkpoint time directly ({@link
 * net.jonbell.crochet.runtime.CheckpointRollbackAgent#allocateShadow}) and
 * never installs the Fast proxy. {@code obj.getClass()} therefore remains
 * the original user class across checkpoints — useful when downstream code
 * asserts identity on the klass or {@code getClass()} chain.
 *
 * <p>Gap-8 exception safety is preserved: the emitted body still uses the
 * sentinel-CAS version guard. A throw from {@code allocateShadow} or
 * {@code $$crochetCopyFieldsTo} zeroes the version via CAS and raises
 * {@link net.jonbell.crochet.runtime.RollbackException} with
 * {@link net.jonbell.crochet.runtime.RollbackException#POISON_VERSION}.
 *
 * <p>Sources that cannot annotate (third-party types, JDK classes) can opt
 * in via the system property {@code crochet.eagerClasses}:
 * {@code -Dcrochet.eagerClasses=com.foo.Bean,com.bar.Box}. The list is
 * comma-separated, whitespace-tolerant, uses fully qualified class names.
 *
 * <p>Unproxyable classes (final or otherwise rejected by
 * {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#isUnproxyable})
 * that also carry {@code @CrochetEager} are this annotation's cleanest use
 * case: a final bean with mutable state gets correct checkpoint/rollback
 * semantics via shallow copy, whereas the Fast-proxy path would silently
 * no-op (a final class cannot be subclassed so the proxy cannot be
 * generated).
 */
@Stable
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CrochetEager {
}
