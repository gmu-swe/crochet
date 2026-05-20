package edu.neu.ccs.prl.crochet.ttd;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import net.jonbell.crochet.annotation.Stable;

/**
 * Marks a method whose every source line should become an implicit
 * time-travel pause point. The {@link TtdAgent} javaagent transforms
 * each {@code @TimeTravelBody} method by inserting a call to
 * {@link Ttd#lineHit(String, String, int)} at every line number entry
 * in the method's {@code LineNumberTable}.
 *
 * <p>The method must be invoked from inside a {@link Ttd#session} so
 * the line-hit callbacks have a context to drive. Methods marked with
 * this annotation but called outside a session are no-ops (the
 * {@code lineHit} call sees no thread-local context and returns).
 *
 * <p>Phase 1 limitation: only direct, non-lambda methods. Lambda body
 * lines won't be instrumented because the lambda is a synthetic
 * method without our annotation. To make a lambda body time-travelable,
 * extract it into a named method and annotate that.
 */
@Stable
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TimeTravelBody {}
