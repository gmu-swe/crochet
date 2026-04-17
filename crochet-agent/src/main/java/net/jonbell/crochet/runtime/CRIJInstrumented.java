package net.jonbell.crochet.runtime;

/**
 * Marker interface added to every instrumented user class. The method names
 * mirror the legacy CROCHET contract — the stub generator and field rewriter
 * both reference them by name in emitted bytecode, so renaming is not free.
 */
public interface CRIJInstrumented {

    void $$crochetCopyFieldsTo(Object to);

    void $$crochetCopyFieldsFrom(Object old);

    void $$crochetCheckpoint(int version);

    void $$crochetRollback(int version);

    void $$crochetPropagateCheckpoint(int version);

    void $$crochetPropagateRollback(int version);

    int $$crochetGetVersion();

    void $$crochetSetVersion(int version);

    void $$crochetAccess();

    boolean $$crochetIsRollbackState();
}
