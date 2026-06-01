package edu.neu.ccs.prl.crochet.ttd.nondet;

import net.jonbell.crochet.annotation.Stable;

/**
 * Structured event emitted when a replay diverges from its recording.
 *
 * <p>Schema:
 * <pre>
 *   int    siteId;       // which call site diverged
 *   String siteDesc;     // "owner/method/bci" for display
 *   long   recordedBits; // rawBits from recording (Long.MIN_VALUE if absent)
 *   long   actualBits;   // rawBits from the actual call at replay time
 *   byte   kind;         // NondetEvent.KIND_* constant
 *   String cause;        // QUEUE_EMPTY | SITE_ABSENT | WRONG_KIND
 * </pre>
 *
 * <p>Surface: the event is passed to
 * {@link NondetRecorder#getDivergenceHandler()} which by default calls
 * {@link NondetDivergenceHandler#onDivergence(NondetDivergenceEvent)}.
 * The REPL installs its own handler to route divergence events through
 * the REPL output channel.
 */
@Stable
public final class NondetDivergenceEvent {

    /** siteId was queued but the deque was empty (extra replay call). */
    public static final String CAUSE_QUEUE_EMPTY = "QUEUE_EMPTY";
    /** siteId was never recorded (call site not seen during recording). */
    public static final String CAUSE_SITE_ABSENT = "SITE_ABSENT";
    /** siteId event was found but the kind byte did not match. */
    public static final String CAUSE_WRONG_KIND  = "WRONG_KIND";

    /** Long.MIN_VALUE sentinel used when there is no recorded value to report. */
    public static final long NO_RECORDED_VALUE = Long.MIN_VALUE;

    public final int    siteId;
    public final String siteDesc;
    public final long   recordedBits;
    public final long   actualBits;
    public final byte   kind;
    public final String cause;

    public NondetDivergenceEvent(int siteId, String siteDesc,
                                  long recordedBits, long actualBits,
                                  byte kind, String cause) {
        this.siteId       = siteId;
        this.siteDesc     = siteDesc;
        this.recordedBits = recordedBits;
        this.actualBits   = actualBits;
        this.kind         = kind;
        this.cause        = cause;
    }

    @Override
    public String toString() {
        return "[ttd-nondet] DIVERGENCE siteId=" + siteId
                + " desc=" + siteDesc
                + " cause=" + cause
                + " recorded=" + recordedBits
                + " actual=" + actualBits
                + " kind=" + kind;
    }
}
