package edu.neu.ccs.prl.crochet.ttd.nondet;

/**
 * Callback for replay divergence events.
 *
 * <p>The default implementation (used when no handler is installed) prints
 * to {@link System#err}. The TTD REPL installs its own handler to route
 * events through the REPL output stream.
 */
@FunctionalInterface
public interface NondetDivergenceHandler {

    /**
     * Called when a replay nondeterministic call diverges from its recording.
     *
     * @param event structured divergence event; never null
     */
    void onDivergence(NondetDivergenceEvent event);
}
