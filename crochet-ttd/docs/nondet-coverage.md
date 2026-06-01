# Nondeterministic Source Coverage for TTD Record/Replay

This document enumerates which nondeterministic JDK methods are intercepted
by `NondetTransformer` / `NondetRecorder` (D.3), and which are explicitly
left uncovered with rationale.

## Covered methods

The following call sites are rewritten at the bytecode level by
`NondetTransformer`. When a TTD session is recording, each call's return
value is logged. When replaying, the logged value is returned instead of
calling the real method.

| Method | Return type | Helper in NondetRecorder |
|--------|-------------|--------------------------|
| `java.lang.System.currentTimeMillis()` | `long` | `fetchOrCallCurrentTimeMillis(I)J` |
| `java.lang.System.nanoTime()` | `long` | `fetchOrCallNanoTime(I)J` |
| `java.lang.System.identityHashCode(Object)` | `int` | `fetchOrCallIdentityHashCode(Ljava/lang/Object;I)I` |
| `java.lang.Object.hashCode()` (static type = Object only) | `int` | `fetchOrCallObjectHashCode(Ljava/lang/Object;I)I` |
| `java.util.Random.next(int)` | `int` | `fetchOrCallRandomNext(Ljava/util/Random;II)I` |
| `java.util.Random.nextInt()` | `int` | `fetchOrCallNextInt(Ljava/util/Random;I)I` |
| `java.util.Random.nextInt(int)` | `int` | `fetchOrCallNextIntBound(Ljava/util/Random;II)I` |
| `java.util.Random.nextLong()` | `long` | `fetchOrCallNextLong(Ljava/util/Random;I)J` |
| `java.util.Random.nextDouble()` | `double` | `fetchOrCallNextDouble(Ljava/util/Random;I)D` |
| `java.util.Random.nextFloat()` | `float` | `fetchOrCallNextFloat(Ljava/util/Random;I)F` |
| `java.util.Random.nextBoolean()` | `boolean` | `fetchOrCallNextBoolean(Ljava/util/Random;I)Z` |
| `java.util.Random.nextGaussian()` | `double` | `fetchOrCallNextGaussian(Ljava/util/Random;I)D` |
| `java.lang.Math.random()` | `double` | `fetchOrCallMathRandom(I)D` |

### Important scope constraints for covered methods

- **`Object.hashCode()` coverage is limited to call sites whose static
  receiver type is exactly `java.lang.Object`** (i.e., the instruction is
  `INVOKEVIRTUAL java/lang/Object hashCode ()I`). If the compiler emits
  `INVOKEVIRTUAL MyClass hashCode ()I`, that call site is NOT intercepted
  — it is user-defined and presumed deterministic. This covers the most
  common nondeterminism source (identity-hash-based hashCode on plain
  Object references used as map keys).

- **`Random` coverage applies to `java.util.Random` call sites only.**
  Subclasses (`ThreadLocalRandom`, `SplittableRandom`, custom subclasses)
  are NOT intercepted unless the static type at the call site is
  `java.util.Random`. See "Uncovered" below.

## Uncovered sources (explicit non-coverage with rationale)

### File I/O

`FileInputStream.read`, `FileOutputStream.write`, `RandomAccessFile`,
`Files.*`, `Path.*`, `FileChannel.*`, etc.

**Rationale**: IO is stateful and side-effecting. Replaying file reads
requires the filesystem to be in the same state as during recording, or
storing the full byte sequences (potentially gigabytes). This is beyond
the scope of lightweight TTD. Use mocks (`ByteArrayInputStream`, etc.)
for TTD targets that read files.

**Impact**: if your `@TimeTravelBody` reads from a file, replay will
read whatever the file currently contains, which may differ from the
recording run.

### Network I/O

Sockets, HTTP clients, `URLConnection`, etc.

**Rationale**: same as file I/O. Network state is external and
non-reproducible without a full network-level record/replay system.
Use mocks (WireMock, HttpStubber, etc.).

