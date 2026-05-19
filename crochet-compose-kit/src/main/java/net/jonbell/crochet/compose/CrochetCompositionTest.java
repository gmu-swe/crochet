package net.jonbell.crochet.compose;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import net.jonbell.crochet.annotation.Experimental;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Meta-annotation that marks a JUnit 5 test class as a Crochet composition test.
 *
 * <p>A composition test verifies that the tested feature works correctly under
 * different agent stack configurations (Crochet alone, Crochet + Byte Buddy,
 * Crochet + Fray). The active configuration is selected via the system property
 * {@code crochet.compose.config}; see {@link AgentConfig} for the enumerated values.
 *
 * <h2>Usage</h2>
 * <pre>
 *   &#064;CrochetCompositionTest
 *   class MyFeatureCompositionTest {
 *
 *       &#064;Test
 *       void checkpointAndRollbackWorksUnderComposition() {
 *           MyBean bean = new MyBean("hello");
 *           int v = CheckpointRollbackAgent.checkpoint(bean);
 *           bean.setValue("world");
 *           CheckpointRollbackAgent.rollback(bean, v);
 *           assertEquals("hello", bean.getValue());
 *       }
 *   }
 * </pre>
 *
 * <p>The {@link CrochetCompositionExtension} registered by this annotation
 * checks at test-class initialization time that the expected agent configuration
 * is actually loaded. If the Crochet agent is not present (detected via
 * {@code CRIJInstrumented} presence on a known-instrumented class), the test
 * is aborted with a clear message rather than silently passing or failing with
 * an unrelated error.
 *
 * <h2>CI integration</h2>
 * <p>To exercise all configurations, configure Maven Surefire to fork three
 * JVM executions, one per {@link AgentConfig} constant:
 * <pre>{@code
 *   <plugin>
 *     <artifactId>maven-surefire-plugin</artifactId>
 *     <executions>
 *       <execution>
 *         <id>crochet-only</id>
 *         <configuration>
 *           <argLine>-javaagent:crochet-agent.jar -Dcrochet.compose.config=crochet-only</argLine>
 *         </configuration>
 *       </execution>
 *       <execution>
 *         <id>crochet-fray</id>
 *         <configuration>
 *           <argLine>-javaagent:fray-agent.jar -javaagent:crochet-agent.jar
 *                    -Dcrochet.compose.config=crochet+fray</argLine>
 *         </configuration>
 *       </execution>
 *     </executions>
 *   </plugin>
 * }</pre>
 *
 * <p>For Phase A, {@code crochet-compose-kit}'s POM provides the
 * {@code crochet-only} configuration only. The Byte Buddy and Fray forks are
 * documented above as templates; they are exercised manually or in projects
 * that pull in those agents alongside this module.
 *
 * @see CrochetCompositionExtension
 * @see AgentConfig
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(CrochetCompositionExtension.class)
@Experimental
public @interface CrochetCompositionTest {
}
