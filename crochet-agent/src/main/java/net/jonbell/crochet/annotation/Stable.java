package net.jonbell.crochet.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API element whose surface is frozen for the current major version.
 *
 * <p>Downstream code may safely depend on a {@code @Stable} type or method.
 * Breaking changes require a major-version bump and a documented migration
 * path. Within a major version, the signature, semantics, and observable
 * behaviour are guaranteed not to change.
 *
 * <p>Contrast with {@link Experimental} (may change in a minor release) and
 * {@link Internal} (no stability guarantee of any kind).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Stable {
}
