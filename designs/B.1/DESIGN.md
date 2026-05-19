# B.1 Liveness Analyzer — Design

Unit B.1 of the Crochet TTD plan. Produces a map from save-point BCI
→ `[(slotIndex, Type)]` for each `@TimeTravelBody`-annotated method.
B.3 consumes this to emit only live locals into ResumeFrame, avoiding
max-sized allocations.

## Motivation

The alternative — saving all declared locals at every save point —
would force every save point to allocate a max-sized `long[]` and
`Object[]` (sized to the method's max locals), regardless of how many
variables are actually live at that BCI. For methods with large
LocalVariableTable entries but short live ranges, this wastes alloc
budget and defeats the zero-alloc-when-no-session goal (universal gate
7). By computing liveness precisely, B.3 can emit save-frame snippets
that only pack/unpack the minimal live set at each save point.

## Algorithm

### Forward typed analysis via ASM `Analyzer<BasicValue>`

We use ASM's `Analyzer<BasicValue>` with `BasicInterpreter` (or
equivalently, `SimpleVerifier` if we need type fidelity). This gives
us, at each instruction index, the type and size of every local
variable slot that holds a known value.

**Why forward typed + post-process rather than backward DFA:**

1. ASM's `Analyzer` computes typed frames bottom-up through the CFG,
   handling exception edges, jsr/ret, and uninitialized values
   automatically. Rolling our own backward DFA would duplicate that
   CFG-construction work.

2. The key insight: "live at BCI" = "the frame at BCI contains a
   non-TOP BasicValue in that slot". ASM's `BasicValue.UNINITIALIZED_VALUE`
   (TOP) signals a slot with no current value. For liveness we simply
   walk each frame at each save-point BCI and collect all non-TOP
   slots.

3. This is sound for our use case because:
   - We emit save-frame snippets that capture the *current* value of
     each local at the save-point. The frame at that instruction tells
     us exactly which slots have defined, typed values.
   - Forward typed analysis conservatively over-approximates at
     join points: if a local is live on *any* predecessor branch, it
     will be non-TOP at the join. This is the correct behavior —
     B.3 must save a local if it might be needed on *any* path.

4. Try/catch handling is automatic: ASM's Analyzer propagates frames
   through exception edges. A local that is live inside the handler
   will be non-TOP at the throwing instruction (because the frame
   at an exception handler is the merge of all throwing-instruction
   frames under exception-edge semantics). This satisfies the
   try/catch requirement in the plan.

### Two-slot type handling (long, double)

ASM represents `long` and `double` as occupying two slots:
- Slot N: `BasicValue` with `type.getSize() == 2`
- Slot N+1: `BasicValue.UNINITIALIZED_VALUE` (TOP placeholder)

Our analysis:
- When we encounter a slot N with a size-2 type, we emit one
  `LiveLocal(N, LONG_TYPE)` or `LiveLocal(N, DOUBLE_TYPE)`.
- We explicitly skip slot N+1 because it is the TOP placeholder.
  The check is: if the frame at slot N has a size-2 type, skip N+1.

This means the liveness map never contains `(N+1, ...)` as a separate
entry after a size-2 local at N. B.3 uses `type.getSize()` to
allocate the right number of `long[]` entries when packing.

### Uninitialized-this rejection

A save point inside a `<init>` method before the `super()` / `this()`
call must be rejected: the JVM verifier will not accept resuming into
a frame where `this` is uninitialized.

**Key finding:** Neither `BasicInterpreter` nor `SimpleVerifier` in
ASM 9.9 propagates an "uninitialized-this" type distinctly — both
map slot 0 in an `<init>` to the class type (`Ljava/lang/Object;` or
`Lcom/example/Foo;`) from the very first frame, even before the
`INVOKESPECIAL <init>` call. Frame-based detection is insufficient.

**Bytecode scan approach:** Scan the method's instruction list for the
first `INVOKESPECIAL <init>` call (which is the `super()` or `this()`
delegate). Any save-point BCI strictly less than that instruction index
is rejected. This is correct because:
- The first `INVOKESPECIAL <init>` in any `<init>` method is always
  the super/this delegate call (Java language guarantees this).
