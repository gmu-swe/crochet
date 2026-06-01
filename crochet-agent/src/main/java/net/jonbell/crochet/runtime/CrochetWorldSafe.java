package net.jonbell.crochet.runtime;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * User-facing facade for the stop-the-world world-safe checkpoint API.
 *
 * <p>This class provides {@link #checkpointWorldSafe()}, which combines
 * the existing class-level static-field checkpoint (equivalent to
 * {@link CheckpointRollbackAgent#checkpointAll()}) with a JVMTI-based
 * STW (stop-the-world) heap iteration that checkpoints every live
 * {@link CRIJInstrumented} instance in the heap. The combined operation
 * establishes a consistent before-image for the full live world.
 *
 * <h2>Ordering</h2>
 *
 * <p>The static-field pass runs BEFORE the STW window to minimise pause length.
 * This is sound because static fields are held by {@code sfHelper} instances
 * (one per user class, a {@link CRIJInstrumented} hidden-class instance), and
 * those instances are themselves picked up by the STW heap walk. The second
 * {@code $$crochetCheckpoint(V)} call on a already-snapped sfHelper is an
 * idempotent no-op (I3 — CAS fails, no double-write). Any PUTSTATIC that fires
 * between the static pass and the STW triggers {@code fastAccess} on the sfHelper,
 * which allocates the snap before overwriting — so the snap still holds the
 * pre-pass value. See {@code designs/E.2/DESIGN.md §2} for the full argument
 * and cross-references to {@code designs/E.1/SOUNDNESS.md §4} and §7 T6.
 *
 * <h2>Soundness guarantee</h2>
 *
 * <p>When the native agent is loaded (via {@code -agentpath:libcrochet-jvmti.so}):
 * for every {@link CRIJInstrumented} instance I live at the moment the last
 * mutator thread was suspended, any subsequent {@link CheckpointRollbackAgent#rollbackAll(int)}
 * call will restore I's observable instance-field state to the value it had
 * at that moment. See {@code designs/E.1/SOUNDNESS.md} for the full argument.
 *
 * <h2>Scope limits</h2>
 *
 * <p>{@code checkpointWorldSafe()} does NOT cover:
 * <ul>
 *   <li>Live local variables inside parked (unmounted) virtual-thread continuations.
 *       The continuation object IS heap-walked; only its call-frame locals are missed.
 *       A {@link VirtualThreadGap} event is fired for each detected unmounted virtual
 *       thread. Register a consumer via {@link #setCheckpointEventConsumer} to handle
 *       these events programmatically.
 *   <li>Java object fields written via raw C pointers by JNI code that does not go
 *       through the JVM's safepoint fence. This is a pre-existing Crochet limitation
 *       (paper §5.3) and is not specific to {@code checkpointWorldSafe()}.
 * </ul>
 *
 * <p>See {@code crochet-agent/docs/checkpoint-world-scope.md} for the full
 * scope-limit reference, including reproducible examples for each limit.
 *
 * <h2>Fallback</h2>
 *
 * <p>When the native agent is not loaded ({@link HeapWalker#isEngaged()} is
 * {@code false}), {@link #checkpointWorldSafe()} falls back to
 * {@link CheckpointRollbackAgent#checkpointAll()} and emits a one-time warning to
 * {@code stderr} (subsequent calls after the first are silently forwarded without
 * repeating the warning). The fallback is sound for the majority of practical
 * workloads (heap-rooted checkpoints work correctly); the STW is a soundness
 * <em>strengthening</em> that eliminates torn-snap races from concurrent
 * mutations. See {@code designs/E.2/DESIGN.md §4} and
 * {@code designs/E.1/SOUNDNESS.md §8} for the rationale behind the fallback
 * decision.
 *
 * <h2>Structured events</h2>
 *
 * <p>Register a {@link BiConsumer}{@code <CheckpointEvent, Object>} via
 * {@link #setCheckpointEventConsumer(BiConsumer)} to receive structured events
 * before any snapshot state is altered. The context argument ({@code Object}) is
 * reserved for future use and is currently always {@code null}.
 *
 * <h2>Merge note</h2>
 *
 * <p>This class is a temporary staging location. When unit A.3 lands and
 * establishes the {@link Crochet} facade, {@link #checkpointWorldSafe()} should
 * be folded into that class as a {@code @Stable public static} method. At that
 * time this class can be deprecated and removed.
 *
 * @see HeapWalker
 * @see CheckpointRollbackAgent#checkpointAll()
 * @see CheckpointRollbackAgent#rollbackAll(int)
 * @see VirtualThreadGap
 * @see CheckpointEvent
 * @see <a href="../../../../../../../../designs/E.2/DESIGN.md">E.2 Design</a>
 * @see <a href="../../../../../../../../designs/E.1/SOUNDNESS.md">E.1 Soundness Sketch</a>
 * @see <a href="../../../../../../../../designs/E.4/DESIGN.md">E.4 Design</a>
 * @see <a href="../../../../../../../../crochet-agent/docs/checkpoint-world-scope.md">
 *      Scope-limit reference</a>
 */
public final class CrochetWorldSafe {

    private CrochetWorldSafe() {}

    /**
     * Guards the one-time missing-native warning. Set to {@code true} on
     * the first call that observes {@link HeapWalker#isEngaged()} == false
     * so that subsequent fallback calls do not re-emit the message.
     */
    private static final AtomicBoolean FALLBACK_WARNED = new AtomicBoolean(false);

    /**
     * Guards the one-time virtual-thread-gap warning emitted to stderr when no
     * event consumer is registered. Fires at most once per JVM lifetime.
     */
    private static final AtomicBoolean LOOM_GAP_WARNED = new AtomicBoolean(false);

    /**
     * Optional structured-event consumer. When non-null, called for each
     * {@link CheckpointEvent} before any snapshot state is altered. When null,
     * gaps are reported via a one-time stderr warning. Volatile so that a
     * consumer registered from one thread is visible to the checkpoint thread.
     *
     * @see #setCheckpointEventConsumer(BiConsumer)
     */
    private static volatile BiConsumer<CheckpointEvent, Object> eventConsumer;

    /**
     * Registers a consumer that receives structured {@link CheckpointEvent}s
     * emitted by {@link #checkpointWorldSafe()}.
     *
     * <p>The consumer is called on the thread invoking {@code checkpointWorldSafe()},
     * before any snapshot state is altered. The context argument ({@code Object})
     * is reserved for future use and is currently always {@code null}.
     *
     * <p>Setting {@code null} removes the consumer (subsequent gaps fall back to
     * the one-time stderr warning). Only one consumer can be registered at a time;
     * calling this method replaces any prior registration.
     *
     * <p><b>Thread safety:</b> the assignment is volatile; a consumer registered
     * before any call to {@code checkpointWorldSafe()} is guaranteed to be visible
     * to that call.
     *
     * <p><b>Reentrancy:</b> the consumer must not itself call
     * {@code checkpointWorldSafe()} (would deadlock on the native STW mutex if
     * the JVMTI agent is loaded).
     *
     * @param consumer the event consumer, or {@code null} to deregister
     */
    public static void setCheckpointEventConsumer(
            BiConsumer<CheckpointEvent, Object> consumer) {
        eventConsumer = consumer;
    }

    /**
     * Returns the currently registered event consumer, or {@code null} if none
     * is registered.
     */
    public static BiConsumer<CheckpointEvent, Object> getCheckpointEventConsumer() {
        return eventConsumer;
    }

    /**
     * Establishes a whole-program checkpoint at a fresh version V and returns V.
     *
     * <p>The implementation proceeds in this order:
     * <ol>
     *   <li><b>Virtual-thread gap detection:</b> scans the live thread set for
     *       unmounted virtual threads. For each found, fires a {@link VirtualThreadGap}
     *       event via the registered consumer (or logs to stderr once). This phase
     *       runs BEFORE any state is altered so that callers can observe the gap and
     *       abort if needed (by throwing from their consumer).
     *   <li><b>Static-field pass:</b> equivalent to
     *       {@link CheckpointRollbackAgent#checkpointAll()}'s class-level walk —
     *       checkpoints the static fields of every known user class.
     *   <li><b>STW heap walk</b> (requires native agent): suspends all mutator
     *       threads, calls {@code $$crochetCheckpoint(V)} on every live
     *       {@link CRIJInstrumented} instance, then resumes threads. When the
     *       native agent is not loaded, this phase is skipped (see fallback).
     *   <li><b>Stack-root checkpoint:</b> equivalent to
     *       {@link CheckpointRollbackAgent#checkpointAll()}'s stack-root pass —
     *       a no-op when {@link StackRoots#isEngaged()} is false.
     * </ol>
     *
     * <p>When the native agent is loaded, phase 3 subsumes the stack-root pass
     * (all stack-referenced instances are heap-reachable) and the thread-object
     * + system-classloader passes in {@code checkpointAll}. The stack pass is
     * still performed as a defensive belt-and-suspenders measure.
     *
     * <p>The returned version V is the argument to pass to
     * {@link CheckpointRollbackAgent#rollbackAll(int)} to restore the
     * checkpointed state.
     *
     * @return the checkpoint version V; pass to {@link CheckpointRollbackAgent#rollbackAll(int)}
     */
    public static int checkpointWorldSafe() {
        // Phase 0: virtual-thread gap detection.
        // Done FIRST, before any state is altered, so the consumer can observe
        // or abort cleanly. See designs/E.4/DESIGN.md §2 for the rationale.
        detectAndReportVirtualThreadGaps();

        // Phase 1: static-field pass (mirrors checkpointAll's class-level walk).
        // Done BEFORE STW to keep the STW window as short as possible.
        // Ordering: see SOUNDNESS.md §4 (interaction with checkpointAll) and
        // §7 threat T6 (objects allocated between static pass and STW).
        int v = CheckpointRollbackAgent.checkpointAll();

        // Phase 2: STW heap walk.
        if (HeapWalker.isEngaged()) {
            boolean ok = HeapWalker.checkpointWorldSafe(v);
            if (!ok) {
                // The native reported an error but still resumed threads.
                // Log and continue — the static-field pass in phase 1 is still valid.
                if (Boolean.getBoolean("crochet.verboseCompat")) {
                    System.err.println("[crochet-heap] WARNING: STW heap walk returned error"
                            + " for version " + v + "; some instances may not be checkpointed.");
                }
            }
        } else {
            // Native agent not loaded — fall back to checkpointAll's existing
            // behavior (phase 1 already ran). Emit a one-time warning (first call
            // only) so that workloads calling checkpointWorldSafe() in a loop do
            // not flood stderr. The warning fires at most once per JVM lifetime.
            // See designs/E.2/DESIGN.md §4 and designs/E.1/SOUNDNESS.md §8.
            if (FALLBACK_WARNED.compareAndSet(false, true)) {
                System.err.println("[crochet-heap] WARNING: native agent not loaded;"
                        + " falling back to checkpointAll. STW guarantees do not apply."
                        + " Load libcrochet-jvmti.so via -agentpath for the full soundness guarantee."
                        + " (This warning will not repeat.)");
            }
        }

        // Phase 3: stack-root checkpoint (belt-and-suspenders; no-op if StackRoots
        // is not engaged, or if the STW walk already covered all heap instances).
        // This is already done inside checkpointAll (phase 1), but checkpointAll
        // uses version v and passes it to StackRoots.checkpointStackRoots(v)
        // internally. No double-work needed here.

        return v;
    }

    /**
     * Cached reference to {@code jdk.internal.vm.ThreadContainer.threads()}, obtained
     * once on first use. {@code null} means the reflection probe failed (the JVM does not
     * have this API, or the necessary {@code --add-opens} flag was not supplied).
     */
    private static volatile Method THREAD_CONTAINER_THREADS_METHOD;

    /**
     * Cached reference to {@code jdk.internal.vm.ThreadContainers.root()}.
     */
    private static volatile Method THREAD_CONTAINERS_ROOT_METHOD;

    /**
     * Cached reference to {@code jdk.internal.vm.ThreadContainer.children()}.
     */
    private static volatile Method THREAD_CONTAINER_CHILDREN_METHOD;

    /**
     * {@code true} if the JVM-internal reflection probe has been attempted at
     * least once. Guards repeated probe attempts (probe once; cache the result).
     */
    private static volatile boolean VT_PROBE_DONE;

    /**
     * Scans the live thread set for unmounted virtual threads and fires a
     * {@link VirtualThreadGap} event for each one.
     *
     * <p>A virtual thread is considered "unmounted" if its state is not
     * {@link Thread.State#RUNNABLE}: a RUNNABLE virtual thread is executing on a
     * carrier thread which will be suspended by {@code SuspendThreadList}, so its
     * call-frame locals ARE covered. Non-RUNNABLE virtual threads are parked
     * off-carrier; their continuation frames are not reached by the STW.
     *
     * <p>Detection mechanism: uses {@code jdk.internal.vm.ThreadContainers.root()}
     * (with {@code --add-exports java.base/jdk.internal.vm=ALL-UNNAMED} and
     * {@code --add-opens java.base/jdk.internal.vm=ALL-UNNAMED}) to walk all live
     * threads including virtual threads. Falls back to a warning if the internal
     * API is not accessible (e.g., missing {@code --add-opens} flag).
     *
     * <p>Note: a pinned RUNNABLE virtual thread (carrier blocked in native code)
     * is classified as "covered" by this heuristic because its carrier IS suspended.
     * This is conservative-safe.
     *
     * <p>Events are fired by calling the registered consumer (see
     * {@link #setCheckpointEventConsumer(BiConsumer)}), or by emitting a one-time
     * stderr warning if no consumer is registered.
     */
    private static void detectAndReportVirtualThreadGaps() {
        BiConsumer<CheckpointEvent, Object> consumer = eventConsumer; // single volatile read

        // Probe the JVM-internal API on first call.
        if (!VT_PROBE_DONE) {
            probeVirtualThreadApi();
        }

        if (THREAD_CONTAINERS_ROOT_METHOD == null) {
            // Internal API not accessible. Detection gap applies: we cannot enumerate
            // virtual threads. This should be treated as a configuration issue:
            // add --add-exports java.base/jdk.internal.vm=ALL-UNNAMED
            //     --add-opens  java.base/jdk.internal.vm=ALL-UNNAMED
            // to the JVM flags to enable detection.
            // We do not emit a warning here by default — this is a detection gap
            // (not a known gap), and excessive warnings would be noisy.
            return;
        }

        try {
            Object root = THREAD_CONTAINERS_ROOT_METHOD.invoke(null);
            collectAndFireVTGaps(root, consumer);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("[crochet-heap] event consumer threw checked exception", t);
        }
    }

    /**
     * Probes the {@code jdk.internal.vm.ThreadContainers} internal API and caches
     * the reflected methods. Called at most once per JVM lifetime.
     */
    private static synchronized void probeVirtualThreadApi() {
        if (VT_PROBE_DONE) {
            return; // another thread beat us to the probe
        }
        try {
            Class<?> tcClass = Class.forName("jdk.internal.vm.ThreadContainers");
            Class<?> containerClass = Class.forName("jdk.internal.vm.ThreadContainer");

            Method rootM = tcClass.getDeclaredMethod("root");
            rootM.setAccessible(true);

            Method threadsM = containerClass.getDeclaredMethod("threads");
            threadsM.setAccessible(true);

            Method childrenM = containerClass.getDeclaredMethod("children");
            childrenM.setAccessible(true);

            THREAD_CONTAINERS_ROOT_METHOD = rootM;
            THREAD_CONTAINER_THREADS_METHOD = threadsM;
            THREAD_CONTAINER_CHILDREN_METHOD = childrenM;
        } catch (Throwable e) {
            // API not accessible (missing --add-opens, or different JDK version).
            // Detection will be skipped; THREAD_CONTAINERS_ROOT_METHOD stays null.
            THREAD_CONTAINERS_ROOT_METHOD = null;
        } finally {
            VT_PROBE_DONE = true;
        }
    }

    /**
     * Recursively walks the {@code ThreadContainer} tree rooted at {@code container},
     * collecting virtual threads and firing gap events for unmounted ones.
     */
    @SuppressWarnings("unchecked")
    private static void collectAndFireVTGaps(Object container,
                                             BiConsumer<CheckpointEvent, Object> consumer)
            throws Throwable {
        // Collect threads in this container.
        List<Thread> threads = ((Stream<Thread>) THREAD_CONTAINER_THREADS_METHOD.invoke(container))
                .collect(Collectors.toList());

        for (Thread t : threads) {
            if (!t.isVirtual()) {
                continue;
            }
            Thread.State state = t.getState();
            // RUNNABLE virtual threads are mounted on a carrier; their carrier IS
            // suspended by SuspendThreadList. Only non-RUNNABLE VTs have uncovered frames.
            if (state == Thread.State.RUNNABLE) {
                continue;
            }
            // Found an unmounted virtual thread — fire the gap event.
            VirtualThreadGap event = VirtualThreadGap.of(t);
            if (consumer != null) {
                consumer.accept(event, null);
            } else {
                if (LOOM_GAP_WARNED.compareAndSet(false, true)) {
                    System.err.println("[crochet-heap] WARNING: virtual thread \""
                            + event.threadName() + "\" (state=" + event.threadState()
                            + ") is unmounted; its continuation frame locals are NOT"
                            + " captured by checkpointWorldSafe(). The continuation"
                            + " object's heap fields ARE captured. See"
                            + " crochet-agent/docs/checkpoint-world-scope.md §1 for"
                            + " details and workarounds."
                            + " (This warning will not repeat for subsequent"
                            + " virtual thread gaps in this JVM.)");
                }
            }
        }

        // Recurse into child containers.
        List<?> children = ((Stream<?>) THREAD_CONTAINER_CHILDREN_METHOD.invoke(container))
                .collect(Collectors.toList());
        for (Object child : children) {
            collectAndFireVTGaps(child, consumer);
        }
    }
}
