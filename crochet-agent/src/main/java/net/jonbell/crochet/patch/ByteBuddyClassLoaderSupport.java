package net.jonbell.crochet.patch;

import net.jonbell.crochet.agent.CrochetAgent;

/**
 * Support helper invoked from the patched {@code findClass} / {@code loadClass}
 * bodies of ByteBuddy's private classloaders ({@code ByteArrayClassLoader},
 * {@code MultipleParentClassLoader}). See
 * {@link net.jonbell.crochet.transform.ByteBuddyClassLoaderPatcher} for the
 * bytecode-level entry point.
 *
 * <p><b>Why this helper exists.</b> ByteBuddy synthesises classes (Mockito's
 * subclass mock-maker funnels through it) and defines them in
 * {@code ByteArrayClassLoader}, whose {@code parent} is the loader of the type
 * being mocked. Crochet's transformer stamps every newly-defined class with
 * {@code implements net.jonbell.crochet.runtime.CRIJInstrumented}, which the
 * mock-loader's parent chain typically cannot resolve — Crochet's runtime jar
 * is on the agent classloader, not on the parent of e.g. an internal Kafka
 * loader. The result is a CNFE at link-time of the synthetic mock class.
 *
 * <p><b>What this helper does.</b> When the patched {@code findClass} sees a
 * name in the {@code net.jonbell.crochet.runtime.} package, it routes the
 * resolution through this helper. We use {@link Class#forName} with
 * {@code initialize=false} against {@link CrochetAgent}'s classloader (which
 * by construction owns the runtime classes). {@code initialize=false} is
 * load-bearing: the same {@link Class} instance is returned every call, and
 * we never re-trigger the runtime class's {@code <clinit>} — exactly what we
 * need to avoid the double-load + SIGSEGV pattern that the prior
 * {@code -Xbootclasspath/a:} workaround tripped over.
 *
 * <p><b>Bootstrap safety.</b> We wrap the lookup in a {@link Throwable} catch
 * that yields {@code null} on any failure so the caller falls through to its
 * original throw-CNFE path. {@link CrochetAgent#getClass} is reachable as
 * long as the agent jar is on the classpath, but {@code CrochetAgent} is in a
 * package the transformer skips ({@code net/jonbell/crochet/agent/}), so
 * loading it never recurses into the bytecode chain. Both
 * {@link Class#forName} failures (CNFE, NoClassDefFoundError) and any
 * unexpected runtime errors land in the same {@code null} return; the caller
 * treats {@code null} as "not handled here" and runs its normal logic.
 */
public final class ByteBuddyClassLoaderSupport {

    private ByteBuddyClassLoaderSupport() {}

    /**
     * Returns the {@link Class} for {@code name} resolved via the agent
     * classloader, or {@code null} if it isn't a Crochet runtime class or
     * cannot be resolved. Never throws — all failures collapse to {@code null}
     * so the patched body can fall through to its original ClassNotFoundException
     * throw site.
     */
    public static Class<?> tryAgentClassLoader(String name) {
        if (name == null) {
            return null;
        }
        // Only intervene for our own runtime package — every other name must
        // continue to follow ByteBuddy's normal resolution. Keeping the prefix
        // check inline (rather than letting the bytecode emit handle it) means
        // any future non-runtime-prefix invocation here is a no-op.
        if (!name.startsWith("net.jonbell.crochet.runtime.")) {
            return null;
        }
        try {
            ClassLoader agentLoader = CrochetAgent.class.getClassLoader();
            // initialize=false: do not re-run <clinit>. Using the bootstrap
            // (null) loader as a fallback covers the packed-runtime case
            // where CRIJInstrumented is on java.base.
            return Class.forName(name, false,
                    agentLoader != null ? agentLoader
                            : ClassLoader.getSystemClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
