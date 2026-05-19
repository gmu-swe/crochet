package net.jonbell.crochet.compose;

import net.jonbell.crochet.annotation.Experimental;

/**
 * Enumeration of agent configurations supported by {@link CrochetCompositionTest}.
 *
 * <p>Each constant identifies one agent stack that has been validated as a
 * known-good composition with Crochet. The composition test infrastructure
 * selects the active configuration via the system property
 * {@code crochet.compose.config} (see {@link CrochetCompositionExtension}).
 *
 * <p>To exercise all configurations, a Maven Surefire setup forks one JVM per
 * constant, passing the matching {@code -Dcrochet.compose.config} value to each.
 * This is the recommended pattern rather than attempting to load/unload agents
 * inside a single JVM (which is not reliably possible once the JVM is running).
 *
 * <h2>Known-good compositions and their requirements</h2>
 * <ul>
 *   <li>{@link #CROCHET_ONLY} — Crochet alone. No additional requirements.</li>
 *   <li>{@link #CROCHET_BYTE_BUDDY} — Crochet + Byte Buddy (via Mockito-inline).
 *       Byte Buddy's runtime class generation produces {@code $ByteBuddy$}
 *       and {@code $HibernateProxy$} synthetic subclasses. These are pre-skipped
 *       by {@code CrochetTransformer.shouldSkip} to prevent "Duplicate method"
 *       {@code ClassFormatError}. Without the skip, Byte Buddy's
 *       {@code MemberAccessor} scans the parent via {@code Class.getDeclaredMethods}
 *       and re-declares our {@code $$crochet*} members on the subclass before our
 *       transformer sees it.</li>
 *   <li>{@link #CROCHET_FRAY} — Crochet + Fray concurrency tester.
 *       Fray's scheduler classes ({@code org.pastalab.fray.*}) must not acquire
 *       Crochet's stripe-lock inside scheduler hot paths; instrumenting them
 *       makes them {@code CRIJInstrumented} and causes {@code checkpointAll}'s
 *       {@code Thread.getAllStackTraces()} loop to attempt {@code fastAccess} on
 *       Fray's internal threads — a {@code ReentrantLock} acquire inside the
 *       scheduler that deadlocks. The {@code org/pastalab/fray/} skip-list entry
 *       prevents this.</li>
 * </ul>
 */
@Experimental
public enum AgentConfig {

    /**
     * Crochet agent only, no additional agents.
     *
     * <p>This is the baseline configuration and is always expected to pass.
     */
    CROCHET_ONLY("crochet-only"),

    /**
     * Crochet + Byte Buddy (Mockito-inline mode).
     *
     * <p>Pre-requisite skip-list entries in {@code CrochetTransformer.shouldSkip}:
     * <ul>
     *   <li>{@code $ByteBuddy$} — prevents "Duplicate method" ClassFormatError
     *       when Byte Buddy's MemberAccessor re-declares inherited $$crochet* methods.</li>
     *   <li>{@code $HibernateProxy$} — prevents the same error on Hibernate's proxy
     *       factory path, which also uses ASM and is similarly affected.</li>
     *   <li>{@code _$$_Weld} — prevents VerifyError on WildFly/Weld client proxies.</li>
     *   <li>{@code $$$view} — prevents ClassFormatError on JBoss EJB view proxies.</li>
     * </ul>
     */
    CROCHET_BYTE_BUDDY("crochet+byte-buddy"),

    /**
     * Crochet + Fray concurrency testing framework.
     *
     * <p>Pre-requisite skip-list entries in {@code CrochetTransformer.shouldSkip}:
     * <ul>
     *   <li>{@code org/pastalab/fray/} — prevents checkpointAll deadlock under
     *       Fray's scheduler. Without this entry, Crochet's stripe-lock would
     *       be acquired from within Fray scheduler hot paths (RunContext,
     *       RuntimeDelegate, ThreadContext), confounding the state Fray is tracking
     *       and deadlocking. See Fray issue #424 investigation notes. This entry
     *       is the Fray skip-list contribution that crochet-compose-kit pre-bakes.</li>
     * </ul>
     */
    CROCHET_FRAY("crochet+fray");

    /** Value of {@code -Dcrochet.compose.config} that selects this configuration. */
    public final String propertyValue;

    AgentConfig(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    /**
     * Returns the {@code AgentConfig} whose {@link #propertyValue} matches the
     * system property {@code crochet.compose.config}, or {@link #CROCHET_ONLY}
     * if the property is absent.
     */
    public static AgentConfig fromSystemProperty() {
        String val = System.getProperty("crochet.compose.config", CROCHET_ONLY.propertyValue);
        for (AgentConfig c : values()) {
            if (c.propertyValue.equals(val)) {
                return c;
            }
        }
        return CROCHET_ONLY;
    }
}
