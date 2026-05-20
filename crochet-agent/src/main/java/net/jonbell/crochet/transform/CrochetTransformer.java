package net.jonbell.crochet.transform;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.jonbell.crochet.annotation.Internal;

@Internal
public class CrochetTransformer {

    public static final String RUNTIME_PACKAGE_PREFIX = "net/jonbell/crochet/runtime/";

    public static final String TRANSFORM_PACKAGE_PREFIX = "net/jonbell/crochet/transform/";

    private static final String AGENT_PACKAGE_PREFIX = "net/jonbell/crochet/agent/";

    private static final String PATCH_PACKAGE_PREFIX = "net/jonbell/crochet/patch/";

    private static final String ANNOTATION_PACKAGE_PREFIX = "net/jonbell/crochet/annotation/";

    /** Descriptor of {@link net.jonbell.crochet.annotation.CrochetSkip}. */
    static final String CROCHET_SKIP_DESC =
            "Lnet/jonbell/crochet/annotation/CrochetSkip;";

    /** Descriptor of the marker annotation added to every transformed class. */
    public static final String CROCHET_INSTRUMENTED_DESC =
            "Lnet/jonbell/crochet/annotation/CrochetInstrumented;";

    /**
     * System-property gate for {@link ReflectionRewriter}. Default off
     * pending a narrow-scope regression fix: with the rewriter enabled,
     * the Weld CDI bean resolver on tradebeans/tradesoap loses
     * {@code TransactionManager} discovery (WELD-001408: Unsatisfied
     * dependencies). The filter semantics themselves (filter-synthetic-
     * and-$$crochet-prefixed-only) are minimal, so the regression is
     * surprising; the working theory is that Weld's
     * {@code AnnotatedType} builder depends on the exact
     * {@code Class.getMethods()} ordering or count — both of which our
     * filter preserves except for removed entries. Leaving the code in
     * place and unit-tested but disabled by default lets us opt-in
     * ({@code -Dcrochet.reflectionRewriter=true}) on workloads that
     * actually enumerate our injected members (Hibernate/ByteBuddy
     * subclass generation, h2o Schema.fillFromParms).
     *
     * <p>The property is read via {@code AccessController.doPrivileged} so that
     * the agent's static initializer succeeds even when a restrictive
     * {@link SecurityManager} is installed before the agent's premain runs
     * (e.g. Lucene's {@code TestSecurityManager} denies
     * {@code PropertyPermission("crochet.*","read")} to unprivileged callers).
     * The agent is always trusted code (loaded from the boot classpath via
     * {@code -javaagent}), so reading our own system properties is safe.
     */
    @SuppressWarnings("removal")
    private static final boolean REFLECTION_REWRITER_ENABLED =
            Boolean.parseBoolean(
                    java.security.AccessController.doPrivileged(
                            (java.security.PrivilegedAction<String>) () ->
                                    System.getProperty("crochet.reflectionRewriter", "false")));

    public byte[] transform(byte[] classFileBuffer, boolean hostedAnonymous) {
        return transform(classFileBuffer, hostedAnonymous, null);
    }

