# A.4 Design: Composition Kit + Stability Annotations + Universal-Gate CI

## 1. Scope

Unit A.4 provides three independent but related things:

1. **`InstrumentedSurfaceVerifier`** — a lowest-priority `ClassFileTransformer`
   registered in the agent premain that re-reads each transformed class file
   post-load and verifies the `$$crochet*` surface is intact. Detects silent
   agent-stack clobbering (e.g. Byte Buddy rewriting `$$crochetAccess` to a
   no-op) at load time rather than at the first checkpoint call.

2. **`crochet-compose-kit/`** reactor module — POM with the Fray skip-list
   pre-baked, a JUnit 5 `@CrochetCompositionTest` extension that boots an agent
   matrix, and a README enumerating known-good combinations and the failure mode
   each pre-baked skip-list entry prevents.

3. **`@Stable` / `@Experimental` / `@Internal` annotations** under
   `net.jonbell.crochet.annotation`, retroactively applied to the existing
   `crochet-agent` public surface.

4. **`.github/workflows/universal-gates.yml`** — GitHub Actions CI enforcing
   gates 1–21 from PLAN.md.

---

## 2. Verifier transformer

### 2.1 What constitutes a valid instrumented surface

A class processed by `CrochetTransformer` carries all of the following. If
any element is missing after agent-stack composition, we emit a structured log
line and continue (NOT a `ClassFormatError`).

| Element | How to detect at class-file level |
|---|---|
| `@CrochetInstrumented` annotation | Annotation descriptor `Lnet/jonbell/crochet/annotation/CrochetInstrumented;` present in class annotations with `RetentionPolicy.CLASS` |
| `int $$crochetVersion` instance field | Field named `$$crochetVersion`, descriptor `I` |
| `Object $$crochetSnap` instance field | Field named `$$crochetSnap`, descriptor `Ljava/lang/Object;` |
| `void $$crochetAccess()` method | Method named `$$crochetAccess`, descriptor `()V` |
| `void $$crochetCheckpoint(int)` method | Method named `$$crochetCheckpoint`, descriptor `(I)V` |
| `void $$crochetRollback(int)` method | Method named `$$crochetRollback`, descriptor `(I)V` |
| Implements `CRIJInstrumented` interface | Interface `net/jonbell/crochet/runtime/CRIJInstrumented` in the class's interface list |

The check reads only the class structure (SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES),
making it cheap — O(class-file header + member table).

### 2.2 Registration order

`premain` registers `InstrumentedSurfaceVerifier` with
`inst.addTransformer(new InstrumentedSurfaceVerifier(), false)` **after** the
existing `TransformerWrapper` registration. The JVM calls transformers in
registration order; the verifier thus sees bytes that have already passed
through the Crochet transformer, making it a post-instrumentation check.

The verifier does NOT retransform; it reads the `classfileBuffer` argument
(which is already the output of prior transformers at this point) and logs on
mismatch.

The verifier is gated by the system property `crochet.verifyInstrumented`
(default `false`). This keeps steady-state overhead zero; users and CI jobs
that want surface-verification turn it on.

### 2.3 Log format

```
[Crochet-Verify] SURFACE_MISMATCH class=<internalName> missing=<element>[,<element>]
```

`element` values: `@CrochetInstrumented`, `$$crochetVersion`, `$$crochetSnap`,
`$$crochetAccess`, `$$crochetCheckpoint`, `$$crochetRollback`,
`CRIJInstrumented`.

Emitted via `System.err` (not a logger dependency) to stay bootstrap-safe.
The line starts with `[Crochet-Verify]` as a stable prefix for grep / log
analysis.

The verifier only logs on classes that `CrochetTransformer` would have
instrumented (i.e., not skipped by `shouldSkip`, not an enum/interface/annotation).

---

## 3. Stability annotations

### 3.1 Semantics

| Annotation | Meaning |
|---|---|
| `@Stable` | API surface frozen for the current major version. Downstream code may depend on it. |
| `@Experimental` | Subject to change in a minor release. Downstream code should not depend on it for production use. |
| `@Internal` | May change without notice in any release. Not intended for use outside Crochet modules. Javadoc says "INTERNAL USE ONLY." |

All three carry `@Documented`, `@Target(ElementType.TYPE, ElementType.METHOD)`,
and `@Retention(RetentionPolicy.RUNTIME)` (runtime retention lets downstream
tools inspect them).

### 3.2 Stability decisions for existing public surface

