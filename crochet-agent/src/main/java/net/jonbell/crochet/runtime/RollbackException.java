package net.jonbell.crochet.runtime;

public class RollbackException extends RuntimeException {

    private static final long serialVersionUID = 1486960037309581236L;

    /**
     * Sentinel version value meaning "the checkpoint or rollback itself failed
     * mid-flight". Callers receiving a poison exception should assume the
     * target object's state may be partially-updated and treat the object as
     * lost (or retry, if they have an outer snap).
     */
    public static final int POISON_VERSION = -1;

    public final int version;

    public RollbackException(int version) {
        this.version = version;
    }

    public RollbackException(int version, Throwable cause) {
        super(cause);
        this.version = version;
    }

    public boolean isPoison() {
        return version == POISON_VERSION;
    }

    /**
     * Thrown by {@link CheckpointRollbackAgent#rollbackAll(int)} when one or
     * more external-state hooks (registered via
     * {@link Crochet#registerExternalState}) threw during their restore pass.
     *
     * <p>The heap restore completes before this exception is raised — the heap
     * is in the post-rollback state, but one or more external resources (DB
     * cursors, file-descriptor offsets, etc.) may be inconsistent. Each
     * failing hook's exception is attached via {@link Throwable#addSuppressed};
     * the suppressed exception's message includes the hook name so users can
     * identify the offending adapter.
     *
     * <p>This exception carries {@link #POISON_VERSION} as its version because
     * the external state is potentially inconsistent after a hook failure.
     *
     * <p>Example catch:
     * <pre>{@code
     *   try {
     *       CheckpointRollbackAgent.rollbackAll(v);
     *   } catch (RollbackException.HookFailure hf) {
     *       for (Throwable sup : hf.getSuppressed()) {
     *           log.error("adapter restore failed: " + sup.getMessage(), sup.getCause());
     *       }
     *   }
     * }</pre>
     *
     * <p><b>Stability:</b> this class is {@code @Stable} user-facing API.
     * The class name, constructor, and {@code getSuppressed()} contract are
     * guaranteed not to change in a backwards-incompatible way.
     */
    public static final class HookFailure extends RollbackException {

        private static final long serialVersionUID = 7312847650319203891L;

        /**
         * Constructs a {@code HookFailure} with the given message. Individual
         * hook failures must be attached via {@link #addSuppressed} by the
         * caller.
         *
         * @param message human-readable summary (e.g. "3 external-state hook(s)
         *                failed during rollback")
         */
        public HookFailure(String message) {
            super(POISON_VERSION);
            // getMessage() builds the detail string dynamically from getSuppressed()
            // so the caller can attach suppressed exceptions after construction.
        }

        /**
         * Returns a message that includes the count and names of all failed
         * hooks. The message is built dynamically from the attached suppressed
         * exceptions so it is always accurate regardless of when they were
         * added.
         */
        @Override
        public String getMessage() {
            Throwable[] sup = getSuppressed();
            if (sup.length == 0) {
                return "external-state hook restore failed (no suppressed detail)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(sup.length).append(" external-state hook(s) failed during rollback:");
            for (Throwable t : sup) {
                sb.append("\n  ").append(t.getMessage());
            }
            return sb.toString();
        }
    }
}
