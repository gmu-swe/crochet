# B.5 Stack-as-Data Bolt-On — Design

## Purpose

Expose the `FRAME_DEQUE` chain (introduced by B.2) as a human-readable
stack snapshot via `Ttd.captureStack()`.  This drops WISHLIST 1.1's
originally-planned native-JVMTI path entirely: the ResumeFrame chain *is*
the stack-as-data; we just need to package and surface it.

Deliverables (all ~100 LOC):
- `StackEntry` record — one entry per `ResumeFrame` in the active thread's deque.
- `LocalSnapshot` record — one local per slot in a frame, with name, type, value.
- `Ttd.captureStack()` — snapshot copy of the deque, innermost frame first.
- `Ttd.registerMethodLine(int methodId, int bci, String label)` — populate the
  `(methodId, bci) → "ClassName.method:line"` debug table.
- `StackCapture.serialize(List<StackEntry>)` / `StackEntry.toJson()` — JSON
  with versioned schema.
- Tests covering the validation matrix.

## Debug-table storage: (methodId, bci) → label

PLAN.md says "method-id → ClassName.method:line" but there are multiple save
points (distinct bcis) within a single method, each mapping to a different
source line.  The correct key is `(methodId, bci)`, not `methodId` alone.

Storage: `ConcurrentHashMap<Long, String>` keyed by `(methodId << 32) | bci`
packed into a `long`.  This is compact, lock-free, and avoids boxing a
`(int, int)` tuple.

## Design choice: intern-time registration vs. transform-time emit

Two options considered:

**Option A — transform-time emit**: B.3 emits a `<clinit>` helper that calls
`Ttd.registerMethodLine(id, bci, label)` for each save point in the method.
The helper fires once at class-load time.

**Option B — intern-time argument**: Change `internMethodId(String key)` to
`internMethodId(String key, int[] bcis, String[] labels)` so the whole table
for a method is registered in one call.

**Decision: Option A (transform-time emit, separate `registerMethodLine` call).**

Rationale:
- B.3 already emits per-save-point bytecode; adding a `registerMethodLine`
  call at class-init time is a natural extension with no extra visitor state.
- Option B changes the signature of `internMethodId`, which is already in B.2's
  public API and would be a breaking change to any B.3 draft that calls the
  current form.
- Option A keeps concerns separate: `internMethodId` remains purely about
  assigning a stable integer identity; `registerMethodLine` is about debug
  metadata.  B.3 can call both at transform time.

In B.5 itself (before B.3 lands), we test `registerMethodLine` directly from
test code simulating what B.3 would emit.  This makes B.5 a clean, testable
runtime API layer.

## LocalVariableTable handling

When a `ResumeFrame` carries `prims` or `refs` arrays, we map slot index to
local name and type descriptor using the LVT from the class file.  The LVT is
available at transform time via `MethodNode.localVariables`.

B.5's `captureStack()` does not read the class file at capture time (that would
be expensive and require a classloader reference).  Instead, B.3 will emit calls
to `Ttd.registerLocalInfo(int methodId, int bci, int primSlot|refSlot, String name, String descriptor)`
for each live local at each save point — but that is B.3's deliverable.

For B.5's test coverage (before B.3), we exercise the fallback path:
- When no local info is registered for a slot, `captureStack()` uses `"$slotN"`
  as the name and `"?"` as the type descriptor.  This covers the `-g:none` case.
- The positive test registers info manually via a to-be-added
  `Ttd.registerLocalInfo(...)` call and verifies the names appear in output.

### Decision on registerLocalInfo granularity

Rather than a separate per-slot registration method, we fold local-name info
into the debug table alongside the label.  The debug entry holds:
- `label` — the `"ClassName.method:line"` string
- `primNames` / `primDescs` — parallel `String[]` indexed by prim slot
- `refNames` / `refDescs` — parallel `String[]` indexed by ref slot

This is stored in a `MethodLineInfo` helper class (package-private, same
file as the table).  The registration API:

```java
Ttd.registerMethodLine(int methodId, int bci, String label,
                       String[] primNames, String[] primDescs,
                       String[] refNames,  String[] refDescs)
```

For B.5's own tests we use a simpler overload that omits the local arrays
(sets them all to null, triggering the `$slotN` fallback).

## captureStack() semantics

```java
public static List<StackEntry> captureStack()
```

- Returns a snapshot (copy) of the current thread's `FRAME_DEQUE` as a
  `List<StackEntry>`, innermost frame first (i.e., the deque head is index 0).
- If `TTD_ACTIVE_SESSIONS == 0` (no session active), returns an empty list.
- The returned list is decoupled from the deque: subsequent `saveFrame` /
  `popResumeFrame` calls do not affect the snapshot, and the caller can mutate
  the list freely.
- For each `ResumeFrame` in the deque, we look up the `(methodId, bci)` pair
  in the debug table.  If present, the `StackEntry.classMethodLine` field is
  set to the registered label (e.g., `"com/example/Foo.doWork(I)V:42"`).
  If absent, the sentinel `"<methodId=N bci=M>"` is used.
- Locals are built from the frame's `prims` and `refs` arrays plus the
  registered slot info (or the `$slotN` / `?` fallback).

## Serialization schema

Format: hand-rolled JSON with `{"schemaVersion": 1, ...}` wrapper.
No external dependency required; the existing pom.xml has no JSON library.

Schema version 1:
```json
{
  "schemaVersion": 1,
  "frames": [
    {
      "classMethodLine": "com/example/Foo.doWork(I)V:42",
      "locals": [
        {"name": "x", "descriptor": "I", "value": "42"},
        {"name": "obj", "descriptor": "Ljava/lang/String;", "value": "\"hello\""},
        {"name": "$slot2", "descriptor": "?", "value": "null"}
      ]
    }
  ]
}
```

Rules:
- `value` is always a JSON string.  Primitives are stringified by
  `Long.toString` (for prims array entries).  References use
  `String.valueOf(obj)` (i.e., `obj.toString()` or `"null"`).
- The `frames` array is ordered innermost-first (matches `captureStack()` order).
- Schema version is `1`.  If the schema changes, the version increments.
- Serialization is deterministic given a fixed `captureStack()` result:
  same frame chain → byte-identical JSON string.

## Wiring into Ttd

All new fields / methods are added to the existing `Ttd.java`:
- `METHOD_LINE_TABLE: ConcurrentHashMap<Long, MethodLineInfo>` — the debug table.
- `registerMethodLine(...)` — public static, called by B.3 at class-load time.
- `captureStack()` — public static, user-facing.
- `StackEntry`, `LocalSnapshot` — inner records of `Ttd` (or top-level files
  in the same package).

`StackEntry` and `LocalSnapshot` are in the same package but in separate
source files to keep `Ttd.java` under 400 LOC and match the project's
one-class-per-file style.

## What surprised us about B.2's Ttd surface

- `clearSessionState()` is `private` — B.5 cannot call it directly.  That is
  correct: the session lifecycle is owned by B.2; B.5 only reads the deque.
- `FRAME_DEQUE` is `private` — `captureStack()` must be implemented inside
  `Ttd.java` (not a separate utility class) since it needs direct access to
  the private field.  We add `captureStack()` as a method on `Ttd` rather than
  a helper.
- The deque's LIFO order (`ArrayDeque.push` = addFirst; `peek()` = peekFirst).
  `captureStack()` must iterate in deque iteration order (which for ArrayDeque
  is addFirst → head first), so the resulting list is correctly innermost-first.
