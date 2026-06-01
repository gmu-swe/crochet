package edu.neu.ccs.prl.crochet.ttd.nondet;

import net.jonbell.crochet.annotation.Stable;

/**
 * A single recorded nondeterministic return value.
 *
 * <p>Values are stored as a {@code long rawBits} field using the
 * following encoding:
 * <ul>
 *   <li>INT / HASHCODE: value widened to long (sign-extended)</li>
 *   <li>LONG: value as-is</li>
 *   <li>DOUBLE / FLOAT: {@link Double#doubleToRawLongBits} /
 *       {@link Float#floatToRawIntBits} widened to long</li>
 * </ul>
 *
 * <p>The {@code kind} byte allows the replay path to validate
 * that recording and replay are calling the same method shape.
 */
@Stable
public final class NondetEvent {

    /** INT: covers int-returning methods (nextInt, next, nextBoolean, identityHashCode, hashCode). */
    public static final byte KIND_INT = 0;
    /** LONG: covers long-returning methods (currentTimeMillis, nanoTime, nextLong). */
    public static final byte KIND_LONG = 1;
    /** DOUBLE: covers double-returning methods (nextDouble, nextGaussian, Math.random). */
    public static final byte KIND_DOUBLE = 2;
    /** FLOAT: covers float-returning methods (nextFloat). */
    public static final byte KIND_FLOAT = 3;

    public final int siteId;
    public final long rawBits;
    public final byte kind;

    public NondetEvent(int siteId, long rawBits, byte kind) {
        this.siteId = siteId;
        this.rawBits = rawBits;
        this.kind = kind;
    }

    /** Decode an INT event back to int. */
    public int asInt() {
        return (int) rawBits;
    }

    /** Decode a LONG event back to long. */
    public long asLong() {
        return rawBits;
    }

    /** Decode a DOUBLE event back to double. */
    public double asDouble() {
        return Double.longBitsToDouble(rawBits);
    }

    /** Decode a FLOAT event back to float. */
    public float asFloat() {
        return Float.intBitsToFloat((int) rawBits);
    }

    @Override
    public String toString() {
        return "NondetEvent{siteId=" + siteId + ", kind=" + kind + ", rawBits=" + rawBits + "}";
    }
}
