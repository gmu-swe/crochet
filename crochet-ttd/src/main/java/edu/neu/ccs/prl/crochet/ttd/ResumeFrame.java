package edu.neu.ccs.prl.crochet.ttd;

/**
 * Saved locals at one CPS save point inside a {@link TimeTravelBody}-annotated method.
 *
 * <p>This is the data structure the bytecode CPS transformer (B.3) creates at
 * every save point and that the dispatch prelude uses to restore locals on
 * resume.
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code methodId} — dense {@code int} assigned by
 *       {@link Ttd#internMethodId(String)}; unique per distinct method
 *       within the process lifetime.  The dispatch prelude compares this
 *       against its own statically-assigned id to decide whether the top-of-
 *       deque frame belongs to the current call frame.</li>
 *   <li>{@code bci} — bytecode index of the save point within the method.
 *       The dispatch prelude uses this as the table-switch key.</li>
 *   <li>{@code prims} — one {@code long} slot per primitive local; B.3
 *       zero-extends {@code float}/{@code int}/{@code short}/{@code char}/
 *       {@code byte}/{@code boolean} to {@code long}.  {@code double} and
 *       {@code long} occupy one slot each.  Array sized statically at
 *       transform time from the live-locals analysis (B.1).</li>
 *   <li>{@code refs} — one slot per reference-type local.  Array sized
 *       statically.  Entries may be {@code null} if the local was dead at
 *       the save point.</li>
 * </ul>
 *
 * <p><b>Mutability:</b> all fields are {@code final}; the arrays are mutable
 * but are not modified after construction.  This class is effectively
 * immutable.
 *
 * <p><b>Internal API.</b>  This class is public only because B.3 emits
 * bytecode that references it by name from user-class code.  It is not part
 * of Crochet's public API and may change without notice.
 *
 * <p>TODO: annotate with {@code @Internal} once unit A.4 (compose-kit) merges
 * and the annotation is available on this branch.
 */
public final class ResumeFrame {

    /** Dense method id assigned by {@link Ttd#internMethodId(String)}. */
    public final int methodId;

    /** Bytecode index of the save point (dispatch prelude table-switch key). */
    public final int bci;

    /**
     * Primitive locals, one {@code long} slot each.  Sized by the transformer
     * from the live-locals set; never {@code null}.
     */
    public final long[] prims;

    /**
     * Reference-type locals, one slot each.  Sized by the transformer from
     * the live-locals set; never {@code null}.
     */
    public final Object[] refs;

    /**
     * Construct a save-point record.
     *
     * <p>Called from bytecode emitted by B.3 at every save point inside an
     * active session.  The {@code prims} and {@code refs} arrays are owned by
     * this frame; callers must not mutate them after handing them to this
     * constructor.
     *
     * @param methodId method id as returned by {@link Ttd#internMethodId}
     * @param bci      save-point bytecode index
     * @param prims    primitive locals (never {@code null})
     * @param refs     reference locals (never {@code null})
     */
    public ResumeFrame(int methodId, int bci, long[] prims, Object[] refs) {
        this.methodId = methodId;
        this.bci = bci;
        this.prims = prims;
        this.refs = refs;
    }
}