    public byte[] transform(byte[] classFileBuffer, boolean hostedAnonymous, ClassLoader loader) {
        if (classFileBuffer == null) {
            return null;
        }
        ClassReader reader = new ClassReader(classFileBuffer);
        String name = reader.getClassName();
        if (shouldSkip(name)) {
            return null;
        }
        // User-class opt-out via @CrochetSkip: check the class file's own
        // annotation table and walk the superclass chain. This fires after the
        // hardcoded shouldSkip list (which already short-circuits for JDK /
        // framework incompatibilities the user cannot annotate) — the two
        // mechanisms are ORed together.
        if (hasSkipAnnotation(classFileBuffer, loader)) {
            return null;
        }
        // Enum classes, interfaces, annotations, and modules reject the
        // instance fields/methods we want to inject.
        int access = reader.getAccess();
        if ((access & (Opcodes.ACC_ENUM | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION
                | Opcodes.ACC_MODULE)) != 0) {
            return null;
        }
        // Anonymous enum-constant bodies are not flagged ACC_ENUM but extend Enum.
        String superName = reader.getSuperName();
        if ("java/lang/Enum".equals(superName)) {
            return null;
        }
        if (alreadyInstrumented(reader)) {
            // Either the jlink pipeline already baked instrumentation into
            // this class, or a prior transformer pass did. Returning null
            // preserves the existing bytes.
            return null;
        }
        // JDK classes go through a minimal pipeline — FieldAccessWrapper /
        // StaticFieldRewriter / ArrayAccessWrapper emit bytecode that
        // references ArrayRegistry / CheckpointRollbackAgent before the
        // runtime is wired in during JVM boot, which crashes the instrumented
        // JDK's early startup. The Gap 7 proof-of-concept needs only the
        // $$crochet surface + CRIJInstrumented interface on JDK classes;
        // user classes keep the full Gap 2/3/4 treatment.
        boolean isJdkClass = name != null
                && (name.startsWith("java/") || name.startsWith("jdk/")
                    || name.startsWith("sun/") || name.startsWith("com/sun/"));
        int classFileVersion = readMajorVersion(classFileBuffer);
        // Pre-Java-6 class files (major < 50) may use JSR/RET subroutines for
        // try/finally. COMPUTE_FRAMES can't process those directly, but
        // JSRInlinerAdapter rewrites them into straight-line control flow.
        // Apply only where needed — on modern bytecode the adapter still
        // buffers into a MethodNode, which is pure overhead.
        boolean needsJsrInlining = classFileVersion < Opcodes.V1_6;

        ClassWriter writer = new SafeClassWriter(reader,
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES, loader);
        // Chain (bottom → top). A single SharedLocalsProvider owns the lone
        // LocalVariablesSorter for the whole chain; every wrapper above it
        // that needs scratch locals delegates via SharedLocalsProvider.newLocal
        // instead of instantiating its own LVS. Stacking multiple LVS
        // instances produced cumulative local-index rewrites that broke
        // COMPUTE_FRAMES on large methods.
        ClassVisitor chain = writer;
        chain = new AnnotationStamper(Opcodes.ASM9, chain);
        chain = new LookupInjector(Opcodes.ASM9, chain);
        // Skip <clinit> registration emit for JDK classes: their static
        // initialisers run during JVM bootstrap, before the CheckpointRollback
        // Agent class itself is fully initialised on the packed-runtime path.
        // getAllLoadedClasses() in checkpointAll covers JDK-loaded roots
        // reactively once an agent is attached.
        chain = new FieldAdder(Opcodes.ASM9, chain, /*emitClinitRegistration=*/ !isJdkClass);
        // Gap 7 closure: JDK classes now go through the full wrapper
        // chain. Runtime entry points (noteStaticAccess, beforeStore,
        // interceptedArraycopy) are routed through RuntimeReady, whose
        // bootstrap gate returns early while the agent's dependency
        // closure is still class-initializing. That makes the emitted
        // INVOKESTATIC calls safe to run during JVM startup from
        // instrumented HashMap/TreeMap/etc. The !isJdkClass gate here
        // used to skip the wrappers entirely; it's dropped. isJdkClass
        // is still used above to suppress the <clinit> registration
        // emit, because that emit depends on CheckpointRollbackAgent
        // being reachable at class-load time rather than at runtime.
        SharedLocalsProvider locals = new SharedLocalsProvider(Opcodes.ASM9, chain);
        chain = locals;
        chain = new ArrayAccessWrapper(Opcodes.ASM9, chain, locals);
        chain = new StaticFieldRewriter(Opcodes.ASM9, chain, loader);
        chain = new ArrayCopyInterceptor(Opcodes.ASM9, chain);
        chain = new FieldAccessWrapper(Opcodes.ASM9, chain, locals, loader);
        // ByteBuddy's private classloaders ({@code ByteArrayClassLoader},
        // {@code MultipleParentClassLoader}) define dynamically-generated
        // classes (Mockito mocks, etc.) and inherit Crochet's
        // {@code implements CRIJInstrumented} stamp on each. Their
        // {@code parent} chain doesn't include the agent classloader, so
        // resolving {@code net.jonbell.crochet.runtime.CRIJInstrumented}
        // from a dynamically-defined mock class fails with CNFE. The
        // patcher prepends a fallback to the loader's resolution method
        // that routes Crochet runtime types through the agent loader. See
        // {@link ByteBuddyClassLoaderPatcher} for the bytecode shape and
        // the broader bug-shape rationale. Sits above
        // {@link FieldAccessWrapper} so the prelude bytes flow straight to
        // the writer without being wrapped — the prelude has no field
        // accesses, so this is purely a cleanliness preference.
        ByteBuddyClassLoaderPatcher.Target bbTarget =
                ByteBuddyClassLoaderPatcher.targetFor(name);
        if (bbTarget != null) {
            chain = new ByteBuddyClassLoaderPatcher(Opcodes.ASM9, chain,
                    bbTarget.internalName, bbTarget.methodName,
                    bbTarget.methodDesc, bbTarget.nameLocalSlot);
        }
        // ReflectionRewriter sits at the top of the user-class chain.
        // It only rewrites INVOKEVIRTUAL/INVOKESTATIC on specific
        // reflection APIs into INVOKESTATIC helpers in ReflectionFilter,
        // so it neither needs scratch locals nor interacts with frame
        // computation. Gated by REFLECTION_REWRITER_ENABLED (see the
        // property javadoc above for the Weld regression that keeps
        // the default off for now). Only rewritten for non-JDK classes
        // because JDK reflection call sites are not our concern.
        if (!isJdkClass && REFLECTION_REWRITER_ENABLED) {
            chain = new ReflectionRewriter(Opcodes.ASM9, chain);
        }
        // CheckpointWrapper sits above ReflectionRewriter / JsrInliner so it
        // sees the original (pre-JSR-inlined) descriptor but still operates on
        // fully inlined bytecode for older class files.  It does not need
        // scratch locals, so placement above SharedLocalsProvider is fine.
        // Only applied to user classes — JDK methods do not carry
        // @CrochetCheckpoint, and adding the visitor there would be dead weight.
        if (!isJdkClass) {
            chain = new CheckpointWrapper(Opcodes.ASM9, chain);
        }
        if (needsJsrInlining) {
            chain = new JsrInliner(Opcodes.ASM9, chain);
        }
        // EXPAND_FRAMES: required by LocalVariablesSorter, used by any
        // visitor that spills 2-slot values (long/double) via scratch locals.
        try {
            reader.accept(chain, ClassReader.EXPAND_FRAMES);
        } catch (LinkageError le) {
            // A LinkageError (typically NoClassDefFoundError for a class
            // referenced in a bootstrap method constant) can propagate from
            // SymbolTable.addBootstrapMethod when one or more bootstrap-
            // method arguments reference a class that is in an error state
            // (e.g. NondetRecorder during TTD agent initialisation). This
            // path is hit even though the INPUT bytes contain no such
            // reference, because the InvokeDynamic's bootstrap arguments
            // are resolved via addConstant() which can trigger class loading.
            //
            // Recovery: rebuild the writer without a shared reader so that
            // the SymbolTable starts fresh. The retry is semantically
            // equivalent — COMPUTE_FRAMES/COMPUTE_MAXS recompute all
            // frames and maxs from scratch regardless — the only difference
            // is that the output class file's constant pool is generated
            // from scratch rather than sharing indices with the input file.
            SafeClassWriter retryWriter = new SafeClassWriter(null,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES, loader);
            ClassVisitor retryChain = retryWriter;
            retryChain = new AnnotationStamper(Opcodes.ASM9, retryChain);
            retryChain = new LookupInjector(Opcodes.ASM9, retryChain);
            retryChain = new FieldAdder(Opcodes.ASM9, retryChain,
                    /*emitClinitRegistration=*/ !isJdkClass);
            SharedLocalsProvider retryLocals = new SharedLocalsProvider(Opcodes.ASM9, retryChain);
            retryChain = retryLocals;
            retryChain = new ArrayAccessWrapper(Opcodes.ASM9, retryChain, retryLocals);
            retryChain = new StaticFieldRewriter(Opcodes.ASM9, retryChain, loader);
            retryChain = new ArrayCopyInterceptor(Opcodes.ASM9, retryChain);
            retryChain = new FieldAccessWrapper(Opcodes.ASM9, retryChain, retryLocals, loader);
            if (bbTarget != null) {
                retryChain = new ByteBuddyClassLoaderPatcher(Opcodes.ASM9, retryChain,
                        bbTarget.internalName, bbTarget.methodName,
                        bbTarget.methodDesc, bbTarget.nameLocalSlot);
            }
            if (!isJdkClass && REFLECTION_REWRITER_ENABLED) {
                retryChain = new ReflectionRewriter(Opcodes.ASM9, retryChain);
            }
            if (!isJdkClass) {
                retryChain = new CheckpointWrapper(Opcodes.ASM9, retryChain);
            }
            if (needsJsrInlining) {
                retryChain = new JsrInliner(Opcodes.ASM9, retryChain);
            }
            reader.accept(retryChain, ClassReader.EXPAND_FRAMES);
            return retryWriter.toByteArray();
        }
        return writer.toByteArray();
    }

