package net.jonbell.crochet.transform;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

/**
 * Owns the <em>single</em> {@link LocalVariablesSorter} per method for the
 * whole transform chain. Upstream visitors that need scratch locals hold a
 * reference to this provider and call {@link #newLocal(Type)} to reserve a
 * slot and {@link #emitVarInsn(int, int)} to emit store/load for that slot.
 *
 * <p>Rationale: stacking multiple LVS instances — one per visitor that wanted
 * a scratch slot — produced cumulative local-index rewrites that confused
 * {@code COMPUTE_FRAMES} on large DaCapo methods (VerifyError "Bad local
 * variable type" on fop's FObj, h2's Parser). With a single LVS, all
 * allocations draw from one counter.
 *
 * <p>Why {@link #emitVarInsn} instead of upstream visitors routing through
 * {@code super.visitVarInsn}: LVS keys its remap table by {@code 2*var+size-1}
 * — so {@code (var=N, INT)} and {@code (var=N, OBJECT)} map to the same key.
 * When an upstream emit for a scratch slot flows through LVS, LVS re-allocates
 * via its remap table and can alias the scratch slot with an <em>original</em>
 * slot that shares the numeric index. On h2's {@code Parser.parseCreate} this
 * aliased our OBJECT scratch with the remapped INT local 16, and
 * COMPUTE_FRAMES produced {@code top} at merge points where the two live
 * ranges met — breaking a downstream {@code iload} with "Type top is not
 * assignable to integer".
 *
 * <p>The fix matches ASM's documented pattern: after {@code newLocal},
 * emit the store/load to the LVS's <em>delegate</em> (its inherited {@code mv}
 * field), bypassing the remap table entirely. {@link #emitVarInsn} does this
 * via an LVS subclass that exposes a raw emit.
 */
public final class SharedLocalsProvider extends ClassVisitor {

    private ExposedLvs current;
    /**
     * Per-method cache of scratch slots, keyed by {@link Type}. Wrap-style
     * scratch lifetimes (xSTORE → hook → xLOAD → xASTORE) are strictly local,
     * so every wrap of the same type within a single method can safely share
     * one slot. Reset on each {@link #visitMethod} entry.
     *
     * <p>Keyed with {@link HashMap} not {@code IdentityHashMap}:
     * {@link Type#equals} compares by descriptor, so e.g. {@code Type.INT_TYPE}
     * references from different call-sites are hash-equal. Identity keys
     * would silently defeat sharing.
     */
    private final Map<Type, Integer> scratchByType = new HashMap<>();

    public SharedLocalsProvider(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) {
            current = null;
            scratchByType.clear();
            return null;
        }
        current = new ExposedLvs(api, access, descriptor, base);
        scratchByType.clear();
        return current;
    }

    /**
     * Reserve a fresh local slot of the given {@link Type} in the current
     * method. The slot is allocated above the method's original max-locals
     * range; it will not alias any original local.
     */
    public int newLocal(Type type) {
        if (current == null) {
            throw new IllegalStateException(
                    "newLocal called outside a method visit — SharedLocalsProvider "
                    + "must be placed in the chain above any visitor that allocates locals.");
        }
        return current.newLocal(type);
    }

    /**
     * Return a per-method scratch slot for {@code type}, allocating on first
     * request and reusing thereafter. Intended for short-lived wrap scratches
     * (xSTORE → hook → xLOAD → xASTORE) whose live ranges never overlap across
     * wraps of the same type — the sequence is linear per wrap and type
     * consistency keeps COMPUTE_FRAMES from merging the slot to {@code top}.
     *
     * <p>Keeps {@link #newLocal(Type)} available for callers that genuinely
     * need a fresh slot per invocation.
     */
    public int sharedScratch(Type type) {
        if (current == null) {
            throw new IllegalStateException(
                    "sharedScratch called outside a method visit — SharedLocalsProvider "
                    + "must be placed in the chain above any visitor that allocates locals.");
        }
        Integer existing = scratchByType.get(type);
        if (existing != null) {
            return existing;
        }
        int slot = current.newLocal(type);
        scratchByType.put(type, slot);
        return slot;
    }

    /**
     * Emit a {@code visitVarInsn(opcode, slot)} for a previously-allocated
     * scratch slot. Bypasses the LVS's remap table — required because the
     * remap keys {@code (var, INT)} and {@code (var, OBJECT)} to the same
     * slot, and would otherwise alias the scratch with an original local.
     */
    public void emitVarInsn(int opcode, int slot) {
        if (current == null) {
            throw new IllegalStateException(
                    "emitVarInsn called outside a method visit.");
        }
        current.emitRaw(opcode, slot);
    }

    /**
     * LVS subclass whose only job is to expose a raw-emit path for slots
     * allocated via {@link LocalVariablesSorter#newLocal(Type)}. We keep the
     * subclass internal so the rest of the transform chain still "sees" the
     * provider as the single LVS in the chain without knowing this detail.
     */
    private static final class ExposedLvs extends LocalVariablesSorter {
        ExposedLvs(int api, int access, String descriptor, MethodVisitor delegate) {
            super(api, access, descriptor, delegate);
        }

        void emitRaw(int opcode, int slot) {
            // mv is the LVS's inherited delegate (the writer-side MV below
            // LVS in the chain). Emitting here skips LVS's visitVarInsn →
            // remap path, so the scratch slot goes through to the writer
            // with its actual allocated index preserved.
            mv.visitVarInsn(opcode, slot);
        }
    }
}
