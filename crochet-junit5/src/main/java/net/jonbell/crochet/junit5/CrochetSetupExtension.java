package net.jonbell.crochet.junit5;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

/**
 * JUnit 5 extension that takes a Crochet checkpoint after the test class's
 * {@code @BeforeAll} completes, then rolls back to that checkpoint between
 * {@code @Test} methods. Lets test classes with expensive setup amortise
 * the cost across all their tests instead of paying it per test.
 *
 * <p><b>Usage:</b>
 * <pre>
 *   &#064;ExtendWith(CrochetSetupExtension.class)
 *   class MyTest {
 *       &#064;CrochetTrack static MyExpensiveResource resource;
 *
 *       &#064;BeforeAll static void setup() { resource = MyExpensiveResource.build(); }
 *
 *       &#064;Test void firstMutation()  { resource.add("a"); ... }
 *       &#064;Test void secondMutation() { resource.add("b"); ... }  // sees pristine resource
 *   }
 * </pre>
 *
 * <p><b>Mechanism:</b> implemented as a {@link BeforeEachCallback} so the
 * snapshot is taken on the first test's pre-iter callback (which fires after
 * all {@code @BeforeAll} methods complete and before any {@code @BeforeEach}
 * runs). The first call captures the post-{@code @BeforeAll} state; each
 * subsequent call rolls back to that captured state and immediately
 * re-checkpoints for the next iteration (Crochet's API consumes the snapshot
 * on rollback).
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>Run with {@code -javaagent:crochet-agent.jar} (or under a
 *       Crochet-instrumented JDK) so the tracked classes get the
 *       {@code $$crochet*} surface.</li>
 *   <li>Tracked fields must be static and non-null at the time the first
 *       test starts.</li>
 *   <li>{@code @BeforeEach} state mutations are NOT included in the
 *       checkpoint — only the post-{@code @BeforeAll} state is. This is
 *       deliberate: {@code @BeforeEach} re-runs each test as JUnit
 *       intends, while {@code @BeforeAll} is the cost we're amortising.</li>
 * </ul>
 */
public final class CrochetSetupExtension implements BeforeEachCallback, Extension {

    private static final Namespace NS =
            Namespace.create("net.jonbell.crochet.junit5");
    private static final String STATE_KEY = "tracked";

    @Override
    public void beforeEach(ExtensionContext context) {
        ExtensionContext classContext = context.getParent().orElseThrow();
        Store store = classContext.getStore(NS);
        TrackedState state = store.get(STATE_KEY, TrackedState.class);
        if (state == null) {
            state = takeInitialSnapshot(context.getRequiredTestClass());
            store.put(STATE_KEY, state);
        } else {
            rollbackAndRecheckpoint(state);
        }
    }

    private static TrackedState takeInitialSnapshot(Class<?> testClass) {
        TrackedState state = new TrackedState();
        for (Field f : findTrackedFields(testClass)) {
            f.setAccessible(true);
            Object value;
            try {
                value = f.get(null);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "@CrochetTrack field not accessible: " + f, e);
            }
            if (value == null) {
                throw new IllegalStateException(
                        "@CrochetTrack field is null at first-test entry: " + f
                                + " — initialise it in @BeforeAll.");
            }
            int version = CheckpointRollbackAgent.checkpoint(value);
            state.tracked.add(new TrackedRoot(f, value, version));
        }
        state.globalVersion = CheckpointRollbackAgent.checkpointAll();
        return state;
    }

    private static void rollbackAndRecheckpoint(TrackedState state) {
        for (TrackedRoot root : state.tracked) {
            CheckpointRollbackAgent.rollback(root.value, root.version);
        }
        CheckpointRollbackAgent.rollbackAll(state.globalVersion);
        for (TrackedRoot root : state.tracked) {
            root.version = CheckpointRollbackAgent.checkpoint(root.value);
        }
        state.globalVersion = CheckpointRollbackAgent.checkpointAll();
    }

    private static List<Field> findTrackedFields(Class<?> testClass) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = testClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(CrochetTrack.class)) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        throw new IllegalStateException(
                                "@CrochetTrack field must be static: " + f);
                    }
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static final class TrackedRoot {
        final Field field;
        final Object value;
        int version;

        TrackedRoot(Field field, Object value, int version) {
            this.field = field;
            this.value = value;
            this.version = version;
        }
    }

    private static final class TrackedState {
        final List<TrackedRoot> tracked = new ArrayList<>();
        int globalVersion;
    }
}