    /**
     * ClassWriter that never touches Class.forName during frame computation.
     *
     * <p>Stock ASM's {@code ClassWriter.getCommonSuperClass} resolves type
     * names to {@link Class} objects to compute the common superclass of two
     * types being merged. This requires the class loader that instantiated
     * ClassWriter to be able to resolve arbitrary types — which it can't when
     * instrumentation runs inside a ClassFileTransformer (the agent's
     * classloader has no visibility into the benchmark's classes). The failure
     * surfaces as {@code TypeNotPresentException} wrapping a CNFE on an
     * internal-name-form class name (seen on avrora, fop, pmd, sunflow).
     *
     * <p>The safe fallback is {@code java/lang/Object} — always a valid
     * common superclass. Frame computation stays correct but less precise;
     * the verifier accepts the looser (Object-typed) frame entries.
     */
    private static final class SafeClassWriter extends ClassWriter {
        /**
         * Process-wide cache of {@code internalName → superInternalName} from
         * the resource-stream walker. JFR on tradebeans showed
         * {@code SafeClassWriter.superOf} + its transitive
         * {@code getResourceAsStream} / {@link ClassReader} parsing in ~5% of
         * startup samples. With caching, each unique class is probed at most
         * once across the whole agent lifetime.
         *
         * <p>Kept cross-loader (keyed by type name only): a given internal
         * name almost always maps to the same supername regardless of which
         * loader we asked. In the rare case of same-named classes in
         * different loaders, the only consequence is a slightly less precise
         * common-super result — and the existing {@code "java/lang/Object"}
         * fallback was already imprecise, so this is strictly no worse.
         *
         * <p>{@link #SUPER_NONE} sentinel distinguishes "looked up, truly
         * unresolvable" (caches it) from "not yet looked up" (absent from
         * map). {@link java.util.concurrent.ConcurrentHashMap} rejects null
         * values, so we need a sentinel.
         */
        private static final java.util.concurrent.ConcurrentHashMap<String, String>
                SUPER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
        private static final String SUPER_NONE = "";

        private final ClassLoader loader;

        SafeClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(safeReader(reader), flags);
            this.loader = loader;
        }

        /**
         * Guards {@code ClassWriter(ClassReader, int)} against
         * {@link NoClassDefFoundError} thrown by
         * {@code SymbolTable.copyBootstrapMethods} when the input class
         * references a bootstrap-method handle whose owner class is in an
         * error state (e.g. {@code NondetRecorder} during TTD agent
         * initialisation). In that case we fall back to {@code null}, which
         * makes {@code ClassWriter(ClassReader, int)} behave like
         * {@code ClassWriter(int)} — it loses the ability to copy the constant
         * pool verbatim but still produces a valid class file. The only
         * observable consequence is that the output class file recomputes its
         * constant pool from scratch rather than reusing the reader's pool,
         * which is always safe.
         */
        private static ClassReader safeReader(ClassReader reader) {
            if (reader == null) return null;
            try {
                // Probe: does copyBootstrapMethods succeed for this reader?
                // Use flag=0 (no COMPUTE_FRAMES/COMPUTE_MAXS) so the writer
                // does the minimum work — we discard the result immediately.
                new ClassWriter(reader, 0);
                return reader;
            } catch (LinkageError ignored) {
                // A class referenced by a bootstrap method constant in this
                // class file is in an error state. Return null so the outer
                // ClassWriter(null, flags) constructor skips copyBootstrapMethods.
                return null;
            }
        }

