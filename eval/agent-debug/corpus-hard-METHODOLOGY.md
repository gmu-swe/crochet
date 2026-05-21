# Phase II Corpus Curation Methodology

## Why these projects

Phase I used Lang, Math, Time, and a sample of Closure. Post-hoc analysis showed a
ceiling effect: C1 (no debugger) solved 11/11 bugs. The corpus was too easy.

For Phase II we want bugs where **C1 fails often enough that C3 (Crochet TTD) has
room to outperform**. We target projects whose bug classes are genuinely hard for an
LLM agent operating with only source-inspection tools:

**Closure (Google Closure Compiler, ~174 bugs).** A large (130 KLOC) multi-pass
JavaScript compiler. Bugs tend to involve subtle pass-ordering interactions, dataflow
lattice semantics, and AST traversal invariants. A patch that removes one wrong
branch in a 3-class traversal is not diagnosable from a stack trace; the agent must
understand the compiler's pass pipeline.

**JacksonDatabind (~112 bugs).** JSON serialization/deserialization. Bugs involve
interactions among annotation processors, type resolvers, and serializer factories.
The failing test often produces "wrong JSON output" with no exception — requiring the
agent to trace through multiple subsystems to understand why the wrong code path was
taken.

**Jsoup (~93 bugs).** An HTML/XML parser. Parser bugs have multi-class fixes spanning
tokenizer, tree-builder, and node classes. Wrong output (malformed parse tree) for
non-trivial HTML5 edge cases requires understanding the HTML5 parsing spec and how
Jsoup's tree-builder state machine implements it.

**Excluded: Chart.** Requires SVN which is not installed on this machine. D4J
maintains Chart's history via SVN; `defects4j checkout` fails.

**Excluded: Mockito.** Uses Gradle 4.9 whose Groovy DSL triggers
`ExceptionInInitializerError` on JDK 21 (Groovy 2.x's `MetaClassImpl` reflectively
accesses `sun.reflect.*` APIs that were removed in JDK 9+). None of the 38 Mockito
bugs compile under JDK 21 without patching Gradle itself.

**Excluded: Lang/Math/Time.** Phase I showed all 11 of these bugs passed under C1.
They are straightforward single-class fixes with direct exception messages; C1 can
diagnose them from the stack trace alone.

## How "hard" was operationalized

**Pre-screen criterion:** `c1_success_rate <= 0.5` — C1 passes at most 1 of 2
independent seeds. This directly measures whether the agent without debugging tools
can solve the bug; any bug where C1 reliably succeeds is unlikely to show a C3
benefit.

**Selection filters applied before pre-screen:**

1. **Multi-class canonical fix** (≥ 2 files changed in the D4J patch): preferred,
   since bugs requiring coordinated changes across multiple classes are harder to
   localize without a runtime oracle.

2. **Patch size ≥ 6 changed lines**: eliminated trivial single-line typo fixes where
   the test failure message is sufficient to identify the exact location.

3. **Non-NPE symptom**: preferred bugs whose failing test shows wrong output,
   wrong computed value, or wrong structural result rather than "NullPointerException
   at method.foo()" — the latter gives the fix location immediately.

4. **JDK 21 compatibility verified**: every candidate was dry-run (checkout →
   compile → verify bug reproduces) under JDK 21 Temurin before being included.

## What was excluded and why

From the initial survey:

- `JacksonDatabind-65`: deprecated in D4J 3.0 as `JVM11.flaky` — known to produce
  non-deterministic failures under JDK 11+.
- Closure bugs with only `lib/rhino` compile issues that could not be fixed by
  source/target bumping alone were investigated case-by-case; none were excluded on
  compile grounds after the rhino build-properties fix was applied.
- Single-class patches with ≤ 5 changed lines were excluded (e.g., Closure-62,
  Closure-72, Closure-79) — they are too localized.

## Selection bias acknowledgment

The pre-screen filtering introduces a deliberate selection bias toward bugs C1 fails
on. This is the intent: Phase II's null hypothesis is that TTD offers no advantage
over the baseline C1 condition, so the corpus must include bugs where C1 is
challenged. However, this bias should be noted when interpreting Phase II results:

- The corpus is **not** a random sample of all D4J bugs. It is a sample from the
  tail of the C1 difficulty distribution.
- Effect sizes measured in Phase II (C3 success rate vs. C1 success rate) will be
  **upward-biased relative to an unselected corpus** because we filtered out bugs
  where C1 is trivially effective.
- The valid inference is: "for bugs in this difficulty range, does TTD help?" not
  "across all D4J bugs, does TTD help?"

A complementary Phase III (unfiltered random sample with larger N) would be needed
to estimate the average treatment effect across the full difficulty distribution.

## Seed policy

Two C1 seeds were used for pre-screening. The `claude` CLI does not expose a numeric
random seed, but `--session-id` prevents the CLI from reusing cached session state.
Seeds 1 and 2 were used, producing session IDs `<bug>-C1-seed1` and `<bug>-C1-seed2`
respectively. Each trial uses a fresh checkout, compile, and agent invocation; the
seed variation captures LLM stochasticity (different random token sequences at the
same temperature).
