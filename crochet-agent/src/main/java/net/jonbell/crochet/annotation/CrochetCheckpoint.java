package net.jonbell.crochet.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic checkpoint/rollback wrapping by the Crochet
 * transformer.
 *
 * <p>When the Crochet agent (or jlink-packed runtime) loads a class whose
 * method carries this annotation, the transformer wraps the entire method body
 * with a {@code checkpoint} / {@code rollback} pair:
 *
 * <pre>
 *   int v = Crochet.checkpoint(root);
 *   try {
 *       // original body
 *   } catch (Throwable t) {
 *       Crochet.rollback(root, v);
 *       throw t;
 *   }
 *   Crochet.rollback(root, v);   // on normal exit
 * </pre>
 *
 * <p>Exactly one parameter of the annotated method must be annotated with
 * {@link CrochetRoot}; that parameter is used as the {@code root} object for
 * the checkpoint/rollback calls.
 *
 * <p><b>Works on prebuilt JARs</b> — because the transformer operates on
 * bytecode at class-load time, downstream libraries compiled without the Crochet
 * APT can still benefit from the wrap as long as the annotation is present in
 * their bytecode.
 *
 * <p>Constraints (validated at compile time by {@code CrochetCheckpointProcessor}
 * when the APT is on the annotation processor path, and at transform time
 * otherwise):
 * <ul>
 *   <li>The method must not be {@code static}.
 *   <li>The method must not be {@code abstract} or {@code native}.
 *   <li>Exactly one parameter must carry {@link CrochetRoot}.
 * </ul>
 *
 * @see CrochetRoot
 */
@Stable
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CrochetCheckpoint {
}
