# crochet-junit5

JUnit 5 extension that takes a Crochet checkpoint after `@BeforeAll` and
rolls back between `@Test` methods. Lets test classes with expensive setup
amortise the cost across all their tests instead of paying it per test.

## Use

```java
@ExtendWith(CrochetSetupExtension.class)
class MyTest {
    @CrochetTrack static MyExpensiveResource resource;

    @BeforeAll
    static void setup() {
        resource = MyExpensiveResource.build();   // expensive
    }

    @Test void firstMutation()  { resource.add("a"); ... }
    @Test void secondMutation() { resource.add("b"); ... }   // sees pristine resource
    @Test void thirdMutation()  { resource.remove("seed"); ... }   // also pristine
}
```

The first test sees the post-`@BeforeAll` state. Each subsequent test starts
from the same captured snapshot — every prior test's mutations are reverted
before the next runs.

## Run

Add the agent to your test JVM:

```xml
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>-javaagent:${path.to.crochet-agent}.jar --add-reads java.base=jdk.unsupported</argLine>
  </configuration>
</plugin>
```

Gradle equivalent:

```kotlin
tasks.test {
    jvmArgs("-javaagent:$pathToCrochetAgent", "--add-reads", "java.base=jdk.unsupported")
}
```

## Caveats

- **JDK collections (HashMap, ArrayList, ...) need a Crochet-instrumented
  JDK to be tracked.** The runtime `-javaagent` only transforms classes
  loaded after agent attach; JDK classes are loaded earlier. If your test
  resource holds a `HashMap`, mutations to the map won't be rolled back
  unless you run on `crochet-instrument`'s JDK build (see top-level README).
  For a quick test with rollback semantics on JDK collections, swap the
  collection for a hand-rolled equivalent or run on the instrumented JDK.

- **`@CrochetTrack` fields must be static and non-null at the first
  `@Test` entry.** Initialize them in `@BeforeAll`.

- **`@BeforeEach` runs as JUnit intends** — its mutations are NOT captured
  in the post-`@BeforeAll` snapshot. The amortisation is over `@BeforeAll`
  cost only. If your `@BeforeEach` is also expensive, the extension still
  helps but only to the extent that `@BeforeAll`'s share is large.

- **Ordering**: the snapshot is taken on the first test's `BeforeEachCallback`
  (which fires after all `@BeforeAll` and before any `@BeforeEach`). If
  another extension has its own `BeforeEachCallback` that mutates state,
  ordering depends on extension registration order — call this extension
  last with `@ExtendWith` to ensure it observes the final pre-test state.

## Mechanism

`CrochetSetupExtension` is a `BeforeEachCallback`. On the first invocation
for a given test class:

1. Walk every `@CrochetTrack`-annotated static field on the test class.
2. Call `CheckpointRollbackAgent.checkpoint(value)` for each, store the
   returned version.
3. Call `CheckpointRollbackAgent.checkpointAll()` to capture the static-field
   state of every Crochet-instrumented class touched during setup.

On every subsequent invocation:

1. Roll each tracked root back to its captured version.
2. Roll the global static state back to its captured global version.
3. Re-checkpoint immediately so the next test rolls back to the same
   baseline (Crochet consumes the snapshot on rollback).

Cost per test = (rollback) + (re-checkpoint), which on Crochet's lazy
heap traversal is O(touched-objects-during-test), not O(setup-graph-size).
That's the win.
