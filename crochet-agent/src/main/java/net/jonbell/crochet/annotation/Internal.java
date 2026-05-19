package net.jonbell.crochet.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * INTERNAL USE ONLY — may change without notice in any release.
 *
 * <p>An {@code @Internal} type or method is part of Crochet's implementation
 * rather than its public API. It is visible (not package-private) only because
 * it must be reachable from emitted bytecode, other Crochet modules, or test
 * infrastructure. No guarantees are made about its signature, semantics, or
 * existence across any release boundary.
 *
 * <p>Downstream code must not depend on {@code @Internal} elements. Any
 * dependency on an internal element is at the user's own risk and will not be
 * treated as a breaking change when the element is modified or removed.
 *
 * <p>Contrast with {@link Stable} (frozen for the major version) and
 * {@link Experimental} (may change in a minor release, but intentionally
 * public).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Internal {
}
