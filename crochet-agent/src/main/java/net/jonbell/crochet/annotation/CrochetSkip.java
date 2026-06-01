package net.jonbell.crochet.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-out marker that suppresses Crochet instrumentation for the annotated
 * class and all of its subclasses.
 *
 * <h2>Scope</h2>
 * <p>{@code @CrochetSkip} is a <em>user-class opt-out only</em>. It is
 * intended for application code that has a principled reason to exclude a
 * specific class from checkpoint/rollback tracking — for example, a
 * thread-local accumulator that is deliberately reset between checkpoints, or
 * a value-typed record whose rollback semantics are handled at a higher level.
 *
 * <p><strong>This annotation is NOT a replacement for the hardcoded
 * {@code CrochetTransformer.shouldSkip} list.</strong> That list documents
 * JDK-internal, Hibernate, Fray, and other framework incompatibilities that
 * require suppression regardless of whether user code annotates the class.
 * The hardcoded list remains the authoritative source for framework-level
 * skip decisions; {@code @CrochetSkip} is layered on top as a convenience for
 * application authors.
 *
 * <h2>Inheritance</h2>
 * <p>Skipping is inherited: if a superclass carries {@code @CrochetSkip}, all
 * subclasses are also skipped at transform time. The check walks the
 * superclass chain from the class being transformed up to (but not including)
 * {@code java.lang.Object}, reading annotation tables directly from class
 * files via ASM — no class loading takes place. This means the decision is
 * made purely at bytecode level, consistent with how the rest of the
 * transformer operates.
 *
 * <p>Java's {@link java.lang.annotation.Inherited} meta-annotation is
 * <em>not</em> used because it operates on the reflective layer and requires
 * the annotated class to be loaded. The transformer runs before classes are
 * loaded, so we implement inheritance explicitly.
 *
 * <h2>Interaction with the hardcoded skip-list</h2>
 * <p>A class that appears on the hardcoded list is always skipped,
 * independently of whether {@code @CrochetSkip} is also present. The two
 * mechanisms are ORed together: skip if either says to skip. There are no
 * interaction surprises — the hardcoded check fires first and short-circuits.
 *
 * <h2>Performance</h2>
 * <p>The annotation check is performed once per class at transform time (not
 * on every method call). Superclass resolution reads each ancestor class file
 * at most once via the classloader's resource stream; results are not cached
 * because skip decisions are idempotent and the class-file read is already
 * paid at instrumentation time.
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Annotating a class that has already been instrumented (e.g., via the
 *       jlink pre-instrumented JDK) has no effect — instrumentation is baked
 *       in. Use the hardcoded list for jlink-time exclusions.
 *   <li>Interfaces cannot carry this annotation (interfaces are already skipped
 *       by the transformer). Annotating an interface is a no-op.
 *   <li>JDK classes ({@code java.*}, {@code jdk.*}, {@code sun.*},
 *       {@code com.sun.*}) that the application cannot annotate should instead
 *       be added to the hardcoded list with a comment explaining the failure.
 * </ul>
 */
@Stable
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CrochetSkip {
}
