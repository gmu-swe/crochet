package net.jonbell.crochet.transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Adds the CRIJInstrumented surface to every user class that passes the
 * CrochetTransformer filter.
 *
 * <p>For each class, this visitor:
 * <ul>
 *   <li>Declares {@code implements CRIJInstrumented} on the class.
 *   <li>Adds two injected fields:
 *     <ul>
 *       <li>{@code int $$crochetVersion} — current checkpoint/rollback version
 *       <li>{@code Object $$crochetSnap}  — a same-class shadow instance
 *           holding the checkpointed field values (null if no live checkpoint)
 *     </ul>
 *   <li>Emits the ten CRIJInstrumented methods with bodies that operate on
 *       the user class's declared instance fields:
 *     <ul>
 *       <li>{@code $$crochetCopyFieldsTo(Object)} / {@code $$crochetCopyFieldsFrom(Object)}
 *           — bulk copy of every declared instance field
 *       <li>{@code $$crochetCheckpoint(int v)} — if snap is null, allocate a
 *           shadow via {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#allocateShadow},
 *           copy fields into it, store it. Record v.
 *       <li>{@code $$crochetRollback(int v)} — if snap is non-null, copy
 *           fields back from it, clear snap. Record v.
 *       <li>getters/setters for the version field
 *       <li>propagate* — no-op placeholders (V0 scope; V1 walks reference fields)
 *       <li>{@code $$crochetAccess()} — no-op in V0
 *       <li>{@code $$crochetIsRollbackState()} — returns {@code (version % 2) == 0 && version != 0}
 *     </ul>
 * </ul>
 *
 * <p>Bodies for the simple CRIJ surface methods (get/set version + snap,
 * copy-fields, propagate, access, is-rollback-state) live in
 * {@link InstrumentedSurfaceEmitter} and are shared with
 * {@link StaticFieldHelperTemplate}. The version-guarded checkpoint/rollback
 * entry ({@link #emitVersionGuardedEntry}) is user-class-specific and stays
 * here.
 */
public final class FieldAdder extends ClassVisitor {

    public static final String VERSION_FIELD = "$$crochetVersion";
    public static final String SNAP_FIELD = "$$crochetSnap";

    private static final String INSTRUMENTED = "net/jonbell/crochet/runtime/CRIJInstrumented";
    private static final String AGENT = "net/jonbell/crochet/runtime/CheckpointRollbackAgent";

    /** Descriptor of {@link net.jonbell.crochet.annotation.CrochetEager}. */
    static final String CROCHET_EAGER_DESC =
            "Lnet/jonbell/crochet/annotation/CrochetEager;";

    /**
     * Fully-qualified class names opted in to the eager strategy via
     * {@code -Dcrochet.eagerClasses=Foo.Bar,Baz}. Internal/slash form. The
     * transformer consults this plus the {@code @CrochetEager} annotation —
     * either is sufficient.
     *
     * <p>Cached in a volatile field keyed by the string form of the property.
     * Re-parses only when the underlying property changes, which in practice
     * never happens outside tests (the system property is set at JVM start).
     */
    private static volatile String eagerPropCached;
    private static volatile Set<String> eagerInternalNamesCached = Collections.emptySet();

    private static Set<String> eagerClassInternalNames() {
        String prop = System.getProperty("crochet.eagerClasses");
        if (prop == null) {
            if (eagerPropCached != null) {
                eagerPropCached = null;
                eagerInternalNamesCached = Collections.emptySet();
            }
            return Collections.emptySet();
        }
        String cached = eagerPropCached;
        if (prop.equals(cached)) {
            return eagerInternalNamesCached;
        }
        Set<String> parsed = parseEagerClassInternalNames(prop);
        eagerInternalNamesCached = parsed;
        eagerPropCached = prop;
        return parsed;
    }

    private static Set<String> parseEagerClassInternalNames(String prop) {
        if (prop == null || prop.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (String part : prop.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed.replace('.', '/'));
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * Emits a sentinel-aware body for {@code $$crochetCheckpoint} /
     * {@code $$crochetRollback} per paper Listing 3:
     * <pre>
     *   loop:
     *   int cur   = Agent.versionVolatileGet(this, ThisClass.class);
     *   int realV = Math.abs(cur);
     *   if (realV &gt;= v) return;                                         // I2 guard
     *   if (!Agent.versionCas(this, ThisClass.class, cur, -v)) goto loop; // retry
     *   try {
     *       Agent.swapToFastProxy(this, ThisClass.class);
     *   } catch (Throwable t) {
     *       Agent.versionCas(this, ThisClass.class, -v, cur);             // best-effort restore
     *       throw new RollbackException(POISON_VERSION, t);
     *   }
     *   Agent.versionCas(this, ThisClass.class, -v, v);                   // finalize
     * </pre>
     *
     * <p>Sentinel value {@code -v} closes the "version bumped but klass not
     * yet swapped" race window: a concurrent reader that observes {@code -v}
     * decodes {@code realV = v} and sees the same parity/branch decision it
     * would observe after the finalize.
     *
     * <p>The CAS retry loop is required for correctness: if a lower-version
     * peer wins the sentinel CAS first (e.g. v=5 beats v=7), the higher-version
     * thread must retry rather than return — otherwise the object is permanently
     * stuck at the lower version violating the "highest concurrent version wins"
     * invariant that scenario 10-concurrent-checkpoint validates. The I2 guard
     * at the top terminates the loop once {@code realV &ge; v}.
     *
     * <p>Gap 8 integration: a throw from {@code swapToFastProxy} (i.e., proxy
     * class generation failed) restores the pre-sentinel version via CAS so
     * no other thread observes a stuck sentinel, and raises a
     * {@link net.jonbell.crochet.runtime.RollbackException#POISON_VERSION}
     * carrying the cause.
     *
     * <p>Local layout: slot 0 is {@code this}, slot 1 is {@code v}, slot 2 is
     * {@code cur}, slot 3 is {@code realV}, slot 4 is the caught throwable.
     */
    private void emitVersionGuardedEntry(MethodVisitor mv) {
        Label loopHead     = new Label();
        Label proceed      = new Label();
        Label gotSentinel  = new Label();
        Label tryStart     = new Label();
        Label tryEnd       = new Label();
        Label handler      = new Label();
        Label afterHandler = new Label();

        // ---- cur = Agent.versionVolatileGet(this, ThisClass.class)
        mv.visitLabel(loopHead);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "versionVolatileGet",
                "(Ljava/lang/Object;Ljava/lang/Class;)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 2);                        // cur -> slot 2

        // ---- realV = Math.abs(cur)
        // HotSpot compiles Math.abs(int) to a branchless CMOV intrinsic on
        // x86_64 / AArch64, saving a conditional branch + GOTO and shaving
        // ~5 bytes of bytecode per checkpoint/rollback entry. Math.abs
        // returns Integer.MIN_VALUE for Integer.MIN_VALUE (overflow); this
        // is benign here because the paper's I2 guard below uses the same
        // decoded value, so both sides of the comparison see identical
        // overflow behaviour. We'd only wrap past Integer.MIN_VALUE with
        // ~2^31 checkpoint/rollback cycles, which is not reachable in any
        // realistic workload.
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs",
                "(I)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3);                        // realV -> slot 3

        // ---- if (realV >= v) return;  (I2 guard + cycle termination)
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, proceed);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(proceed);

        // ---- if (!Agent.versionCas(this, ThisClass.class, cur, -v)) retry;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitVarInsn(Opcodes.ILOAD, 2);                          // expect = cur
        mv.visitVarInsn(Opcodes.ILOAD, 1);                          // v
        mv.visitInsn(Opcodes.INEG);                                  // -v
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "versionCas",
                "(Ljava/lang/Object;Ljava/lang/Class;II)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, gotSentinel);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);                    // retry: re-read cur
        mv.visitLabel(gotSentinel);

        // ---- try { Agent.swapToFastProxy(this, ThisClass.class); }
        mv.visitLabel(tryStart);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "swapToFastProxy",
                "(Ljava/lang/Object;Ljava/lang/Class;)V", false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, afterHandler);

        // ---- catch (Throwable t):
        //      Agent.versionCas(this, ThisClass.class, -v, cur);  // best-effort restore
        //      throw new RollbackException(POISON_VERSION, t);
        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.INEG);                                  // expect = -v
        mv.visitVarInsn(Opcodes.ILOAD, 2);                           // update = cur
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "versionCas",
                "(Ljava/lang/Object;Ljava/lang/Class;II)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, "net/jonbell/crochet/runtime/RollbackException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(-1);                                         // POISON_VERSION
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "net/jonbell/crochet/runtime/RollbackException",
                "<init>", "(ILjava/lang/Throwable;)V", false);
        mv.visitInsn(Opcodes.ATHROW);

        // ---- finalize: Agent.versionCas(this, ThisClass.class, -v, v); return;
        mv.visitLabel(afterHandler);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.INEG);                                  // expect = -v
        mv.visitVarInsn(Opcodes.ILOAD, 1);                           // update = v
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "versionCas",
                "(Ljava/lang/Object;Ljava/lang/Class;II)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
    }

    private String className;
    private String superName;
    private final List<InstrumentedSurfaceEmitter.FieldRef> instanceFields = new ArrayList<>();
    private boolean alreadyInstrumented;
    private boolean hasVersionField;
    private boolean hasSnapField;
    private boolean hasClinit;
    private final boolean emitClinitRegistration;
    private boolean eagerMode;

    public FieldAdder(int api, ClassVisitor delegate) {
        this(api, delegate, true);
    }

    /**
     * @param emitClinitRegistration when {@code true} (the default for user
     *        classes) the visitor emits a
     *        {@code CheckpointRollbackAgent.registerInitializedClass(ThisClass.class)}
     *        call at the top of every class's {@code <clinit>} — synthesising
     *        one if absent. Set to {@code false} for JDK classes on the
     *        minimal pipeline: JDK {@code <clinit>} can fire during JVM
     *        bootstrap before our agent runtime is initialised, and even our
     *        try/catch-wrapped emit is more risk than benefit there. Classes
     *        initialised via the JDK's own bootstrap are captured instead via
     *        the {@link java.lang.instrument.Instrumentation#getAllLoadedClasses()}
     *        fallback in {@code checkpointAll}.
     */
    public FieldAdder(int api, ClassVisitor delegate, boolean emitClinitRegistration) {
        super(api, delegate);
        this.emitClinitRegistration = emitClinitRegistration;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name;
        this.superName = superName;
        // System-property opt-in (-Dcrochet.eagerClasses=...) is re-resolved
        // per class (with a cached parse); the annotation check is deferred
        // to visitAnnotation below. Both are "OR" — presence via either is
        // sufficient.
        Set<String> opted = eagerClassInternalNames();
        if (!opted.isEmpty() && opted.contains(name)) {
            this.eagerMode = true;
        }
        // Final classes cannot host a FastProxy (the proxy would need to
        // extend them, which the verifier rejects). Without a proxy the
        // klass-swap mechanism can't trigger {@code fastAccess}, so the
        // lazy snap/restore path never runs. Force eager mode so
        // $$crochetCheckpoint takes the snapshot directly and
        // $$crochetRollback restores it directly. Paper §5.1's replication
        // depends on this: {@link java.util.HashMap$Node},
        // {@link java.util.TreeMap$Entry}, {@link java.util.LinkedHashMap$Entry}
        // and {@link java.util.concurrent.ConcurrentHashMap$Node} are all
        // final — without this forcing, none of them can be rolled back.
        if ((access & Opcodes.ACC_FINAL) != 0) {
            this.eagerMode = true;
        }
        String[] newIfaces = interfaces;
        boolean hasMarker = false;
        if (interfaces != null) {
            for (String i : interfaces) {
                if (INSTRUMENTED.equals(i)) {
                    hasMarker = true;
                    break;
                }
            }
        }
        if (!hasMarker) {
            int n = interfaces == null ? 0 : interfaces.length;
            newIfaces = new String[n + 1];
            if (n > 0) {
                System.arraycopy(interfaces, 0, newIfaces, 0, n);
            }
            newIfaces[n] = INSTRUMENTED;
        } else {
            alreadyInstrumented = true;
        }
        // Bump class-file version to V1_7 (major=51) minimum so our emitted
        // methods carry StackMapTable attributes that the modern verifier
        // expects. Pre-V1_6 class files (major<50) use inference-based
        // verification that doesn't compose with our COMPUTE_FRAMES output;
        // commons-logging ships at V1_1 (major=45) and trips "Illegal type
        // in constant pool" VerifyError without this bump.
        int majorBump = Math.max(version & 0xFFFF, Opcodes.V1_7);
        int versionBumped = (version & 0xFFFF0000) | majorBump;
        super.visit(versionBumped, access, name, signature, superName, newIfaces);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        // {@code @CrochetEager} is RUNTIME-retained, so reader sees it with
        // visible=true. We flip a boolean; the delegate chain still emits the
        // annotation onto the transformed class file so reflective queries
        // post-transform ({@link
        // net.jonbell.crochet.runtime.CheckpointRollbackAgent#isEagerClass})
        // stay consistent.
        if (CROCHET_EAGER_DESC.equals(descriptor)) {
            this.eagerMode = true;
        }
        return super.visitAnnotation(descriptor, visible);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor,
                                   String signature, Object value) {
        if (VERSION_FIELD.equals(name)) {
            hasVersionField = true;
        } else if (SNAP_FIELD.equals(name)) {
            hasSnapField = true;
        } else if ((access & Opcodes.ACC_STATIC) == 0
                && (access & Opcodes.ACC_FINAL) == 0
                && !name.startsWith("$$crochet")) {
            // Skip final instance fields: the JVM rejects PUTFIELD on a
            // final field from any method other than {@code <init>}, and
            // the $$crochetCopyFieldsTo / $$crochetCopyFieldsFrom
            // surfaces we emit live outside the constructor. Final fields
            // are also invariant by design — there is no post-{@code <init>}
            // value change to snapshot, so skipping them is semantically
            // lossless for normal code paths. The only way to mutate a
            // final field is via Unsafe.putX, which bypasses our entire
            // instrumentation machinery anyway; we accept that
            // unreachable-for-rollback corner in exchange for bootstrap
            // correctness on JDK classes that expose final fields
            // ({@code HashMap$Node.hash}, {@code HashMap$Node.key},
            // {@code ConcurrentHashMap$Node.hash}, etc.).
            instanceFields.add(new InstrumentedSurfaceEmitter.FieldRef(name, descriptor));
        }
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        // Prepend the class-init registration call to an existing <clinit>.
        // alreadyInstrumented classes skip the emit (a prior pass already
        // handled it); emitClinitRegistration is false on JDK classes.
        if ("<clinit>".equals(name) && emitClinitRegistration && !alreadyInstrumented) {
            hasClinit = true;
            if (base == null) {
                return null;
            }
            return new ClinitRegistrar(api, base, className);
        }
        return base;
    }

    /**
     * Wraps an existing user {@code <clinit>} to prepend a
     * {@code CheckpointRollbackAgent.registerInitializedClass(ThisClass.class)}
     * call, guarded by a try/catch block that swallows every {@link Throwable}.
     *
     * <p>The try/catch tolerates very-early initialisation paths (packed-
     * runtime path, where CheckpointRollbackAgent's own {@code <clinit>} might
     * still be running when a peer class's {@code <clinit>} fires) and any
     * classloader-specific {@link NoClassDefFoundError} for the runtime
     * facade on restricted loaders.
     */
    private static final class ClinitRegistrar extends MethodVisitor {
        private final String ownerInternal;

        ClinitRegistrar(int api, MethodVisitor mv, String ownerInternal) {
            super(api, mv);
            this.ownerInternal = ownerInternal;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitRegisterCall(mv, ownerInternal);
        }
    }

    /**
     * Emits the registration call plus a try/catch that swallows every
     * {@link Throwable}. Body:
     * <pre>
     *   try {
     *       CheckpointRollbackAgent.registerInitializedClass(ThisClass.class);
     *   } catch (Throwable t) {
     *       // swallow; runtime not yet ready
     *   }
     * </pre>
     *
     * <p>Uses {@code COMPUTE_FRAMES} (the class-level writer already requests
     * it) to emit any required stack-map frames for the catch landing pad.
     */
    static void emitRegisterCall(MethodVisitor mv, String ownerInternal) {
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label after = new Label();
        mv.visitLabel(tryStart);
        mv.visitLdcInsn(Type.getObjectType(ownerInternal));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "registerInitializedClass",
                "(Ljava/lang/Class;)V", false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, after);
        mv.visitLabel(handler);
        // Caught Throwable is on stack; discard.
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(after);
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
    }

    @Override
    public void visitEnd() {
        if (!alreadyInstrumented) {
            // ACC_TRANSIENT is important for two reasons:
            //  (1) hides our fields from Java serialization — we never want
            //      instrumentation bookkeeping to leak into serialized forms
            //      of user objects;
            //  (2) h2o's water.api.Schema.fillFromParms walks every declared
            //      field of every Schema subclass up to water.Iced and
            //      requires each one to carry an @API annotation unless
            //      Modifier.isTransient(field.getModifiers()) is true.
            //      Marking our fields transient makes that reflection pass
            //      skip them (the check is at h2o Schema.java #692, caught
            //      as "Missing annotation for API field: $$crochetSnap").
            // ACC_PRIVATE, not ACC_PUBLIC: JBoss Weld rejects CDI-managed
            // beans with public non-annotated fields
            // ({@code WELD-000075: Normal scoped managed bean implementation
            // class has a public field}), and Weld extensions get a
            // validation warning ({@code WELD-001552}). All read/write sites
            // live inside the emitted $$crochet* methods on the same class,
            // so private visibility is sufficient. Unsafe-based offset
            // access from the agent bypasses access control.
            if (!hasVersionField) {
                super.visitField(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_TRANSIENT,
                        VERSION_FIELD, "I", null, null).visitEnd();
            }
            if (!hasSnapField) {
                super.visitField(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_TRANSIENT,
                        SNAP_FIELD, "Ljava/lang/Object;", null, null).visitEnd();
            }
            // Pass `this` (i.e. the outer ClassVisitor) so emit calls thread
            // through the FieldAdder's own visitMethod -> ClassVisitor.cv
            // delegation chain, identical to the previous super.visitMethod
            // calls inlined into this file.
            InstrumentedSurfaceEmitter.emitCopyFieldsTo(this, className, superName, instanceFields);
            InstrumentedSurfaceEmitter.emitCopyFieldsFrom(this, className, superName, instanceFields);
            emitCheckpoint();
            emitRollback();
            InstrumentedSurfaceEmitter.emitGetVersion(this, className);
            InstrumentedSurfaceEmitter.emitSetVersion(this, className);
            InstrumentedSurfaceEmitter.emitGetSnap(this, className);
            InstrumentedSurfaceEmitter.emitSetSnap(this, className);
            InstrumentedSurfaceEmitter.emitPropagateRefFields(this, className, superName,
                    "$$crochetPropagateCheckpoint", "$$crochetCheckpoint", instanceFields);
            InstrumentedSurfaceEmitter.emitPropagateRefFields(this, className, superName,
                    "$$crochetPropagateRollback", "$$crochetRollback", instanceFields);
            InstrumentedSurfaceEmitter.emitAccessNoop(this);
            InstrumentedSurfaceEmitter.emitIsRollbackStateSentinel(this, className);
            // If the user class has no <clinit> of its own, synthesise a
            // minimal one whose only job is to call
            // {@code CheckpointRollbackAgent.registerInitializedClass(ThisClass.class)}
            // — wrapped in a Throwable catch-all so very-early-boot paths
            // where the agent runtime isn't yet initialised don't crash the
            // caller's <clinit>.
            //
            // Gate on emitClinitRegistration so JDK classes on the minimal
            // pipeline (no user-class wrappers) don't grow a new <clinit> —
            // those classes' {@code <clinit>} can fire during JVM bootstrap
            // before CheckpointRollbackAgent's own class init has run, and
            // rely on the Instrumentation#getAllLoadedClasses() fallback in
            // checkpointAll for discovery instead.
            if (emitClinitRegistration && !hasClinit) {
                emitSynthesizedClinit();
            }
        }
        super.visitEnd();
    }

    private void emitSynthesizedClinit() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "<clinit>", "()V", null, null);
        mv.visitCode();
        emitRegisterCall(mv, className);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitCheckpoint() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCheckpoint", "(I)V", null, null);
        mv.visitCode();
        if (eagerMode) {
            emitEagerVersionGuardedEntry(mv, /*checkpoint=*/true);
        } else {
            emitVersionGuardedEntry(mv);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitRollback() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetRollback", "(I)V", null, null);
        mv.visitCode();
        if (eagerMode) {
            emitEagerVersionGuardedEntry(mv, /*checkpoint=*/false);
        } else {
            emitVersionGuardedEntry(mv);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Eager counterpart to {@link #emitVersionGuardedEntry}. The sentinel-CAS
     * framing is identical (so paper invariants I1, I2, and sentinel-decode
     * semantics continue to hold), but the body does a shallow copy on this
     * instance instead of swapping to a Fast proxy:
     *
     * <pre>
     *   loop:
     *   int cur   = Agent.versionVolatileGet(this, ThisClass.class);
     *   int realV = Math.abs(cur);
     *   if (realV &gt;= v) return;                                         // I2 guard
     *   if (!Agent.versionCas(this, ThisClass.class, cur, -v)) goto loop; // retry
     *   try {
     *       if (checkpoint) {
     *           Object shadow = Agent.allocateShadow(ThisClass.class);
     *           this.$$crochetCopyFieldsTo(shadow);
     *           this.$$crochetSnap = shadow;
     *       } else {
     *           Object snap = this.$$crochetSnap;
     *           if (snap != null) {
     *               this.$$crochetCopyFieldsFrom(snap);
     *               this.$$crochetSnap = null;
     *           }
     *       }
     *   } catch (Throwable t) {
     *       Agent.versionCas(this, ThisClass.class, -v, 0);              // zero version
     *       throw new RollbackException(POISON_VERSION, t);
     *   }
     *   Agent.versionCas(this, ThisClass.class, -v, v);                  // finalize
     * </pre>
     *
     * <p>No klass swap ever happens, so {@code obj.getClass()} remains the
     * original user class — identity-sensitive third-party code sees a
     * stable type. {@code $$crochetAccess} on the user class is the no-op
     * body from {@link InstrumentedSurfaceEmitter#emitAccessNoop}; it never
     * triggers {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#fastAccess}
     * because the klass never transitions to the proxy.
     *
     * <p>Gap-8: the catch block zeroes the version (rather than restoring the
     * prior value) because a thrown path means {@code this.$$crochetSnap} may
     * be partially written; the consistent "no active checkpoint" state
     * needs both the version and the snap to read as clear. The throw is a
     * {@link net.jonbell.crochet.runtime.RollbackException#POISON_VERSION}
     * per paper §3 exception safety.
     *
     * <p>Local layout: slot 0 is {@code this}, slot 1 is {@code v}, slot 2 is
     * {@code cur}, slot 3 is {@code realV}, slot 4 is the caught throwable,
     * slot 5 is the shadow / snap reference.
     */
    private void emitEagerVersionGuardedEntry(MethodVisitor mv, boolean checkpoint) {
        Label loopHead     = new Label();
        Label proceed      = new Label();
        Label gotSentinel  = new Label();
        Label tryStart     = new Label();
        Label tryEnd       = new Label();
        Label handler      = new Label();
        Label afterHandler = new Label();

        // ---- cur = Agent.versionVolatileGet(this, ThisClass.class)
        mv.visitLabel(loopHead);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "versionVolatileGet",
                "(Ljava/lang/Object;Ljava/lang/Class;)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 2);

        // ---- realV = Math.abs(cur)
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs",
                "(I)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        // ---- if (realV >= v) return;
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, proceed);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(proceed);

        // ---- if (!Agent.versionCas(this, ThisClass.class, cur, -v)) retry;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.INEG);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "versionCas",
                "(Ljava/lang/Object;Ljava/lang/Class;II)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, gotSentinel);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);                    // retry: re-read cur
        mv.visitLabel(gotSentinel);

        // ---- try { ... eager body ... }
        mv.visitLabel(tryStart);
        if (checkpoint) {
            // Object shadow = Agent.allocateShadow(ThisClass.class);
            mv.visitLdcInsn(Type.getObjectType(className));
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "allocateShadow",
                    "(Ljava/lang/Class;)Ljava/lang/Object;", false);
            mv.visitVarInsn(Opcodes.ASTORE, 5);
            // this.$$crochetCopyFieldsTo(shadow);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className,
                    "$$crochetCopyFieldsTo", "(Ljava/lang/Object;)V", false);
            // this.$$crochetSnap = shadow;
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, SNAP_FIELD,
                    "Ljava/lang/Object;");
        } else {
            // Object snap = this.$$crochetSnap;
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, className, SNAP_FIELD,
                    "Ljava/lang/Object;");
            mv.visitVarInsn(Opcodes.ASTORE, 5);
            // if (snap != null) { this.$$crochetCopyFieldsFrom(snap); this.$$crochetSnap = null; }
            Label snapNull = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitJumpInsn(Opcodes.IFNULL, snapNull);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className,
                    "$$crochetCopyFieldsFrom", "(Ljava/lang/Object;)V", false);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, SNAP_FIELD,
                    "Ljava/lang/Object;");
            mv.visitLabel(snapNull);
        }
        // Eager propagation: classes in eager mode (either opted-in via
        // {@code @CrochetEager} / {@code -Dcrochet.eagerClasses}, or
        // forced by {@link #isUnproxyable(Class)} for final classes like
        // {@link java.util.TreeMap$Entry} and {@link java.util.HashMap$Node}
        // that cannot host a FastProxy) must propagate to their reference
        // children directly from inside {@code $$crochetCheckpoint} /
        // {@code $$crochetRollback}, because there is no klass-swap-triggered
        // fastAccess path to take over the work lazily. Without this call,
        // nested state ({@code Entry.left}, {@code Entry.right}, etc.) is
        // never snapshotted and rollback can only restore the direct object.
        //
        // Routed through {@link CheckpointRollbackAgent#propagate} (a
        // thread-local iterative drain) so that deep reference chains
        // (linked lists, tree spines — Lucene's DocumentsWriterDeleteQueue
        // is the motivating case) don't recurse the JVM stack into an SOE.
        // Reentrant eager-eager nesting just enqueues; the outer drain
        // pops and runs each propagate iteratively.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(checkpoint ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "propagate",
                "(Ljava/lang/Object;IZ)V", false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, afterHandler);

        // ---- catch (Throwable t):
        //      Agent.versionCas(this, ThisClass.class, -v, 0);  // clear version
        //      throw new RollbackException(POISON_VERSION, t);
        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.INEG);                                  // expect = -v
        mv.visitInsn(Opcodes.ICONST_0);                              // update = 0
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "versionCas",
                "(Ljava/lang/Object;Ljava/lang/Class;II)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, "net/jonbell/crochet/runtime/RollbackException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(-1);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "net/jonbell/crochet/runtime/RollbackException",
                "<init>", "(ILjava/lang/Throwable;)V", false);
        mv.visitInsn(Opcodes.ATHROW);

        // ---- finalize: Agent.versionCas(this, ThisClass.class, -v, v); return;
        mv.visitLabel(afterHandler);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.INEG);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "versionCas",
                "(Ljava/lang/Object;Ljava/lang/Class;II)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
    }
}
