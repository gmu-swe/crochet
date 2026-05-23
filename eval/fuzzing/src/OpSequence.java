package eval.fuzzing;

import java.util.Arrays;
import java.util.Random;

/**
 * Fuzz input: a flat {@code byte[]} interpreted as a sequence of ops.
 *
 * <p>Each op consumes 4 bytes: {@code [opcode | arg0 | arg1 | arg2]}. Opcodes
 * are mapped to {@link PoolFleet} ops via {@link #execute}. Out-of-range bytes
 * are reduced mod-N inside the target. Inputs that consume past the end of the
 * buffer simply stop.
 *
 * <p>{@code byte[]} reps are AFL-style: cheap to mutate, splice, and serialise.
 */
public final class OpSequence {

    public static final int OPCODE_BORROW = 0;
    public static final int OPCODE_RETURN = 1;
    public static final int OPCODE_INVALIDATE = 2;
    public static final int OPCODE_CLEAR = 3;
    public static final int OPCODE_EVICT = 4;
    public static final int OPCODE_SET_MAX_TOTAL = 5;
    public static final int OPCODE_SET_MAX_IDLE = 6;
    public static final int OPCODE_SET_MIN_IDLE = 7;
    public static final int OPCODE_PREPARE = 8;
    public static final int OPCODE_ADD_OBJECTS = 9;
    public static final int OPCODE_SET_TOB = 10;
    public static final int OPCODE_SET_BWE = 11;
    public static final int OPCODE_CROSS_MOVE = 12;
    public static final int OPCODE_COUNT = 13;

    public byte[] bytes;

    public OpSequence(byte[] bytes) {
        this.bytes = bytes;
    }

    public static OpSequence random(Random r, int targetLenOps) {
        int n = Math.max(4, targetLenOps) * 4;
        byte[] b = new byte[n];
        r.nextBytes(b);
        return new OpSequence(b);
    }

    /** Execute the sequence against the target. Returns ops actually performed. */
    public int execute(PoolFleet target) {
        int i = 0;
        int count = 0;
        while (i + 3 < bytes.length) {
            int op = Math.floorMod(bytes[i] & 0xFF, OPCODE_COUNT);
            int a0 = bytes[i + 1] & 0xFF;
            int a1 = bytes[i + 2] & 0xFF;
            int a2 = bytes[i + 3] & 0xFF;
            switch (op) {
                case OPCODE_BORROW: target.opBorrow(a0); break;
                case OPCODE_RETURN: target.opReturn(a0); break;
                case OPCODE_INVALIDATE: target.opInvalidate(a0); break;
                case OPCODE_CLEAR: target.opClear(a0); break;
                case OPCODE_EVICT: target.opEvict(a0); break;
                case OPCODE_SET_MAX_TOTAL: target.opSetMaxTotal(a0, a1); break;
                case OPCODE_SET_MAX_IDLE: target.opSetMaxIdle(a0, a1); break;
                case OPCODE_SET_MIN_IDLE: target.opSetMinIdle(a0, a1); break;
                case OPCODE_PREPARE: target.opPreparePool(a0); break;
                case OPCODE_ADD_OBJECTS: target.opAddObjects(a0, a1); break;
                case OPCODE_SET_TOB: target.opSetTestOnBorrow(a0, (a1 & 1) == 1); break;
                case OPCODE_SET_BWE: target.opSetBlockWhenExhausted(a0, (a1 & 1) == 1); break;
                case OPCODE_CROSS_MOVE: target.opCrossPoolMove(a0, a2); break;
                default: break;
            }
            i += 4;
            count++;
        }
        return count;
    }

    /**
     * Havoc-mutate this input in place: random bit flip, byte set, arithmetic
     * change, splice from another input, or repeat-segment. Returns a new
     * {@code OpSequence} (does not mutate {@code this}).
     */
    public OpSequence mutate(Random r, OpSequence spliceSrc) {
        byte[] src = bytes;
        byte[] out;
        int kind = r.nextInt(8);
        switch (kind) {
            case 0: { // bit flip
                out = src.clone();
                if (out.length == 0) return new OpSequence(out);
                int pos = r.nextInt(out.length);
                out[pos] ^= (byte) (1 << r.nextInt(8));
                return new OpSequence(out);
            }
            case 1: { // byte set
                out = src.clone();
                if (out.length == 0) return new OpSequence(out);
                int pos = r.nextInt(out.length);
                out[pos] = (byte) r.nextInt(256);
                return new OpSequence(out);
            }
            case 2: { // arithmetic
                out = src.clone();
                if (out.length == 0) return new OpSequence(out);
                int pos = r.nextInt(out.length);
                out[pos] = (byte) ((out[pos] & 0xFF) + (r.nextInt(35) - 17));
                return new OpSequence(out);
            }
            case 3: { // insert 4-op block
                out = Arrays.copyOf(src, src.length + 4);
                int insert = src.length == 0 ? 0 : (r.nextInt(src.length / 4 + 1) * 4);
                System.arraycopy(src, insert, out, insert + 4, src.length - insert);
                for (int j = 0; j < 4; j++) out[insert + j] = (byte) r.nextInt(256);
                return new OpSequence(out);
            }
            case 4: { // delete 4-op block
                if (src.length <= 8) return new OpSequence(src.clone());
                int del = (r.nextInt(src.length / 4) * 4);
                out = new byte[src.length - 4];
                System.arraycopy(src, 0, out, 0, del);
                System.arraycopy(src, del + 4, out, del, src.length - del - 4);
                return new OpSequence(out);
            }
            case 5: { // splice
                if (spliceSrc == null || spliceSrc.bytes.length < 4 || src.length < 4) {
                    return mutate(r, null); // re-roll
                }
                byte[] other = spliceSrc.bytes;
                int srcCut = (r.nextInt(src.length / 4 + 1)) * 4;
                int otherCut = (r.nextInt(other.length / 4 + 1)) * 4;
                out = new byte[srcCut + (other.length - otherCut)];
                System.arraycopy(src, 0, out, 0, srcCut);
                System.arraycopy(other, otherCut, out, srcCut, other.length - otherCut);
                return new OpSequence(out);
            }
            case 6: { // duplicate region
                if (src.length < 8) return new OpSequence(src.clone());
                int region = ((1 + r.nextInt(4)) * 4);
                if (region > src.length) region = src.length;
                int start = (r.nextInt(src.length / 4)) * 4;
                if (start + region > src.length) region = src.length - start;
                out = new byte[src.length + region];
                System.arraycopy(src, 0, out, 0, start);
                System.arraycopy(src, start, out, start, region);
                System.arraycopy(src, start, out, start + region, src.length - start);
                return new OpSequence(out);
            }
            default: { // havoc — multiple bit flips
                out = src.clone();
                int n = 2 + r.nextInt(6);
                for (int k = 0; k < n && out.length > 0; k++) {
                    int pos = r.nextInt(out.length);
                    out[pos] ^= (byte) (1 << r.nextInt(8));
                }
                return new OpSequence(out);
            }
        }
    }

    public OpSequence copy() {
        return new OpSequence(bytes.clone());
    }

    public int lenBytes() {
        return bytes.length;
    }

    public int lenOps() {
        return bytes.length / 4;
    }
}
