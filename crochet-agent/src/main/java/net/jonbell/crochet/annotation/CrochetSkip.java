package net.jonbell.crochet.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts the annotated class out of Crochet's bytecode transformation.
 *
 * <p>A class annotated with {@code @CrochetSkip} is treated the same as any
 * class in Crochet's hardcoded skip-list (see
 * {@link net.jonbell.crochet.transform.CrochetTransformer#shouldSkip}): the
 * transformer returns {@code null} without emitting any {@code $$crochet*}
 * synthetic methods or fields, and the class does NOT implement
 * {@link net.jonbell.crochet.runtime.CRIJInstrumented}. Checkpoint and
 * rollback have no effect on instances of skipped classes; their state
 * survives rollbacks unchanged.
 *
 * <p><b>Scope: user-class opt-out only.</b> This annotation is intended for
 * application code that should deliberately be excluded from Crochet's
 * tracking — for example, infrastructure helpers, logging classes, or
 * framework-internal proxies that are known to break under Crochet's field
 * injection. It is NOT a replacement for the hardcoded skip-list
 * ({@code shouldSkip}), which handles JDK and framework incompatibilities
 * that the user cannot annotate.
 *
 * <p><b>Inheritance semantics.</b> Subclasses of a {@code @CrochetSkip}
 * class are also skipped, because a subclass inherits the annotation status
 * via the explicit superclass-walk in
 * {@link net.jonbell.crochet.transform.CrochetTransformer#shouldSkip}.
 * Annotating a subclass of a non-annotated class skips only that specific
 * subclass and its descendants.
 *
 * <p><b>Interaction with {@code @TimeTravelBody}.</b> If a class is skipped
 * by Crochet, field accesses inside its {@code @TimeTravelBody} methods are
 * NOT wrapped; the CPS save/restore of method-local state still works (the
 * TTD transformer runs first), but heap state of instances of the skipped
 * class is not captured by Crochet checkpoints. On rollback, mutations to
 * those instances survive. This is documented in SOUNDNESS.md §5 (Threat 5).
 *
 * <p><b>Note on retention.</b> The annotation is retained at runtime so that
 * the transformer can read it from the class file being loaded via
 * {@link java.lang.instrument.ClassFileTransformer}. The transformer reads
 * the raw class bytes via ASM; runtime-visible annotations appear in the
 * {@code RuntimeVisibleAnnotations} attribute, which ASM parses without
 * loading the annotated class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CrochetSkip {
}
