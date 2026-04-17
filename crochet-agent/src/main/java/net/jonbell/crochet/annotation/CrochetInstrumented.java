package net.jonbell.crochet.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker applied by {@code CrochetTransformer} to every class it rewrites.
 *
 * <p>Retention is {@code CLASS}: visible on class files at transform time
 * (pre-scan skip on re-entry) but not reified into reflection metadata.
 * The transformer detects presence via ASM's {@code ClassReader}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface CrochetInstrumented {
}
