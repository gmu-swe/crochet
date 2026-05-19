package edu.neu.ccs.prl.crochet.ttd.cps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Computes the set of live locals at each requested save-point BCI in a method.
 *
 * <p><b>Algorithm.</b> Uses ASM's {@code Analyzer<BasicValue>}
 * with {@code BasicInterpreter} to compute typed frames via a standard forward
 * data-flow over the CFG. At each save-point BCI, a slot is considered "live"
 * iff its frame entry is not {@code BasicValue.UNINITIALIZED_VALUE} (i.e., it
 * holds a known-typed value).
 *
 * <p><b>Two-slot types (long, double).</b> ASM occupies two slots for category-2
 * types: slot N holds the actual {@link BasicValue} with
 * {@code type.getSize() == 2}, and slot N+1 holds a TOP placeholder
 * ({@link BasicValue#UNINITIALIZED_VALUE}). We emit exactly one
 * {@link LiveLocal}({@code N}, type) for the pair and skip slot N+1.
 *
 * <p><b>Branch-join soundness.</b> The forward analysis merges frames at join
 * points conservatively: if a local is live on any predecessor branch it will
 * be non-TOP at the join. This ensures that any value that <em>might</em> be
 * needed on a successor path is captured at the save point.
 *
 * <p><b>Exception-edge handling.</b> ASM's {@code Analyzer} propagates frames
 * through exception edges automatically. A local live in a {@code catch} handler
 * will be non-TOP at the corresponding throwing instruction, satisfying the
 * try/catch liveness requirement.
 *
 * <p><b>Uninitialized-this rejection.</b> A save point inside a {@code <init>}
 * method before the {@code super()} / {@code this()} call is rejected with an
 * {@link IllegalStateException} naming the offending method, because the JVM
 * verifier will not accept resuming into a not-yet-constructed {@code this}.
 * Detection: scan the instruction list for the first {@code INVOKESPECIAL <init>}
 * call (the super/delegate constructor); any save-point BCI strictly before
 * that index is rejected. Note: {@code BasicInterpreter} does NOT distinguish
 * uninitialized-this from a live reference (both appear as {@code Object}),
 * so frame inspection alone is insufficient — bytecode scanning is required.
 *
 * <p><b>Output ordering.</b> Each {@code List<LiveLocal>} is sorted ascending
 * by {@link LiveLocal#slotIndex()} for determinism (universal gate 18).
 *
 * <p><b>Consumer contract.</b> The caller supplies the set of save-point BCIs
 * (instruction indices in the method's instruction list). B.3 supplies line-marker
 * BCIs and callsite BCIs; the analyzer is agnostic about which instructions are
 * chosen.
 *
 * @see LiveLocal
 * @since B.1
 */
public final class LivenessAnalyzer {

    /**
     * A live local variable at a particular save point.
     *
     * @param slotIndex the local-variable table index (0-based). For category-2
     *                  types ({@code long}, {@code double}), this is the first of
     *                  the two physical slots; the second slot is implicit and NOT
     *                  reported as a separate entry.
     * @param type      the ASM {@link Type} of the local. {@code type.getSize()}
     *                  is 1 for category-1 types and 2 for {@code long}/{@code double}.
     */
    public record LiveLocal(int slotIndex, Type type)
            implements Comparable<LiveLocal> {

        /**
         * Natural ordering by slot index, ascending. Required for deterministic
         * output across runs (universal gate 18).
         */
        @Override
        public int compareTo(LiveLocal other) {
            return Integer.compare(this.slotIndex, other.slotIndex);
        }
    }

    /**
     * Analyzes liveness at each requested save-point BCI in {@code method}.
     *
     * @param ownerInternalName the internal class name (e.g. {@code "com/example/Foo"}),
     *                          used in error messages and as required by ASM's Analyzer.
     * @param method            the method to analyze. Must be a concrete (non-abstract,
     *                          non-native) method with a non-null instruction list.
     * @param savePointBcis     the set of instruction indices at which liveness is
     *                          requested. An index is the position in
     *                          {@code method.instructions} (0-based, as returned by
     *                          {@code method.instructions.indexOf(insn)}). Indices
     *                          outside the method's instruction range are silently
     *                          ignored.
     * @return an immutable map from each save-point BCI (that fell within the
     *         instruction range) to the sorted list of live locals at that BCI.
     *         BCIs for which the analysis found the instruction unreachable
     *         (dead code) return an empty list.
     * @throws AnalyzerException       if ASM's frame analysis fails (e.g. invalid
     *                                 bytecode).
     * @throws IllegalStateException   if a save point falls inside a {@code <init>}
     *                                 method at a BCI where {@code this} is still
     *                                 uninitialized. Message format:
     *                                 {@code "Save point in <init> before super() in <owner>.<descriptor>"}.
     * @throws IllegalArgumentException if {@code method} is abstract or native.
     */
    public Map<Integer, List<LiveLocal>> analyze(
            String ownerInternalName,
            MethodNode method,
            Set<Integer> savePointBcis) throws AnalyzerException {

        if (savePointBcis == null || savePointBcis.isEmpty()) {
            return Collections.emptyMap();
        }

        int flags = method.access;
        if ((flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalArgumentException(
                    "Cannot analyze abstract or native method: "
                            + ownerInternalName + "." + method.name + method.desc);
        }

        int insnCount = method.instructions.size();

        // Run the forward typed analysis.
        Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
        Frame<BasicValue>[] frames = analyzer.analyze(ownerInternalName, method);

        boolean isInit = "<init>".equals(method.name);

        // For <init> methods: locate the BCI of the first INVOKESPECIAL <init>
        // call on the same 'this' slot (i.e., the super() or this() delegate call).
        // Save points BEFORE this BCI are invalid: 'this' is uninitialized and the
        // JVM verifier will reject any attempt to resume into such a frame.
        // Note: BasicInterpreter does NOT distinguish uninitialized-this from a live
        // reference — it maps both to Object. Detection must be done by scanning
        // the instruction list directly for the first INVOKESPECIAL <init> call.
        int superCallBci = isInit ? findSuperCallBci(method) : Integer.MAX_VALUE;

        Map<Integer, List<LiveLocal>> result = new HashMap<>();

        for (int bci : savePointBcis) {
            if (bci < 0 || bci >= insnCount) {
                continue; // silently ignore out-of-range BCIs
            }

            // Reject save points in <init> before super() / this().
            if (isInit && bci < superCallBci) {
                throw new IllegalStateException(
                        "Save point in <init> before super() in "
                                + ownerInternalName + "." + method.name + method.desc
                                + " (save-point BCI=" + bci
                                + ", super-call BCI=" + superCallBci + ")");
            }

            Frame<BasicValue> frame = frames[bci];
            if (frame == null) {
                // Unreachable instruction (dead code).
                result.put(bci, Collections.emptyList());
                continue;
            }

            List<LiveLocal> liveLocals = extractLiveLocals(frame);
            result.put(bci, Collections.unmodifiableList(liveLocals));
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Finds the instruction index (BCI) of the first {@code INVOKESPECIAL <init>}
     * call in an {@code <init>} method — this is the {@code super()} or
     * {@code this()} delegate call that initialises {@code this}.
     *
     * <p>Returns {@link Integer#MAX_VALUE} if no such call is found (degenerate
     * method; treat all BCIs as safe in that case).
     */
    private static int findSuperCallBci(MethodNode method) {
        int bci = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if ("<init>".equals(mi.name)) {
                    // This is the super() or this() call.
                    return bci;
                }
            }
            bci++;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Extracts all live locals from {@code frame}, handling category-2 types.
     *
     * <p>A slot is live iff its value is not {@link BasicValue#UNINITIALIZED_VALUE}
     * (TOP). For a size-2 type at slot N, slot N+1 is the TOP placeholder and is
     * skipped.
     */
    private static List<LiveLocal> extractLiveLocals(Frame<BasicValue> frame) {
        int maxLocals = frame.getLocals();
        List<LiveLocal> liveLocals = new ArrayList<>();

        int slot = 0;
        while (slot < maxLocals) {
            BasicValue value = frame.getLocal(slot);
            if (value == null || value == BasicValue.UNINITIALIZED_VALUE) {
                slot++;
                continue;
            }
            Type type = value.getType();
            if (type == null) {
                // null type also signals an uninitialized/TOP value.
                slot++;
                continue;
            }
            liveLocals.add(new LiveLocal(slot, type));
            // Skip the phantom second slot for category-2 types.
            slot += type.getSize();
        }

        Collections.sort(liveLocals); // ascending by slotIndex for determinism
        return liveLocals;
    }

}
