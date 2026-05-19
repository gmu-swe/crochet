package net.jonbell.crochet.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the root object parameter for a {@link CrochetCheckpoint}-annotated
 * method.
 *
 * <p>The annotated parameter is passed as the {@code root} argument to
 * {@link net.jonbell.crochet.runtime.Crochet#checkpoint(Object)} and
 * {@link net.jonbell.crochet.runtime.Crochet#rollback(Object, int)} at runtime.
 *
 * <p>Exactly one parameter per {@link CrochetCheckpoint} method may carry this
 * annotation.  The parameter type must be a reference type (not a primitive).
 *
 * @see CrochetCheckpoint
 */
@Stable
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CrochetRoot {
}
