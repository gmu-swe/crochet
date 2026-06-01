package prl.crochet.ttd.testsubject;

import java.util.Random;

/**
 * Target class for nondet transformer tests. This class is in a package that
 * does NOT start with any of NondetTransformer's skip prefixes, so its
 * call sites ARE rewritten by NondetTransformer.
 *
 * <p>The package name "prl.crochet.ttd.testsubject" is deliberately short
 * to avoid matching "edu/neu/ccs/prl/crochet/ttd/" (the full prefix used
 * in production skip-list checks).
 */
public final class NondetTarget {

    public long callCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    public long callNanoTime() {
        return System.nanoTime();
    }

    public int callIdentityHashCode(Object o) {
        return System.identityHashCode(o);
    }

    public int callNextInt(Random rng) {
        return rng.nextInt();
    }

    public long callNextLong(Random rng) {
        return rng.nextLong();
    }

    public double callNextDouble(Random rng) {
        return rng.nextDouble();
    }

    public float callNextFloat(Random rng) {
        return rng.nextFloat();
    }

    public boolean callNextBoolean(Random rng) {
        return rng.nextBoolean();
    }

    public double callNextGaussian(Random rng) {
        return rng.nextGaussian();
    }

    public double callMathRandom() {
        return Math.random();
    }

    public int callNextIntBound(Random rng, int bound) {
        return rng.nextInt(bound);
    }
}
