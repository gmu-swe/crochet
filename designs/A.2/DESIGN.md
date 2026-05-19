# Unit A.2 — `@CrochetSkip` User-Class Opt-Out

## Problem

The hardcoded `CrochetTransformer.shouldSkip` list suppresses instrumentation
for JDK-internal, Hibernate, Fray, and other framework classes where the field
injection or bytecode wrappers cause concrete failures (layout changes, duplicate
methods, verifier errors, scheduler deadlocks). That list is the authoritative
source for framework-level skip decisions.

Application authors have no equivalent mechanism. If a user class is known to
be immutable, is deliberately reset between checkpoints, or carries mutable
state that a higher-level invariant already handles, there is no way to exclude
it from instrumentation short of patching the hardcoded list — which is
inappropriate for application-level decisions and violates the skip-list hygiene
gate (gate 11: every entry must name the specific failure it prevents).

## Solution

Add `@CrochetSkip` under the existing annotation package. The transformer reads
it from the class file at transform time and suppresses instrumentation for the
annotated class and all of its subclasses.

## Design decisions

### 1. Where does the check fire?

The check fires in `CrochetTransformer.transform()` **after** the hardcoded
`shouldSkip(name)` call. This preserves the invariant that the hardcoded list
is checked first and short-circuits before any annotation I/O. The two
mechanisms are ORed: skip if either says to skip.

The check does **not** fire inside `shouldSkip(String)`. That method takes only
a name string and has no access to the class file bytes or the class loader.
Adding class-file I/O there would break every caller that uses `shouldSkip` as
a cheap name filter (e.g., the jlink pipeline and tests).

### 2. How is the annotation read?

We use an ASM `ClassReader` to scan the annotation table of the raw class file
bytes — the bytes that are already present in the caller. No `Class.forName`,
no reflection, no class loading. This is consistent with how the rest of the
transformer pipeline operates and avoids classloader deadlocks.

### 3. How is inheritance implemented?

Java's `@Inherited` meta-annotation is **not** used. `@Inherited` works on the
reflective layer and requires the annotated class to be loaded. The transformer
runs before classes are loaded, so inheritance must be implemented explicitly.

The `hasSkipAnnotation(byte[], ClassLoader)` method:
1. Checks the class file's own annotation table.
2. Reads the `superName` from the class file header.
3. Loads each ancestor's class file via the class loader's resource stream
   (same technique as `SafeClassWriter.superOfUncached`).
4. Checks each ancestor's annotation table.
5. Stops at `java/lang/Object`, at any name that `shouldSkip` already covers,
   or when the resource stream returns null (ancestor class file not resolvable).

This walk is O(depth of inheritance chain) class-file reads at transform time.
It is not cached because skip decisions are idempotent, and the class-file read
is already paid at instrumentation time. In the common case (no `@CrochetSkip`
anywhere in the chain) the walk terminates quickly at `java/lang/Object`.

### 4. API stability

`@CrochetSkip` is marked `@Stable` (unit A.4's annotation, which already exists
in the codebase as `net.jonbell.crochet.annotation.Stable`). The annotation
surface is minimal (no members) so a stability commitment is low-risk.

### 5. Scope limitation

`@CrochetSkip` is user-class opt-out only. The hardcoded list remains the
authority for:
- JDK classes that the user cannot annotate.
- Framework proxies generated at runtime (no source to annotate).
- Classes where the failure mode is a JVM-level crash (verifier, layout),
  not an application correctness issue.

This limitation is documented in the annotation's Javadoc and in this file.

## Files changed

| File | Change |
|------|--------|
| `crochet-agent/src/main/java/net/jonbell/crochet/annotation/CrochetSkip.java` | New — annotation definition |
| `crochet-agent/src/main/java/net/jonbell/crochet/transform/CrochetTransformer.java` | Added `CROCHET_SKIP_DESC`, `hasSkipAnnotation`, `loadClassBytes`, `SkipAnnotationVisitor`; hooked into `transform()` |
| `crochet-agent/src/test/java/net/jonbell/crochet/tests/SkipBean.java` | New — annotated fixture |
| `crochet-agent/src/test/java/net/jonbell/crochet/tests/SkipBeanSubclass.java` | New — unannotated subclass (depth 1) |
| `crochet-agent/src/test/java/net/jonbell/crochet/tests/SkipBeanGrandchild.java` | New — unannotated grandchild (depth 2) |
| `crochet-agent/src/test/java/net/jonbell/crochet/transform/CrochetSkipTest.java` | New — 10 tests covering all validation items |
| `designs/A.2/DESIGN.md` | This document |

## Validation checklist

- [x] `@CrochetSkip` annotated class → transformer returns null (no instrumentation)
- [x] Subclass of annotated class (depth 1) → also skipped
- [x] Grandchild of annotated class (depth 2) → also skipped
- [x] Unannotated class → instrumented normally (control)
- [x] Hardcoded skip-list entry → still suppressed before annotation check fires
- [x] `hasSkipAnnotation` returns false for class with no annotated ancestors
- [x] `shouldSkip(String)` unchanged — no entries added or removed
- [x] All 45 unit tests pass (`mvn -pl crochet-agent test`)
- [x] Integration tests pass (`mvn -pl crochet-integration-tests verify`)
- [x] All 21 demo scenarios pass (`cd demo && bash run-all.sh`)

## Invariants preserved

This change does not touch the checkpoint/rollback runtime
(`CheckpointRollbackAgent`, `FastAccessCoordinator`, the `$$crochetSnap`
layout, or the version-counter protocol). No soundness sketch for I1/I2/I3 is
required (gate 9 applies only to units that touch those systems).

The hardcoded `shouldSkip` list is unchanged. Gate 11 (skip-list hygiene) is
satisfied: no entry was added or removed; the parallel `@CrochetSkip` mechanism
is additive only.
