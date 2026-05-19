package net.jonbell.crochet.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API element that is subject to change in a minor release.
 *
 * <p>An {@code @Experimental} type or method is publicly visible but is not
 * yet committed to API stability. Downstream code may use it, but should be
 * prepared to adapt to breaking changes without a major-version bump.
 * Typically, an element is promoted to {@link Stable} after one release cycle
 * of real-world use.
 *
 * <p>Contrast with {@link Stable} (frozen for the major version) and
 * {@link Internal} (no stability guarantee of any kind).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Experimental {
}
