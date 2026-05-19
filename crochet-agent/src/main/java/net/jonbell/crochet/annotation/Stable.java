package net.jonbell.crochet.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type or method as a stable, user-facing API.
 *
 * <p>Types and methods annotated with {@code @Stable} are part of the
 * public Crochet contract. Signature and behavioral compatibility across
 * minor versions is guaranteed. Changes that break compatibility require
 * a major-version bump and a documented migration path.
 *
 * <p>This annotation is informational only — no compile-time or runtime
 * enforcement is applied in Phase A. The enforcement gate (universal gate 14)
 * will be wired in unit A.4 (composition kit). Until then, treat
 * {@code @Stable} as a promise to consumers that they can depend on the
 * annotated surface.
 *
 * <p>Contrast with {@code @Internal}: internal types/methods may change
 * without notice and should not be relied upon by downstream code.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Stable {
}
