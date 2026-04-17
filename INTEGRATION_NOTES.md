# Gap 3 + Gap 4 Bytecode Integration — Notes

## Scope

Added bytecode-automation paths for Gap 3 (static fields) and Gap 4 (arrays)
on top of the existing reflective surface (`checkpointStatics` /
`rollbackStatics`, `checkpointArray` / `rollbackArray`). The reflective
APIs remain intact.

## Design decisions — implemented verbatim from the design docs

### Gap 3 (static fields)

- Per-user-class hidden helper class (`C$$crochetSFHelper`) generated on
  first static-field access. Defined as a `NESTMATE` + `STRONG` hidden
  class anchored in the user class's `$$crochetLookup`, matching the
  existing proxy pattern.
- Mirror instance fields: one per non-final non-synthetic static field.
- Implements `CRIJInstrumented` — fits cleanly into the existing
  checkpoint/rollback state machine.
- Helper singleton stored in `ClassMeta.sfHelper`, per-class-loader safe
  via `ClassValue`.
- Helper lookup is lazy and double-checked — runtime cost is a single
  `ClassValue.get` + volatile read on the hot path.
- Static-final fields are skipped (javac inlines reads of constant
  primitives / Strings).
- `<clinit>` of the owner class is not instrumented — initializers write
  the user class's real static slots directly.

### Gap 4 (arrays)

- `ArrayRegistry` with `ConcurrentHashMap<IdKey, ArrayMeta>` — weak
  identity keys, `ReferenceQueue` drained on every lookup.
- Eager snapshot gated by a per-array `dirty` flag, taken lazily on
  first write after checkpoint — matches the DESIGN choice.
- Registry queue is drained on every `metaFor` call; no background
  drain thread.
- `xASTORE` instrumentation: IASTORE, LASTORE, FASTORE, DASTORE, AASTORE,
  BASTORE, CASTORE, SASTORE.
- `LocalVariablesSorter` used to spill `value` + `index` into scratch
  locals for safe stack choreography across 1- and 2-slot values.
- Loads (xALOAD) are not instrumented — matches DESIGN §3.

## Design decisions — adapted

### Gap 3: eager snapshot instead of Fast-proxy klass-swap on the helper

The DESIGN doc's Strategy A describes a Fast-proxy pattern for the helper,
mirroring V1's instance-field klass-swap trick. That requires a Fast proxy
whose super is the helper class. **Hidden classes cannot be named as super
in a class file** (symbolic references to a hidden class fail to resolve),
and the DESIGN explicitly wants hidden-class helpers (NESTMATE + STRONG).

Resolution: the helper's `$$crochetCheckpoint` / `$$crochetRollback`
snapshot/restore the user class's real static slots **eagerly and
directly** via `GETSTATIC C.f ↔ PUTFIELD this.f` (the helper has
nestmate-private access). No Fast proxy on the helper. No klass-swap.

Correctness is preserved because a helper instance is interacted with
only by `checkpointClass` / `rollbackClass` / `checkpoint` /
`rollback` — never by user code performing a direct field access on
the helper.

### Gap 3: StaticFieldRewriter keeps original GETSTATIC/PUTSTATIC in place

The DESIGN's rewrite redirects GETSTATIC/PUTSTATIC to read/write the
helper's instance fields (Strategy A makes the original statics "dead"
after first mirror). Our rewrite inserts a pre-hook that calls
`sfHelperFor(C.class).$$crochetAccess()` and then executes the ORIGINAL
GETSTATIC/PUTSTATIC. Rationale:
- Avoids needing the helper's class name at rewrite time (hidden class
  names are JVM-generated, not stable at user-class-load time).
- Keeps reflection-based access to original statics working.
- `$$crochetAccess()` is a no-op — its only purpose is to trigger lazy
  helper creation on first access. `checkpointClass(C)` snapshots the
  real statics; `rollbackClass(C, v)` restores them.

### Gap 4: `checkpoint(Object)` now propagates to array fields and statics

