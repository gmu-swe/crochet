package net.jonbell.crochet.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Smoke test exercising the BeforeAll-checkpoint / between-test-rollback
 * lifecycle. Uses a hand-rolled state class (not a JDK collection) so the
 * test passes under the {@code -javaagent} path without requiring the
 * Crochet-instrumented JDK — the agent transforms user classes at load
 * time, which is what this test exercises.
 */
@ExtendWith(CrochetSetupExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrochetSetupExtensionSmokeTest {

    /**
     * Hand-rolled mutable state. Fields are primitive + reference to
     * another instrumented type so the entire mutation graph is captured by
     * Crochet's per-field hooks. Avoids HashMap / ArrayList because those
     * are JDK classes; the runtime {@code -javaagent} doesn't transform
     * pre-loaded JDK classes (an instrumented-JDK build path would).
     */
    static final class State {
        int totalAdds;
        String lastTag;
        Slot slot;
    }

    static final class Slot {
        int value;
        Slot(int value) { this.value = value; }
    }

    @CrochetTrack
    static State state;

    @BeforeAll
    static void setup() {
        state = new State();
        state.totalAdds = 1;
        state.lastTag = "seed";
        state.slot = new Slot(42);
    }

    @Test
    @Order(1)
    void firstTest_seesPristine_thenMutates() {
        assertEquals(1, state.totalAdds);
        assertEquals("seed", state.lastTag);
        assertEquals(42, state.slot.value);

        state.totalAdds = 100;
        state.lastTag = "test1";
        state.slot.value = 999;
        state.slot = new Slot(7);
    }

    @Test
    @Order(2)
    void secondTest_seesPristineAgain() {
        assertEquals(1, state.totalAdds, "totalAdds rolled back");
        assertEquals("seed", state.lastTag, "lastTag rolled back");
        assertEquals(42, state.slot.value, "slot.value rolled back (transitive)");

        state.totalAdds = 200;
        state.lastTag = "test2";
        state.slot = null;
    }

    @Test
    @Order(3)
    void thirdTest_alsoPristine() {
        assertEquals(1, state.totalAdds);
        assertEquals("seed", state.lastTag);
        assertEquals(42, state.slot.value);
    }
}