        /**
         * Computes common superclass without invoking {@link Class#forName},
         * which can recursively trigger class loading while a transformer is
         * in flight (classloader deadlock / incorrect caching). We walk
         * superchains by reading the target class files directly via the
         * resource stream and an on-the-fly {@link ClassReader}.
         */
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) {
                return type1;
            }
            if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
                return "java/lang/Object";
            }
            java.util.Set<String> chain1 = superChain(type1);
            if (chain1.contains(type2)) {
                return type2;
            }
            java.util.Set<String> chain2 = superChain(type2);
            if (chain2.contains(type1)) {
                return type1;
            }
            for (String c : chain1) {
                if (chain2.contains(c)) {
                    return c;
                }
            }
            return "java/lang/Object";
        }

        private java.util.Set<String> superChain(String type) {
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            String c = type;
            while (c != null && out.add(c)) {
                c = superOf(c);
            }
            return out;
        }

        private String superOf(String type) {
            String cached = SUPER_CACHE.get(type);
            if (cached != null) {
                return cached == SUPER_NONE ? null : cached;
            }
            String result = superOfUncached(type);
            SUPER_CACHE.putIfAbsent(type, result != null ? result : SUPER_NONE);
            return result;
        }

        @SuppressWarnings("removal")
        private String superOfUncached(String type) {
            // Wrap resource reads in doPrivileged so that a restrictive
            // SecurityManager (e.g. Lucene's TestSecurityManager) does not
            // block the ClassLoader.getResourceAsStream() call with an
            // AccessControlException. The agent is always trusted code loaded
            // from the boot classpath; reading class-file bytes for frame-
            // computation purposes is safe and necessary.
            return java.security.AccessController.doPrivileged(
                    (java.security.PrivilegedAction<String>) () ->
                            superOfUncachedPrivileged(type));
        }

        private String superOfUncachedPrivileged(String type) {
            ClassLoader effective = loader != null ? loader
                    : SafeClassWriter.class.getClassLoader();
            // Walk the loader chain so user-jar classes and JDK classes both
            // resolve via their resource path.
            for (ClassLoader l = effective; l != null; l = l.getParent()) {
                try (java.io.InputStream in = l.getResourceAsStream(type + ".class")) {
                    if (in != null) {
                        return new ClassReader(in).getSuperName();
                    }
                } catch (java.io.IOException ignored) {
                } catch (SecurityException ignored) {
                    // SecurityManager blocked the resource read; try the next loader.
                }
            }
            try (java.io.InputStream in = ClassLoader.getSystemResourceAsStream(type + ".class")) {
                if (in != null) {
                    return new ClassReader(in).getSuperName();
                }
            } catch (java.io.IOException ignored) {
            } catch (SecurityException ignored) {
                // SecurityManager blocked the system resource read; fall through.
            }
            return null;
        }
    }

    /** Reads the major version field (bytes 6-7) from a class-file buffer. */
    private static int readMajorVersion(byte[] buf) {
        if (buf == null || buf.length < 8) {
            return 0;
        }
        return ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
    }

    public static boolean shouldSkip(String internalName) {
        if (internalName == null) {
            return true;
        }
        // module-info cannot take injected members.
        if (internalName.equals("module-info") || internalName.endsWith("/module-info")) {
            return true;
        }
        // Object is intentionally out of V1 scope: injecting a field on
        // java/lang/Object changes every object's layout and blows up the
        // JIT's fast-path inlining. Galette rewrites Object via
        // OffsetCacheAdder; we'll address it in V2.
        if (internalName.equals("java/lang/Object")) {
            return true;
        }
        // java.lang.Byte: injecting an int instance field
        // ({@code $$crochetVersion}) shifts the primitive {@code value} field
        // from offset 12 (stock) to offset 16. Byte is annotated
        // {@code @jdk.internal.ValueBased} and its {@code valueOf}/{@code byteValue}
        // methods are {@code @IntrinsicCandidate}; HotSpot has bytecode-level
        // assumptions about its layout that we cannot restore from the Java
        // side. Empirical impact on DaCapo h2o: the CSV parse pipeline still
        // emits 315 rows and 15 columns, but every numeric value lands as NaN
        // in the resulting Frame, so DRF sees a one-column (response-only)
        // training frame and fails with "Training data must have at least 2
        // features (incl. response)." Bisection on h2o-small narrowed the
        // instrumentation difference to a single class-file delta:
        // instrumenting ONLY Byte (everything else stock) reproduces the
        // failure; skipping ONLY Byte (everything else instrumented) passes.
        // Further narrowing showed the trigger is the int-typed field
        // specifically — adding {@code $$crochetSnap} alone (Object ref)
        // leaves h2o passing, adding {@code $$crochetVersion} alone (int)
        // reproduces the failure. Other boxed primitives (Short, Character,
        // Integer, Long, Float, Double, Boolean) don't trip h2o despite
        // identical instrumentation. Byte is uniquely exposed because
        // h2o's byte-level CSV parser, water's Iced/Unsafe-offset Icer
        // generation, and HotSpot's Byte intrinsics compose in a
        // layout-sensitive way.
        if (internalName.equals("java/lang/Byte")) {
            return true;
        }
        // Immutable JDK leaves: state invariant after construction, so
        // checkpoint / rollback on them is a no-op. Skipping removes
        // the per-instance {@code $$crochet*} fields (12 bytes each —
        // very visible on hot allocation sites for boxed primitives and
        // String concat), strips wraps from heavily-called methods like
        // {@code String.hashCode}, {@code Integer.intValue}, and frees
        // C2 inline budget on every method that touches them.
        //
        // <p>Other instrumented classes that hold references to these
        // immutables still snap the REFERENCE in their own
        // {@code $$crochetCopyFieldsTo}; on rollback the reference is
        // restored — and since the immutable instance's state never
        // changed, the restored reference is observationally identical
        // to a deep restore.
        //
        // <p>{@code Number} (abstract) must be in this set too: with
        // {@code Number} instrumented but {@code Integer} skipped, an
        // {@code Integer} instance inherits {@code Number}'s
        // {@code $$crochetCheckpoint} which calls
        // {@code allocateShadow(Number.class)} — fails because
        // {@code Number} is abstract. With {@code Number} also skipped,
        // {@code Integer} carries no {@code $$crochet} surface; the
        // {@code instanceof CRIJInstrumented} check at every reference-
        // field propagation site returns false for {@code Integer}, so
        // we walk past without invoking anything.
        //
        // <p>{@code BigInteger} / {@code BigDecimal} / {@code Atomic*}
        // (also Number subclasses) stay instrumented — they're concrete
        // and DO carry mutable state worth tracking. Their emitted
        // surface checks {@code superIsInstrumented(Number)} → false
        // and skips the super-call chain to Number cleanly.
        //
        // <p>{@code String}'s lazy {@code hash} cache is the one
        // post-{@code <init>} write — caches a deterministic function
        // of the immutable {@code value} array, so missing its rollback
        // means the next {@code hashCode()} recomputes the same value.
        if (internalName.equals("java/lang/String")
                || internalName.equals("java/lang/Number")
                || internalName.equals("java/lang/Integer")
                || internalName.equals("java/lang/Long")
                || internalName.equals("java/lang/Float")
                || internalName.equals("java/lang/Double")
                || internalName.equals("java/lang/Boolean")
                || internalName.equals("java/lang/Short")
                || internalName.equals("java/lang/Character")) {
            return true;
        }
        // java.lang.ThreadLocal and its nested classes: instrumenting them
        // causes infinite recursion at scale.  When many objects are being
        // checkpointed (checkpointWorldSafe with N > ~10k instances), the
        // JVMTI Phase-B CallVoidMethod path triggers GC reference processing
        // on the Reference Handler thread.  That thread calls
        // ThreadLocal.getMap() → $$crochetAccess on the ThreadLocal instance
        // → FastProxySupport.fastAccess → PropagateWorklist.enqueueOrRun
        // (which does DRAINING.get() → ThreadLocal.get() → ...) →
        // StackOverflowError.
        //
        // ThreadLocalMap is skipped for the same reason: it accesses ThreadLocal
        // fields and calls ThreadLocal.$$crochetAccess(), which doesn't exist once
        // ThreadLocal itself is skipped → NoSuchMethodError.
        //
        // Skipping these classes means thread-local state is not tracked across
        // checkpoint/rollback; this is acceptable because PropagateWorklist
        // uses ThreadLocals only for runtime bookkeeping (recursion detection,
        // drain queue), not for user-visible state.
        if (internalName.equals("java/lang/ThreadLocal")
                || internalName.equals("java/lang/InheritableThreadLocal")
                || internalName.startsWith("java/lang/ThreadLocal$")) {
            return true;
        }
        // java.lang.ClassValue and its nested classes (ClassValueMap,
        // ClassValueMap$Entry, Identity, Version, etc.): ClassMeta uses a
        // ClassValue<ClassMeta> as its per-class metadata cache. Instrumenting
        // ClassValue causes a ClassCircularityError on ClassValue$ClassValueMap:
        //
        //   noteDirty(inst) → ClassMeta.of(c) → CACHE.get(c)
        //     → ClassValue.get() → [ClassValue$ClassValueMap.<clinit>]
        //       → GETSTATIC hook → noteStaticAccess(ClassValue$ClassValueMap)
        //         → ClassMeta.of(ClassValue$ClassValueMap) → CACHE.get(...)
        //           → ClassValue$ClassValueMap (ALREADY INITIALIZING) →
        //             ClassCircularityError
        //
        // Skipping ClassValue and its nested classes avoids this recursion
        // entirely. ClassValue instances hold only internal JVM bookkeeping
        // (class-specific cached metadata), not user-visible mutable state, so
        // missing checkpoint/rollback on them is semantically safe.
        if (internalName.equals("java/lang/ClassValue")
                || internalName.startsWith("java/lang/ClassValue$")) {
            return true;
        }
        // Our own runtime/transform/agent/patch/annotation code must never
        // recurse — the instrumentation chain uses these classes directly.
        if (internalName.startsWith(RUNTIME_PACKAGE_PREFIX)
                || internalName.startsWith(TRANSFORM_PACKAGE_PREFIX)
                || internalName.startsWith(AGENT_PACKAGE_PREFIX)
                || internalName.startsWith(PATCH_PACKAGE_PREFIX)
                || internalName.startsWith(ANNOTATION_PACKAGE_PREFIX)) {
            return true;
        }
        // Shaded ASM under the agent's own relocated package.
        // The maven-shade-plugin relocates org.objectweb.asm →
        // edu.neu.ccs.prl.crochet.agent.shaded.asm, so the internal-name
        // prefix is edu/neu/ccs/prl/crochet/agent/shaded/.
        // (An older comment said "net/jonbell/crochet/agent/shaded/" but that
        // path does not exist in the shaded jar.)
        if (internalName.startsWith("edu/neu/ccs/prl/crochet/agent/shaded/")) {
            return true;
        }
        // crochet-ttd (time-travel debugger) runtime and its shaded ASM. When
        // the TTD jar is on the classpath alongside the crochet agent (as in
        // run-all.sh --instrumented), crochet's transformer would otherwise
        // try to instrument TTD's own transformer classes
        // (NondetTransformer$NondetMethodVisitor, etc.). Those classes use
        // invokedynamic bootstrap methods whose arguments reference
        // NondetRecorder — a class that is itself mid-load at the point
        // SafeClassWriter tries to initialise, producing a
        // NoClassDefFoundError: NondetRecorder inside copyBootstrapMethods.
        // The error is caught silently by TransformerWrapper, so Main.class
        // is returned un-instrumented (missing PUTFIELD hooks), which causes
        // rollback to restore nothing. TTD classes carry no user-visible
        // mutable state and must not be checkpointed/rolled-back, so skipping
        // them is both safe and necessary.
        if (internalName.startsWith("edu/neu/ccs/prl/crochet/ttd/")) {
            return true;
        }
        // crochet-instrument's own classes (jlink plugins, runtime support
        // for the instrument process itself) and its shaded ASM+JaCoCo.
        if (internalName.startsWith("net/jonbell/crochet/instrument/")) {
            return true;
        }
        // Fray concurrency-testing runtime — skip at both agent-load time and
        // jlink-instrument time. Fray's scheduler classes (RunContext,
        // RuntimeDelegate, ThreadContext, …) must not acquire Crochet's
        // stripe-lock inside scheduler hot paths, and Fray-internal Thread
        // objects must not be checkpointed by checkpointAll(). Instrumenting
        // them also makes them CRIJInstrumented, which causes checkpointAll's
        // Thread.getAllStackTraces() loop to attempt fastAccess on Fray's
        // internal threads — a ReentrantLock acquire inside the scheduler that
        // deadlocks or confounds the state Fray is tracking. See Fray issue
        // #424 investigation notes.
        if (internalName.startsWith("org/pastalab/fray/")) {
            return true;
        }
        // Gradle build-tool infrastructure (org/gradle/**). When Crochet runs
        // as a javaagent inside a Gradle test executor JVM (e.g. during the
        // Lucene showcase), Gradle's worker classes are on the classpath and
        // get instrumented like any other user class. Two failure modes:
        //
        // 1. serialVersionUID mismatch: Gradle's TestWorker implements
        //    Serializable without an explicit serialVersionUID, so the JVM
        //    computes it from the class structure. Crochet's field injection
        //    ($$crochetVersion int + $$crochetSnap Object) changes the
        //    computed UID, breaking the serialised-object protocol between the
        //    Gradle daemon and the test executor worker JVM.
        //
        // 2. VerifyError in GradleWorkerMain: COMPUTE_FRAMES merges two
        //    branches where a slot holds ClassLoader on one path and Object on
        //    the other (after an AASTORE scratch-store typed as Object), and
        //    cannot resolve GradleWorkerMain's super chain via resource lookup,
        //    so it falls back to java/lang/Object — breaking the subsequent
        //    invokevirtual ClassLoader.loadClass().
        //
        // Gradle classes carry no user-visible mutable state worth
        // checkpointing; skipping them is safe for all workloads.
        if (internalName.startsWith("org/gradle/")
                || internalName.startsWith("worker/org/gradle/")) {
            return true;
        }
        // carrotsearch randomizedtesting framework (com/carrotsearch/**):
        // used by Apache Lucene's test infrastructure. RandomizedRunner and
        // related classes contain exception-handler bytecode patterns where
        // COMPUTE_FRAMES merges the scratch-slot's Object type with an
        // exception-caught local, producing Object at the join point instead
        // of the original Throwable type — then the INVOKESPECIAL of the
        // exception constructor (which needs an uninitialized ref on the
        // stack, not Object) fails verification. This framework is test
        // scaffolding with no user-visible state to checkpoint; skipping it
        // is safe for the Lucene showcase and all other workloads.
        if (internalName.startsWith("com/carrotsearch/")) {
            return true;
        }
        // JUnit test framework classes (org/junit/**) and the legacy junit.framework
        // package (junit/framework/**). Test framework code is scaffolding loaded
        // into the test executor JVM alongside the workload. Instrumenting JUnit's
        // reflection-heavy runner infrastructure produces VerifyErrors (same
        // COMPUTE_FRAMES exception-handler issue as com/carrotsearch above) and
        // serialVersionUID mismatches. JUnit classes hold no user-visible mutable
        // state to checkpoint.
        if (internalName.startsWith("org/junit/")
                || internalName.startsWith("junit/")) {
            return true;
        }
        // Lucene test-framework classes (org/apache/lucene/tests/**). These are
        // test scaffolding (LuceneTestCase, TestUtil, etc.) that extend JUnit and
        // carrotsearch's RandomizedRunner. They are NOT the system under test —
        // the actual Lucene library classes live under org/apache/lucene/ without
        // the /tests/ infix. Instrumenting LuceneTestCase and its siblings
        // produces VerifyErrors in methods that have exception-handler locals
        // narrower than Object (e.g. _expectThrows returns Throwable, but
        // COMPUTE_FRAMES widens local 2 to Object when it can't resolve the
        // caught exception's super chain through the agent's resource-stream
        // walk at transform time).
        if (internalName.startsWith("org/apache/lucene/tests/")) {
            return true;
        }
        // JVM-fabricated classes: lambdas, proxies, reflection-generated
        // accessors. These have no ProtectionDomain; they're built after the
        // jlink pass, so they can never carry the @CrochetInstrumented marker
        // and can't be safely transformed at runtime (no stable class name).
        if (internalName.contains("$$Lambda") || internalName.contains("/$Proxy")) {
            return true;
        }
        // Agent-synthesized hidden proxy classes (Counter$$crochetFast,
        // Counter$$crochetSFHelper, ...). The Specializer defines these via
        // Lookup#defineHiddenClass; the JVM appends "/0x..." before passing
        // them through ClassFileTransformers.
        if (internalName.contains("$$crochet")) {
            return true;
        }
        // Jython compiles .py files to JVM classes named "<module>$py" at
        // runtime. Their methods return PyObject-family types whose super
        // chain SafeClassWriter can't resolve via resource lookup (the types
        // live in the Python script's classloader), so frame computation
        // widens ARETURN targets to java/lang/Object and the verifier rejects
        // them with VerifyError.
        if (internalName.endsWith("$py")) {
            return true;
        }
        // ByteBuddy auxiliary classes (name contains "$ByteBuddy$") are
        // generated at runtime and frequently inherit from already-instrumented
        // user classes. The failure mode is "ClassFormatError: Duplicate
        // method" when ByteBuddy's MemberAccessor scans the parent via
        // Class.getDeclaredMethods and re-declares our $$crochet* members on
        // the subclass before our transformer sees it. ReflectionRewriter
        // closes the reflection bypass that makes this possible (empirically
        // verified: spring PASSES with this skip removed when the rewriter
        // is enabled), but we keep this skip until ReflectionRewriter is
        // default-on across the DaCapo matrix.
        if (internalName.contains("$ByteBuddy$")) {
            return true;
        }
        // ByteBuddy synthesises {@code net.bytebuddy.mirror.<Type>} runtime
        // classes as bytewise field-layout copies of JDK reflection types
        // (currently just {@code java.lang.reflect.AccessibleObject}). It
        // then computes a field offset on the mirror via
        // {@code Unsafe.objectFieldOffset(mirror.field("override"))} and
        // applies the SAME offset to real {@code Field} / {@code Method}
        // instances via {@code Unsafe.putBoolean} — a trick used in
        // {@code ClassInjector$UsingUnsafe$Dispatcher$CreationAction.run}
        // to set {@code Field.override = true} without the reflection-
        // permission check. If we add {@code $$crochetVersion} /
        // {@code $$crochetSnap} fields to the mirror, the JVM may reorder
        // the layout (4-byte / 8-byte packing), shifting {@code override}
        // to a different offset on the mirror than on the JDK class. The
        // wrong-offset {@code putBoolean} corrupts a different field on
        // the real {@code Field} (typically {@code accessCheckCache} or
        // {@code root}); the JIT-compiled {@code AccessibleObject.verifyAccess}
        // then loads a bogus "compressed oop" value of {@code 0x1} from
        // {@code accessCheckCache}, decompresses to address {@code 0x8},
        // and SIGSEGVs at {@code [0x10]} when reading the
        // {@code WeakReference.referent} field. Skip the entire
        // {@code net/bytebuddy/mirror/} package — these classes have no
        // crochet-relevant state of their own (they only exist for layout
        // mirroring), so we lose nothing by leaving them alone.
        if (internalName.startsWith("net/bytebuddy/mirror/")) {
            return true;
        }
        // Hibernate runtime proxies (e.g. Pet$HibernateProxy$FyMglsPZ) extend
        // instrumented entity classes and inherit our $$crochetCopyFieldsTo;
        // instrumenting the subclass re-emits the method and the class loader
        // rejects the duplicate. ReflectionRewriter handles the
        // reflection-enumeration half of the legacy breakage, but Hibernate's
        // proxy factory also consumes the parent bytecode directly via an
        // ASM pass that doesn't go through reflection — keeping this skip
        // bypasses both code paths with zero runtime cost.
        if (internalName.contains("$HibernateProxy$")) {
            return true;
        }
        // JBoss Weld / WildFly EJB3 generate runtime "view" proxy classes
        // named like {@code TradeSLSBLocal$$$view1} for each EJB bean. They
        // extend the instrumented user class and inherit its $$crochet*
        // members; re-emitting them on the subclass produces
        // {@code ClassFormatError: Duplicate method name "$$crochetCopyFieldsTo"}
        // during deployment. The suffix is {@code $$$view<n>} — three
        // dollars, then "view", then a decimal.
        if (internalName.contains("$$$view")) {
            return true;
        }
        // JBoss Weld runtime proxies (client-proxy / interceptor subclass):
        // generated names carry the {@code _$$_Weld} infix, e.g.
        // {@code X$Proxy$_$$_WeldClientProxy} or
        // {@code X$Proxy$_$$_WeldSubclass}. They subclass the instrumented
        // bean and inherit its $$crochet* methods already; running them
        // through our bytecode chain emits bad stack maps
        // ({@code VerifyError: Expecting a stackmap frame at branch
        // target 14} on com/sun/faces/cdi/CdiExtension$Proxy$_$$_WeldClientProxy
        // during tradebeans startup).
        if (internalName.contains("_$$_Weld")) {
            return true;
        }
        // JFR validates that every event class has a native mirror matching
        // the declared instance-field list exactly. Adding our $$crochet*
        // fields to any class the JFR runtime touches — jdk.jfr.Event itself,
        // its jdk.jfr.events.* subclasses, the jdk.internal.event.* family
        // (ThreadSleepEvent etc.), or the jdk.jfr.consumer.* readers — makes
        // JFR abort at VM startup with "Found additional fields in mirror
        // class". We broadly skip both JFR trees; these are event-recording
        // classes with no interesting mutable state for our purposes anyway.
        if (internalName.startsWith("jdk/internal/event/")
                || internalName.startsWith("jdk/jfr/")) {
            return true;
        }
        return false;
    }

    /**
     * Cheap pre-scan that returns {@code true} iff the class file already
     * carries {@code @CrochetInstrumented}. Skips code, debug, and frame data;
     * only the header and attribute table are read.
     */
    private static boolean alreadyInstrumented(ClassReader reader) {
        return hasAnnotation(reader, CROCHET_INSTRUMENTED_DESC);
    }

    /**
     * Cheap pre-scan that returns {@code true} iff the class file carries the
     * named annotation descriptor (e.g.
     * {@code "Lnet/jonbell/crochet/annotation/CrochetSkip;"}).
     * Skips code, debug, and frame data; only the header and attribute table
     * are read.
     */
    private static boolean hasAnnotation(ClassReader reader, String desc) {
        AnnotationPresenceVisitor v = new AnnotationPresenceVisitor(desc);
        reader.accept(v, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return v.found;
    }

    private static final class AnnotationPresenceVisitor extends ClassVisitor {
        private final String target;
        boolean found;

        AnnotationPresenceVisitor(String target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (target.equals(descriptor)) {
                found = true;
            }
            return null;
        }
    }

    /**
     * Returns {@code true} if the class (or any of its superclasses, excluding
     * {@code java.lang.Object}) carries {@code @CrochetSkip}.
     *
     * <p>Java's {@link java.lang.annotation.Inherited} meta-annotation is not
     * used because it operates on the reflective layer and requires the
     * annotated class to be loaded. The transformer runs before classes are
     * loaded, so inheritance is implemented explicitly by walking the superclass
     * chain via class-file resource reads — the same technique used by
     * {@link SafeClassWriter#superOfUncached}.
     *
     * <p>The class file passed as {@code classFileBuffer} is the bytes already
     * available in the caller (no re-read). For each ancestor we re-read from
     * the class loader's resource stream. The walk stops at {@code java/lang/Object}
     * (which can never carry {@code @CrochetSkip} — it lives in the hardcoded
     * list), at a name that {@link #shouldSkip} would already suppress, or when
     * the resource stream can't locate the ancestor class file.
     *
     * @param classFileBuffer bytes of the class being transformed (non-null)
     * @param loader          the classloader active at transform time, or
     *                        {@code null} for the boot loader
     * @return {@code true} to suppress instrumentation of this class
     */
    static boolean hasSkipAnnotation(byte[] classFileBuffer, ClassLoader loader) {
        // Check the class itself first.
        if (classFileHasSkipAnnotation(classFileBuffer)) {
            return true;
        }
        // Walk superclasses.
        ClassReader root = new ClassReader(classFileBuffer);
        String superName = root.getSuperName();
        while (superName != null
                && !superName.equals("java/lang/Object")
                && !shouldSkip(superName)) {
            byte[] superBytes = loadClassBytes(superName, loader);
            if (superBytes == null) {
                break;
            }
            if (classFileHasSkipAnnotation(superBytes)) {
                return true;
            }
            superName = new ClassReader(superBytes).getSuperName();
        }
        return false;
    }

    /**
     * Checks whether the given raw class-file bytes carry
     * {@code @CrochetSkip} (RUNTIME-retained, so {@code visible=true}).
     */
    private static boolean classFileHasSkipAnnotation(byte[] classBytes) {
        SkipAnnotationVisitor v = new SkipAnnotationVisitor();
        new ClassReader(classBytes).accept(
                v, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return v.found;
    }

    /**
     * Loads the raw class-file bytes for {@code internalName} from the given
     * class loader's resource stream, falling back to the system class loader.
     * Returns {@code null} if the resource is not found.
     */
    private static byte[] loadClassBytes(String internalName, ClassLoader loader) {
        String resource = internalName + ".class";
        // Walk the loader chain so user-jar classes and JDK classes both resolve.
        ClassLoader effective = loader != null ? loader
                : SafeClassWriter.class.getClassLoader();
        for (ClassLoader l = effective; l != null; l = l.getParent()) {
            try (java.io.InputStream in = l.getResourceAsStream(resource)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            } catch (java.io.IOException ignored) {
            }
        }
        try (java.io.InputStream in = ClassLoader.getSystemResourceAsStream(resource)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (java.io.IOException ignored) {
        }
        return null;
    }

    private static final class SkipAnnotationVisitor extends ClassVisitor {
        boolean found;

        SkipAnnotationVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (CROCHET_SKIP_DESC.equals(descriptor)) {
                found = true;
            }
            return null;
        }
    }

}