- All BCIs before it are in the "uninitialized-this" region.

**Test fixture:** A synthetic `<init>` MethodNode with:
```
ALOAD 0         // BCI 0: load (uninitialized) this
NOP             // BCI 1: save point — BEFORE super()
INVOKESPECIAL Object.<init> // BCI 2: super() call
RETURN
```
`findSuperCallBci()` returns 2. Save point at BCI 1 < 2 → throws
`IllegalStateException`. ✓

### Save-point BCI set: fully caller-defined

The `analyze()` method takes a `Set<Integer>` of save-point BCIs.
The caller (B.3) supplies these. The analyzer is agnostic about what
constitutes a save point. For line markers, the caller enumerates
`LineNumberNode` BCIs; for callsites, it adds `MethodInsnNode` BCIs.

### Output ordering

The returned `List<LiveLocal>` per BCI is sorted by slot index,
ascending, for determinism (universal gate 18).

## Test fixtures

1. **2-slot locals.** A method with `long x = ...; double y = ...;`
   at a save point. Assert that the liveness map has exactly two
   entries: `(N, LONG_TYPE)` and `(M, DOUBLE_TYPE)` with no N+1 or M+1.

2. **Branch joins.** `if (cond) { x = 1; } // save point`. At the
   save point after the if, `x` is live (might be 1 or whatever the
   default was). Assert it appears in the live set.

3. **Try/catch.** A local defined before a try block and used in the
   catch handler. Assert it is live at the INVOKEVIRTUAL inside the try.

4. **Uninitialized-this rejection.** A synthetic `<init>` MethodNode
   with a save point before `INVOKESPECIAL Object.<init>`. Assert that
   `analyze()` throws `IllegalStateException` with the method's FQN.

5. **Empty method (just RETURN).** No locals. Save point at BCI 0.
   Assert empty live set.

6. **No save points.** A method with locals, empty save-point set.
   Assert empty map.

## Performance budget

Measured via the `LivenessBenchmark` main() harness over `java.lang.String`
from the JDK 21 corpus (167 concrete methods, all BCIs as save points):

```
min:      3.48 ms
median:   4.74 ms
mean:     5.67 ms
max:     18.53 ms
```

Budget = `median × 1.5 ≈ 7 ms`, rounded up to **10 ms** to absorb GC
variance in CI. Set as `LivenessBenchmark.PER_CLASS_BUDGET_MS = 10`.

The analyzer runs `Analyzer<BasicValue>` once per method (forward
data-flow, O(instructions × locals)). For typical methods (< 200
instructions, < 30 locals), this is well within the budget.

## Fuzz corpus

Source: JDK 21 base image extracted via:
```
jimage extract --dir /tmp/jdk-corpus \
    /usr/lib/jvm/java-21-openjdk-amd64/lib/modules
```

Every `.class` file in `/tmp/jdk-corpus` is analyzed. For each method,
the save-point set is ALL instruction BCIs (max stress). The per-method
liveness map is serialized as a sorted string, hashed with SHA-256.
The per-class hashes are sorted and combined into a corpus-level hash.
The corpus-level SHA-256 prefix is committed here:

```
CORPUS_HASH=cd17554cb5595739b08352bd7778fe0dd5cd5aecc331fe565752b422e25828c3
```

Corpus: 27,834 class files from the JDK 21 Temurin base image.
Extracted with:
```
/usr/lib/jvm/java-21-openjdk-amd64/bin/jimage extract \
    --dir /tmp/jdk-corpus \
    /usr/lib/jvm/java-21-openjdk-amd64/lib/modules
```

## File layout

```
crochet-ttd/src/main/java/edu/neu/ccs/prl/crochet/ttd/cps/
    LivenessAnalyzer.java      -- main analyzer
crochet-ttd/src/test/java/edu/neu/ccs/prl/crochet/ttd/cps/
    LivenessAnalyzerTest.java  -- unit tests
    CorpusLivenessTest.java    -- fuzz corpus driver
crochet-ttd/src/jmh/java/edu/neu/ccs/prl/crochet/ttd/jmh/liveness/
    LivenessBenchmark.java     -- JMH harness
designs/B.1/DESIGN.md         -- this file
```
