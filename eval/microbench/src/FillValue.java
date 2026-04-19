/**
 * Fill-value object used by the paper §5.1 microbench. The paper says the
 * values are "newly-created objects with no fields", so each instance carries
 * nothing but its identity hash.
 *
 * <p>On the crochet/crochet_cp configurations this class MUST be instrumented
 * so the injected {@code $$crochetVersion} / {@code $$crochetSnap} fields and
 * {@code $$crochetAccess()} hooks participate in checkpoint/rollback. The
 * paper's correctness criterion is identity-hash preservation across rollback
 * (Gap 2): a rolled-back value retains the same {@code System.identityHashCode}
 * as before, so XORing it back into the post-rollback checksum yields the same
 * integer.
 *
 * <p>We deliberately leave the class empty. A pre-existing field would be
 * subject to the usual crochet field hooks, but would add nothing to the
 * correctness signal the paper is after.
 */
public final class FillValue {
}
