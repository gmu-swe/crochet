# Proposed `CrochetTransformer.shouldSkip` rewrite

Replaces the current implementation in
`crochet-agent/src/main/java/net/jonbell/crochet/transform/CrochetTransformer.java`
lines 36-62.

## Intent

The single transformer is invoked in two contexts — build-time under jlink
and runtime under `-javaagent:` — but its correctness policy is the same:

1. Never double-instrument. Mark transformed classes with
   `@CrochetInstrumented` and skip on re-entry.
2. Never touch our own runtime or the ASM we shipped.
3. Never touch `java/lang/Object` (V1 scope does not ship an Object
   rewriter; see Galette's `OffsetCacheAdder`/`ThreadLocalAdder` for what
   would be involved).
4. Never touch JVM-fabricated lambda / hidden / VM-anonymous classes — they
   share the class loader of their target but are built after transform
   time and can't host `$$crochetLookup` safely.
5. Do touch everything else, including `java/util/*`, `java/lang/*`
   (except `Object`), and `jdk/internal/*`, **when** the caller is the
   jlink plugin. At runtime, those classes arrive already carrying
   `@CrochetInstrumented` and fall through rule 1.

## New annotation

Added once, in `crochet-agent`:

```java
// crochet-agent/src/main/java/net/jonbell/crochet/runtime/CrochetInstrumented.java
package net.jonbell.crochet.runtime;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Marker applied by CrochetTransformer to every class it rewrites. */
@Retention(RetentionPolicy.CLASS)
public @interface CrochetInstrumented {}
```

The transformer's `transform` method adds it via
`visitor.visitAnnotation("Lnet/jonbell/crochet/runtime/CrochetInstrumented;",
false)` after the existing `reader.accept(chain, 0)` (insertion point inside
`LookupInjector.visit` or a new small `AnnotationAdder` visitor — either
works; a dedicated visitor keeps the responsibility single-purpose).

## Updated `shouldSkip`

```java
static boolean shouldSkip(String internalName) {
    if (internalName == null) {
        return true;
    }
    // module-info cannot take injected members.
    if (internalName.equals("module-info") || internalName.endsWith("/module-info")) {
        return true;
    }
    // Object is intentionally out of V1 scope: injecting a field on
    // java/lang/Object changes every object's layout and blows up the
    // JIT's fast-path inlining; Galette rewrites Object via
    // OffsetCacheAdder, we'll address it in V2.
    if (internalName.equals("java/lang/Object")) {
        return true;
    }
    // Our own runtime/transform/agent/patch code must never recurse.
    if (internalName.startsWith(RUNTIME_PACKAGE_PREFIX)
            || internalName.startsWith(TRANSFORM_PACKAGE_PREFIX)
            || internalName.startsWith(AGENT_PACKAGE_PREFIX)
            || internalName.startsWith(PATCH_PACKAGE_PREFIX)) {
        return true;
    }
    // Shaded ASM under the agent's own relocated package.
    if (internalName.startsWith("net/jonbell/crochet/agent/shaded/")) {
        return true;
    }
    // Shaded ASM + JaCoCo inside the instrument jar (when the jlink
    // plugin is itself loaded by the jlink process).
    if (internalName.startsWith("net/jonbell/crochet/instrument/shaded/")) {
        return true;
    }
    // JVM-fabricated classes: lambdas, hidden classes (InnerClassLambdaMetafactory),
    // reflection generated classes. Their ProtectionDomain is often null and
    // they never round-trip through the jlink pass, so they cannot be marked
    // as pre-instrumented.
    if (internalName.contains("$$Lambda") || internalName.contains("/$Proxy")) {
        return true;
    }
    return false;
}
```

Note what's **removed**: the `startsWith("java/")` / `startsWith("jdk/")` /
`startsWith("sun/")` / `startsWith("com/sun/")` block. Those prefixes are
now handled by the annotation check.

## The annotation check

`shouldSkip` is a string test; the annotation test requires a tiny
`ClassReader` pass. The transformer's entry point gains a pre-scan:

```java
public byte[] transform(byte[] classFileBuffer, boolean hostedAnonymous) {
    if (classFileBuffer == null) {
        return null;
    }
    ClassReader reader = new ClassReader(classFileBuffer);
    String name = reader.getClassName();
    if (shouldSkip(name)) {
        return null;
    }
    if (alreadyInstrumented(reader)) {
        return null;              // jlink-baked class hit by the runtime agent
    }
    // ... existing chain ...
}

private static boolean alreadyInstrumented(ClassReader reader) {
    AnnotationPresenceVisitor v = new AnnotationPresenceVisitor();
    reader.accept(v, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return v.found;
}

private static final class AnnotationPresenceVisitor extends ClassVisitor {
    private static final String DESC =
        "Lnet/jonbell/crochet/runtime/CrochetInstrumented;";
    boolean found;
    AnnotationPresenceVisitor() { super(Opcodes.ASM9); }
    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        if (DESC.equals(descriptor)) {
            found = true;
        }
        return null;
    }
}
```

This is O(header + attribute table) per class — negligible compared to the
full transform pipeline, and it runs only once per class load at runtime.

## Test coverage

The existing `CrochetTransformerTest` already checks that transforming a
user class twice is idempotent (the second pass should return null). After
this change, add:

- A test that feeds `transform` a class file that already bears the
  `@CrochetInstrumented` annotation (built by calling `transform` once,
  feeding the output back in) and asserts the second call returns `null`
  without recursing into the chain.
- A test that feeds `transform` a fabricated `java/util/Foo` class
  lacking the annotation and asserts the full chain runs (ensures we
  actually removed the JDK-prefix skip for the positive case).
