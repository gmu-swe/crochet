package net.jonbell.crochet.runtime;

import net.jonbell.crochet.annotation.Stable;

/**
 * Marker interface added to every instrumented user class. The method names
 * mirror the legacy CROCHET contract — the stub generator and field rewriter
 * both reference them by name in emitted bytecode, so renaming is not free.
 */
@Stable
public interface CRIJInstrumented {

    void $$crochetCopyFieldsTo(Object to);

    void $$crochetCopyFieldsFrom(Object old);

    void $$crochetCheckpoint(int version);

    void $$crochetRollback(int version);

    void $$crochetPropagateCheckpoint(int version);

    void $$crochetPropagateRollback(int version);

    int $$crochetGetVersion();

    void $$crochetSetVersion(int version);

    Object $$crochetGetSnap();

    void $$crochetSetSnap(Object snap);

    void $$crochetAccess();

    boolean $$crochetIsRollbackState();
}
