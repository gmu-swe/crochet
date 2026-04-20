package net.jonbell.crochet.junit5;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static field as a Crochet checkpoint root. After the test class's
 * {@code @BeforeAll} runs, {@link CrochetSetupExtension} reads each
 * {@code @CrochetTrack} field and snapshots the referenced object. Between
 * tests the extension restores each tracked object to that snapshot.
 *
 * <p>Field must be static. Field value at the time of checkpoint capture must
 * be non-null. The referenced object's class is instrumented by Crochet at
 * agent-load time, so any class loaded under {@code -javaagent:crochet-agent}
 * is eligible.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface CrochetTrack {}