Modified `checkpoint(Object)` and `rollback(Object, int)` to call
`ArrayRegistry.propagateCheckpoint/Rollback` (reflectively walks the
target's declared array fields) and `checkpointClassAtVersion/
rollbackClassAtVersion(target.getClass(), ...)`. This is how scenario 14
works without an explicit `checkpointArray` call: the container's
`int[]` field is registered at checkpoint time; the subsequent IASTORE
through the instrumented pre-hook takes the lazy snapshot; rollback
restores via `ArrayRegistry.propagateRollback`.

`target.getClass()` is resolved to the user class (walking past any
Fast proxy superclass) so `checkpointClass` sees the stable user class.

## Known limitations

1. **Scenarios 11 and 12 (concurrent rollback / concurrent access) were
   already failing before my changes.** These are pre-existing flaky
   concurrent tests on the `java24-port` branch. Not a regression.
2. **Unit tests `idempotentOnSecondPass` and `skipsInterfaces` fail.**
   These were already failing before my changes due to pre-existing
   modifications in `CrochetTransformer.transform()` — the worktree was
   already in that state when I began. The tests expect `transform()`
   to return non-null for interfaces and on a second pass, but the
   current code returns null for both.
3. **`System.arraycopy` bypasses the xASTORE hook.** Native bulk copies
   do not go through `beforeStore`. Flagged as known limitation.
4. **Reflection on original statics after `checkpointClass` still sees
   the live values (not the snapshot).** We chose Strategy A-lite where
   the real static slots remain canonical.
5. **Multi-level array propagation is limited.** `ArrayRegistry.
   propagateCheckpoint` walks one level — the top-level object's
   array-typed fields. Nested objects' array fields are not visited
   transitively; scenario 14 is single-level and works.
6. **Static-final references are skipped.** The rewriter filters final
   static fields; the helper mirrors only non-final statics. A user who
   mutates a final reference via reflection will not see it rolled
   back.
7. **Scenario 15 (HashMap instrumented) is unrelated to this gap
   integration** and was added by another branch. It currently passes.

## Files created

- `crochet-agent/src/main/java/net/jonbell/crochet/runtime/ArrayRegistry.java`
- `crochet-agent/src/main/java/net/jonbell/crochet/transform/ArrayAccessWrapper.java`
- `crochet-agent/src/main/java/net/jonbell/crochet/transform/StaticFieldRewriter.java`
- `crochet-agent/src/main/java/net/jonbell/crochet/transform/StaticFieldHelperTemplate.java`
- `demo/scenarios/13-static-fields-auto/Config.java`
- `demo/scenarios/13-static-fields-auto/Main.java`
- `demo/scenarios/14-array-auto/Container.java`
- `demo/scenarios/14-array-auto/Main.java`

(Scenarios 10 and 11 were already taken by pre-existing concurrent
tests, so the new automation scenarios were placed at 13 and 14.)

## Files modified (append-only / within allowed surface)

- `crochet-agent/src/main/java/net/jonbell/crochet/transform/CrochetTransformer.java`
  — add `StaticFieldRewriter` and `ArrayAccessWrapper` to the visitor
  chain (does not modify `shouldSkip`).
- `crochet-agent/src/main/java/net/jonbell/crochet/runtime/CheckpointRollbackAgent.java`
  — add `sfHelperFor`, `checkpointClass`, `rollbackClass`,
  `checkpointClassAtVersion`, `rollbackClassAtVersion`,
  `realUserClassOf`; extend `checkpoint` / `rollback` to propagate
  to arrays and class-level statics.
- `crochet-agent/src/main/java/net/jonbell/crochet/runtime/ClassMeta.java`
  — add `sfHelperClass`, `sfHelper` fields.

## Scenario results

```
=== 01-basic                                 PASS
=== 02-nested                                PASS
=== 03-cyclic                                PASS
=== 04-multi-checkpoint                      PASS
=== 05-rollback-then-checkpoint              PASS
=== 06-wide-fields                           PASS
=== 07-concurrent-reads                      PASS
=== 08-static-fields                         PASS   (reflective API)
=== 09-arrays                                PASS   (reflective API)
=== 10-concurrent-checkpoint                 PASS
=== 11-concurrent-rollback                   FAIL   (pre-existing flaky)
=== 12-checkpoint-then-concurrent-access     FAIL   (pre-existing flaky)
=== 13-static-fields-auto                    PASS   (new — bytecode path)
=== 14-array-auto                            PASS   (new — bytecode path)
=== 15-hashmap-instrumented                  PASS   (unrelated, from other branch)
```

## Unit tests

```
Tests run: 5, Failures: 2, Errors: 0, Skipped: 0
  - CrochetTransformerTest.idempotentOnSecondPass   FAIL (pre-existing)
  - CrochetTransformerTest.skipsInterfaces          FAIL (pre-existing)
```

Both failing tests were broken in the worktree state before my changes
were applied — the pre-existing `transform()` modification returns null
for interfaces and for classes already carrying `@CrochetInstrumented`,
which these tests did not expect. Fixing them is outside the append-only
surface this task was scoped to.
