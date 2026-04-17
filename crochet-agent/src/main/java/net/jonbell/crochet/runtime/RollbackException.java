package net.jonbell.crochet.runtime;

public class RollbackException extends RuntimeException {

    private static final long serialVersionUID = 1486960037309581236L;

    public final int version;

    public RollbackException(int version) {
        this.version = version;
    }
}
