package net.jonbell.crochet.transform;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Emits a sentinel-aware body for {@code $$crochetCheckpoint} /
     * {@code $$crochetRollback} per paper Listing 3:
     * <pre>
     *   int cur   = Agent.versionVolatileGet(this, ThisClass.class);
     *   int realV = (cur &lt; 0) ? -cur : cur;
     *   if (realV &gt;= v) return;                                        // I2 guard
     *   if (!Agent.versionCas(this, ThisClass.class, cur, -v)) return;   // peer won
     *   try {
     *       Agent.swapToFastProxy(this, ThisClass.class);
     *   } catch (Throwable t) {
     *       Agent.versionCas(this, ThisClass.class, -v, cur);            // best-effort restore
     *       throw new RollbackException(POISON_VERSION, t);
     *   }
     *   Agent.versionCas(this, ThisClass.class, -v, v);                  // finalize
     * </pre>
     *
     * <p>Sentinel value {@code -v} closes the "version bumped but klass not
     * yet swapped" race window: a concurrent reader that observes {@code -v}
     * decodes {@code realV = v} and sees the same parity/branch decision it
     * would observe after the finalize. All CAS failures are benign — they
     * mean a peer advanced past us, so paper invariants I1 (unique v) and I2
     * (monotone) continue to hold.
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
        Label proceed      = new Label();
        Label gotSentinel  = new Label();
        Label tryStart     = new Label();
        Label tryEnd       = new Label();
        Label handler      = new Label();
        Label afterHandler = new Label();

        // ---- cur = Agent.versionVolatileGet(this, ThisClass.class)
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

        // ---- if (!Agent.versionCas(this, ThisClass.class, cur, -v)) return;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(className));
        mv.visitVarInsn(Opcodes.ILOAD, 2);                          // expect = cur
        mv.visitVarInsn(Opcodes.ILOAD, 1);                          // v
        mv.visitInsn(Opcodes.INEG);                                  // -v
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "versionCas",
                "(Ljava/lang/Object;Ljava/lang/Class;II)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, gotSentinel);
        mv.visitInsn(Opcodes.RETURN);
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
    private final List<InstrumentedSurfaceEmitter.FieldRef> instanceFields = new ArrayList<>();
    private boolean alreadyInstrumented;
    private boolean hasVersionField;
    private boolean hasSnapField;

    public FieldAdder(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name;
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
    public FieldVisitor visitField(int access, String name, String descriptor,
                                   String signature, Object value) {
        if (VERSION_FIELD.equals(name)) {
            hasVersionField = true;
        } else if (SNAP_FIELD.equals(name)) {
            hasSnapField = true;
        } else if ((access & Opcodes.ACC_STATIC) == 0
                && !name.startsWith("$$crochet")) {
            instanceFields.add(new InstrumentedSurfaceEmitter.FieldRef(name, descriptor));
        }
        return super.visitField(access, name, descriptor, signature, value);
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
            InstrumentedSurfaceEmitter.emitCopyFieldsTo(this, className, instanceFields);
            InstrumentedSurfaceEmitter.emitCopyFieldsFrom(this, className, instanceFields);
            emitCheckpoint();
            emitRollback();
            InstrumentedSurfaceEmitter.emitGetVersion(this, className);
            InstrumentedSurfaceEmitter.emitSetVersion(this, className);
            InstrumentedSurfaceEmitter.emitGetSnap(this, className);
            InstrumentedSurfaceEmitter.emitSetSnap(this, className);
            InstrumentedSurfaceEmitter.emitPropagateRefFields(this, className,
                    "$$crochetPropagateCheckpoint", "$$crochetCheckpoint", instanceFields);
            InstrumentedSurfaceEmitter.emitPropagateRefFields(this, className,
                    "$$crochetPropagateRollback", "$$crochetRollback", instanceFields);
            InstrumentedSurfaceEmitter.emitAccessNoop(this);
            InstrumentedSurfaceEmitter.emitIsRollbackStateSentinel(this, className);
        }
        super.visitEnd();
    }

    private void emitCheckpoint() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetCheckpoint", "(I)V", null, null);
        mv.visitCode();
        emitVersionGuardedEntry(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitRollback() {
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "$$crochetRollback", "(I)V", null, null);
        mv.visitCode();
        emitVersionGuardedEntry(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