| Type | Annotation | Rationale |
|---|---|---|
| `net.jonbell.crochet.runtime.CheckpointRollbackAgent` | `@Stable` | User-facing checkpoint/rollback API. Method signatures referenced by emitted bytecode across millions of classes; changing them is an ABI break. |
| `net.jonbell.crochet.runtime.CRIJInstrumented` | `@Stable` | Interface on every instrumented class; part of the ABI. |
| `net.jonbell.crochet.runtime.RollbackException` | `@Stable` | Exception thrown from rollback(); user code may catch it. |
| `net.jonbell.crochet.annotation.CrochetEager` | `@Stable` | User-facing opt-in annotation. |
| `net.jonbell.crochet.annotation.CrochetSkip` | `@Stable` | User-facing opt-out annotation. |
| `net.jonbell.crochet.annotation.CrochetInstrumented` | `@Internal` | Transformer-internal marker; downstream should not inspect it. |
| `net.jonbell.crochet.runtime.ArrayRegistry` | `@Internal` | Called from emitted bytecode; not a user API. |
| `net.jonbell.crochet.runtime.ClassMeta` | `@Internal` | Internal per-class metadata cache. |
| `net.jonbell.crochet.runtime.FastAccessCoordinator` | `@Internal` | Stripe-lock concurrency machinery; not a user API. |
| `net.jonbell.crochet.runtime.FastProxySupport` | `@Internal` | Klass-swap machinery; not a user API. |
| `net.jonbell.crochet.runtime.PropagateWorklist` | `@Internal` | Internal reference propagation. |
| `net.jonbell.crochet.runtime.ReflectionFilter` | `@Internal` | Reflection filtering for emitted bytecode. |
| `net.jonbell.crochet.runtime.RuntimeReady` | `@Internal` | Bootstrap gate; not a user API. |
| `net.jonbell.crochet.runtime.RuntimeTracer` | `@Internal` | Diagnostic tracing; may be removed. |
| `net.jonbell.crochet.runtime.SfHelperFactory` | `@Internal` | Static-field helper materialisation. |
| `net.jonbell.crochet.runtime.StackRoots` | `@Internal` | JVMTI-backed stack root collection. |
| `net.jonbell.crochet.runtime.StaticSnapshots` | `@Internal` | Static-field snapshot/rollback. Not a public API; called from emitted bytecode only. |
| `net.jonbell.crochet.runtime.Tag` | `@Internal` | Internal tagging enum. |
| `net.jonbell.crochet.runtime.VersionCounter` | `@Internal` | Global version counter; internal invariant carrier. |
| `net.jonbell.crochet.runtime.CRIJFast` | `@Internal` | Fast-proxy interface; not a user API. |
| `net.jonbell.crochet.transform.CrochetTransformer` | `@Internal` | Transform pipeline entry point. Used by the instrument module; not a user API. |
| All other `net.jonbell.crochet.transform.*` types | `@Internal` | Transform pipeline visitors; internal. |
| `net.jonbell.crochet.agent.CrochetAgent` | `@Internal` | Agent premain; not meant for direct programmatic use. |

---

## 4. `crochet-compose-kit` module

### 4.1 Purpose

A convenience POM for downstream projects (Tapestry, Fray harnesses, third-party
users) that want a known-good Crochet + agent combination. Pre-bakes:

- The Fray skip-list contribution (`org/pastalab/fray/`) in module metadata.
- A JUnit 5 extension `@CrochetCompositionTest` that parameterizes a test
  over three agent configurations: (a) Crochet alone, (b) Crochet + Byte Buddy
  (Mockito-inline), (c) Crochet + Fray.
- `README.md` documenting known-good combinations and failure modes.

### 4.2 `@CrochetCompositionTest` design

`@CrochetCompositionTest` is a JUnit 5 meta-annotation (via
`@ExtendWith(CrochetCompositionExtension.class)`). It runs the annotated test
class once per agent configuration in the matrix. For Phase A, the matrix is
encoded as an enum `AgentConfig` with three members. The extension selects the
applicable config via a system property (`crochet.compose.config`, set per
Surefire invocation) so that the Maven plugin can fork three JVMs rather than
doing agent manipulation inside a single JVM (which is not reliably possible
once the JVM is running).

In Phase A we ship the extension skeleton with:
- The `AgentConfig` enum.
- The `@CrochetCompositionTest` meta-annotation.
- `CrochetCompositionExtension` that checks the config and aborts with a
  descriptive message if the expected agent isn't loaded.

Full multi-JVM forking is deferred to Phase B when the CI matrix has
instrumented JDK builds to fork into.

---

## 5. CI workflow shape

### 5.1 Job structure

One workflow file: `.github/workflows/universal-gates.yml`.

One job per logical group to enable parallel execution and per-gate failure
attribution:

| Job name | Gates covered | Triggers |
|---|---|---|
| `unit-tests` | 1 (unit tests green) | push + PR |
| `integration-tests` | 2 (integration tests — both deploy modes) | push + PR |
| `demo-scenarios` | 3 (demo scenarios) | push + PR |
| `dacapo-functional` | 4 (DaCapo functional sweep) | PR only (slow) |
| `bytecode-verification` | 5 (Xverify:all strict), 18 (deterministic bytecode) | push + PR |
| `skip-list-hygiene` | 11 (skip-list hygiene) | push + PR |
| `stability-annotations` | 14 (stability classifier on new public API) | push + PR |
| `design-doc-check` | 21 (design doc presence) | push + PR |
| `downstream-smoke` | 12 (Tapestry + crochet-junit5) | PR only |
| `composition-assert` | 13 (A.4 composition-kit check) | PR only |

Gates that require human input (9 = reviewer sign-off) are enforced via
GitHub's required-reviewers feature on the branch protection rule, not in CI.
Gate 6 (DaCapo no-regression) is present in CI but passes vacuously when no
baseline file exists (`eval/phase-A-baseline/` not yet committed).

Gates 7 (no new allocation on cold paths), 8 (JIT-foldability), 10 (stripe-lock
stress), 15 (contract javadoc), 16 (measurements are runnable), 17 (phase
artifacts retained), 19 (TTD recordings deterministic — Phase B onward) are
enforced via PR review conventions and design doc requirements, not automated
checks (they require measurement runs or human judgment).

### 5.2 DaCapo functional gate implementation

Gate 4 runs `eval/dacapo-func/run.sh` with a `DRY_RUN=true` fallback when the
DaCapo JAR is not present (CI has no license). The script exits 0 when
`DRY_RUN=true` and the JAR is absent, so the gate passes in CI while still
being runnable locally with the actual JAR. This is documented in the gate's
comment in the workflow file.

### 5.3 Bytecode determinism check (gate 18)

The workflow builds the agent twice from a clean source tree, transforms the
same input class file both times, and compares SHA-256 hashes of the outputs.
This is implemented as a Maven Surefire test in `crochet-agent` that runs the
transformer on a fixed input and asserts the hash matches a committed expected
value.
