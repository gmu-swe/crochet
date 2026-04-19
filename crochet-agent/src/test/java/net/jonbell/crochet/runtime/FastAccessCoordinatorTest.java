package net.jonbell.crochet.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FastAccessCoordinator} — sanity checks the stripe
 * sizing, padding, and hash distribution.
 */
class FastAccessCoordinatorTest {

    @Test
    void stripeCountIsPowerOfTwoInClampedRange() {
        int n = FastAccessCoordinator.stripeCount();
        assertTrue(n >= 64, "stripe count must be at least 64, was " + n);
        assertTrue(n <= 4096, "stripe count must be at most 4096, was " + n);
        assertEquals(0, n & (n - 1), "stripe count must be a power of two, was " + n);
    }

    @Test
    void lockForReturnsStableLock() {
        Object target = new Object();
        FastAccessCoordinator.Stripe a = FastAccessCoordinator.lockFor(target);
        FastAccessCoordinator.Stripe b = FastAccessCoordinator.lockFor(target);
        assertNotNull(a);
        // identityHashCode is stable so the stripe must be stable too.
        assertSame(a, b);
        // Stripe must expose a usable ReentrantLock — see Tapestry stripefix
        // memo on FastAccessCoordinator: the synchronized form deadlocked
        // when stacked under Fray, so the lock primitive intentionally
        // routes through LockSupport (via AQS) so Fray can see contention.
        assertNotNull(a.lock);
        a.lock.lock();
        try {
            assertTrue(a.lock.isHeldByCurrentThread());
        } finally {
            a.lock.unlock();
        }
    }

    @Test
    void lockForSpreadsAcrossStripes() {
        // 10k fresh objects should land on many distinct stripes. The mixer
        // is idempotent (deterministic); all that matters is distinct-hash
        // targets map to at least some diversity of stripes. Collision rate
        // under an ideal mixer is ~count/stripes.
        int samples = 10_000;
        java.util.IdentityHashMap<FastAccessCoordinator.Stripe, Integer> stripeIds =
                new java.util.IdentityHashMap<>();
        int distinctStripes = 0;
        for (int i = 0; i < samples; i++) {
            Object o = new Object();
            FastAccessCoordinator.Stripe stripe = FastAccessCoordinator.lockFor(o);
            if (stripeIds.putIfAbsent(stripe, i) == null) {
                distinctStripes++;
            }
        }
        // With min=64 stripes and 10k samples, expect essentially all stripes
        // to be exercised (a pigeonhole check).
        assertTrue(distinctStripes >= 32,
                "stripes used: " + distinctStripes + " of " + FastAccessCoordinator.stripeCount());
    }
}
