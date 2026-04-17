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
}
