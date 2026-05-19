# crochet-compose-kit

Composition utilities for Crochet: a pre-baked agent-composition POM, a JUnit 5
`@CrochetCompositionTest` helper for multi-agent matrix testing, and a reference
table of known-good agent combinations with the failure mode each pre-baked
skip-list entry prevents.

---

## Known-good agent compositions

| Config | Agent stack | Pre-baked skip-list entries | Failure prevented |
|---|---|---|---|
| `crochet-only` | Crochet alone | (none beyond defaults) | N/A |
| `crochet+byte-buddy` | Crochet + Byte Buddy (Mockito-inline) | `$ByteBuddy$`, `$HibernateProxy$`, `_$$_Weld`, `$$$view` | ClassFormatError "Duplicate method" when Byte Buddy's MemberAccessor re-declares `$$crochet*` members on generated subclasses |
| `crochet+fray` | Crochet + Fray concurrency tester | `org/pastalab/fray/` | `checkpointAll` deadlock — Fray's scheduler classes (RunContext, RuntimeDelegate, ThreadContext) must not acquire Crochet's stripe-lock inside scheduler hot paths; instrumenting them makes them CRIJInstrumented and causes Fray issue #424 |

---

## Skip-list entry reference

Each entry in `CrochetTransformer.shouldSkip` is documented inline in source.
The following table summarises the entries relevant to agent composition:

### `$ByteBuddy$`

**Trigger**: Byte Buddy (used by Mockito-inline, Spring AOP, and Hibernate) generates
runtime subclasses via `net.bytebuddy.dynamic.ClassFileLocator`. Their class names
contain `$ByteBuddy$`.

**Failure without skip**: `ClassFormatError: Duplicate method name "$$crochetCopyFieldsTo"`
at class-load time. Byte Buddy's `MemberAccessor` scans the parent class via
`Class.getDeclaredMethods()` and re-declares inherited `$$crochet*` methods on the
subclass before our transformer sees it. The transformer then emits the methods again
on the subclass, creating duplicates.

**Pre-baked by**: `CrochetTransformer.shouldSkip` (in crochet-agent).

### `$HibernateProxy$`

**Trigger**: Hibernate's proxy factory creates runtime subclasses named like
`Pet$HibernateProxy$FyMglsPZ` via an internal ASM pass.

**Failure without skip**: Same "Duplicate method" `ClassFormatError` as `$ByteBuddy$`.
Hibernate's proxy factory also consumes the parent bytecode directly via ASM, not via
reflection, so the reflection-rewriter fix doesn't cover this path.

**Pre-baked by**: `CrochetTransformer.shouldSkip`.

### `_$$_Weld`

**Trigger**: JBoss Weld / WildFly EJB3 generates client-proxy and interceptor
subclasses named like `X$Proxy$_$$_WeldClientProxy`.

**Failure without skip**: `VerifyError: Expecting a stackmap frame at branch target 14`
during application server deployment (observed on `com/sun/faces/cdi/CdiExtension$Proxy$_$$_WeldClientProxy`
during tradebeans startup).

**Pre-baked by**: `CrochetTransformer.shouldSkip`.

### `$$$view`

**Trigger**: JBoss / WildFly EJB3 generates "view" proxy classes named like
`TradeSLSBLocal$$$view1`.

**Failure without skip**: `ClassFormatError: Duplicate method name "$$crochetCopyFieldsTo"`
during EJB deployment.

**Pre-baked by**: `CrochetTransformer.shouldSkip`.

### `org/pastalab/fray/`

**Trigger**: Fray concurrency testing framework instruments the JVM to control
thread scheduling. All classes under `org.pastalab.fray.*` — including the scheduler
runtime (RunContext, RuntimeDelegate, ThreadContext) — are part of Fray's internal
scheduler machinery.

**Failure without skip**: `checkpointAll()` deadlock. The failure path is:
1. `checkpointAll()` calls `Thread.getAllStackTraces()` to walk live threads.
2. Without the skip, Fray's internal threads are `CRIJInstrumented`.
3. The thread-walk attempts `fastAccess()` on Fray's internal thread objects.
4. `fastAccess()` acquires a stripe-lock entry.
5. The stripe-lock acquisition is a `ReentrantLock` — Fray intercepts it.
6. Fray's scheduler needs to record the lock event but the scheduler's own
   classes are currently inside a `fastAccess()` call → deadlock.

Confirmed via Fray issue #424 investigation (see `crochet-agent` git history for
the JVMTI-confirmed reproduction).

**Pre-baked by**: `CrochetTransformer.shouldSkip` (the Fray skip-list contribution
that this module documents).

---

## `@CrochetCompositionTest` usage

```java
import net.jonbell.crochet.compose.CrochetCompositionTest;
import net.jonbell.crochet.runtime.CheckpointRollbackAgent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@CrochetCompositionTest
class MyFeatureCompositionTest {

    @Test
    void checkpointAndRollbackIsIdempotentUnderComposition() {
        StringBuilder sb = new StringBuilder("hello");
        int v = CheckpointRollbackAgent.checkpoint(sb);
        sb.append(" world");
        CheckpointRollbackAgent.rollback(sb, v);
        assertEquals("hello", sb.toString());
    }
}
```

Run with:
```bash
mvn test -Dcrochet.compose.config=crochet-only
# or:
mvn test -Dcrochet.compose.config=crochet+fray
```

---

## Maven POM integration

To inherit the Fray-compatible Crochet agent version pin, add to your project's
`<parent>` or `<dependencyManagement>`:

```xml
<dependency>
    <groupId>edu.neu.ccs.prl.crochet</groupId>
    <artifactId>crochet-compose-kit</artifactId>
    <version>2.0.0-SNAPSHOT</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

This pins `crochet-agent` to the version that includes all documented skip-list
entries above.

---

## Universal gate 13: composition assert

Gate 13 in PLAN.md requires that if a unit changes the transform pipeline or adds
new injected surface, the composition-kit check is run against a representative
downstream (Fray, Byte Buddy via Mockito-inline). The
`InstrumentedSurfaceVerifier` (registered automatically with
`-Dcrochet.verifyInstrumented=true`) is the mechanism: it emits
`[Crochet-Verify] SURFACE_MISMATCH` on stderr when any `$$crochet*` surface
element is missing from a class that should have been instrumented.

The CI job `composition-assert` runs the composition tests with
`-Dcrochet.verifyInstrumented=true` and greps for `SURFACE_MISMATCH` lines; any
match is a gate failure.