### Subprocess

`Runtime.exec`, `ProcessBuilder.start`, `Process.*`.

**Rationale**: subprocess execution is OS-level and cannot be
intercepted at the JVM bytecode level.

### Thread scheduling

Thread interleaving order, `LockSupport.park/unpark`, `synchronized`
monitor contention, `wait/notify` order.

**Rationale**: this is Limitation 1 from `design-future-phases.md`.
Requires Fray-backed `Ttd.threadedSession` (Phase 3). The current
no-Fray TTD is single-threaded by assumption.

### Environment and system properties

`System.getenv()`, `System.getProperty()`, `System.getProperties()`.

**Rationale**: these rarely change between original run and replay in
the TTD use case (same JVM, same process). Documented as an assumption:
if your body reads an environment variable that changes between recording
and replay (e.g., a variable set by the test harness), replay will
diverge silently.

### `SecureRandom`

`java.security.SecureRandom.*`.

**Rationale**: cryptographic entropy. Intercepting `SecureRandom` would
produce reproducible "random" values, defeating its security purpose.
Users who need reproducible secure random should seed with a test seed
or use a deterministic PRNG.

### `ThreadLocalRandom`

`java.util.concurrent.ThreadLocalRandom.*`.

**Rationale**: `ThreadLocalRandom` is a `Random` subclass but uses
specialised internal methods (`nextSecondarySeed`, `mix64`, etc.) that
are not part of the public `Random` API. Intercepting its public
`nextInt` etc. via the `Random.nextInt` call site would NOT work because
the static type at the call site is `ThreadLocalRandom`, not `Random`.
Full coverage would require either intercepting `ThreadLocalRandom` by
name (feasible, future work) or intercepting the `Random.next(int)`
primitive (unreliable for subclasses).

### `java.util.Random` subclasses (other than by static type)

If a call site uses a `Random` subclass as the static type (e.g.,
`MyRandom rng = ...; rng.nextInt()`), the instruction is
`INVOKEVIRTUAL MyRandom nextInt ()I`, which is NOT intercepted.
Only `INVOKEVIRTUAL java/util/Random nextXxx` call sites are rewritten.

### `java.util.UUID.randomUUID()`

Calls `SecureRandom`; not covered for the same reason as `SecureRandom`.

### Weak reference clear order

`WeakReference`, `SoftReference` clearing is driven by GC timing.
No lightweight remedy at the bytecode level.

### Native code and JNI

JNI calls return values determined by native code; no bytecode-level
interception is possible.

## `@CrochetSkip` interaction

`@CrochetSkip` opts out of **Crochet checkpoint instrumentation** (field-
access wrappers, static-field hooks). It does **NOT** opt out of TTD nondet
instrumentation. The `NondetTransformer` runs as a separate transformer
installed by `TtdAgent`; it has no knowledge of `@CrochetSkip`. A class
annotated `@CrochetSkip` still has its nondet call sites intercepted when
the TTD agent is active.

This is intentional: the two annotations are orthogonal by design.
`@CrochetSkip` is a Crochet checkpoint concern; TTD nondet recording is a
TTD concern. Decoupling them means TTD users do not need to remove
`@CrochetSkip` to get deterministic replay.

## Cold-path overhead

When neither recording nor replaying (the common case outside TTD
sessions), each rewritten call site executes:

1. A `ThreadLocal.get()` call for `RECORDING_TL` (returns null).
2. A `ThreadLocal.get()` call for `REPLAYING_TL` (returns null).
3. The original JDK method call.

Steps 1-2 add two ThreadLocal reads. Once the JIT profiles the call
site as `isRecording() == false && isReplaying() == false`, both reads
are predicted-not-taken and the net overhead is ≤ 5% on TTD-instrumented
code (per the JMH harness in `crochet-ttd/src/jmh/`).
