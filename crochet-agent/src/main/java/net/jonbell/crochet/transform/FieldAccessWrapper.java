package net.jonbell.crochet.transform;

import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Wraps GETFIELD/PUTFIELD instructions whose owner is an instrumented user
 * class with a preceding call to {@code ownerRef.$$crochetAccess()}. When the
 * ownerRef is of a user-class type this runs the no-op base implementation;
 * when the ownerRef's klass has been swapped to the Fast proxy, this triggers
 * the lazy snapshot/restore in
 * {@link net.jonbell.crochet.runtime.CheckpointRollbackAgent#fastAccess}.
 *
 * <p>1-slot (Z, B, C, S, I, F, L, [) descriptors wrap via pure SWAP/DUP/SWAP.
 * 2-slot (J, D) descriptors stash the value in a scratch local allocated from
 * the chain-wide {@link SharedLocalsProvider} — the visitor never extends a
 * {@link org.objectweb.asm.commons.LocalVariablesSorter} of its own; multiple
 * stacked LVS instances produced cumulative index rewrites that confused
 * {@code COMPUTE_FRAMES} on large methods (fop's FObj, h2's Parser).
 *
 * <p>See {@link WrapAccessesMV#emitPreHook} for the emit-shape design
 * rationale (INVOKEVIRTUAL vs INVOKESTATIC-with-instanceof trade-off).
 *
 * <p><b>Skipped-owner guard (gmu-swe/crochet#5)</b>: {@link CrochetTransformer}
 * skips classes flagged {@code ACC_ENUM} / {@code ACC_INTERFACE} /
 * {@code ACC_ANNOTATION} / {@code ACC_MODULE} (and direct enum-constant
 * subclasses whose super is {@code java/lang/Enum}) because the JVM rejects
 * instance-field/method injection on those forms. But field reads/writes on a
 * receiver of one of those static types still get wrapped here — without care,
 * the emitted {@code INVOKEVIRTUAL fOwner.$$crochetAccess()V} fails to link at
 * runtime ({@code NoSuchMethodError}). When {@code fOwner} resolves to one of
 * those skipped forms via {@link #ownerIsSuspicious}, we emit the guarded
 * form instead: {@code DUP / INSTANCEOF CRIJInstrumented / IFEQ skip /
 * INVOKEINTERFACE $$crochetAccess / GOTO end / skip: POP / end:}. The guard is
 * elided for all other owners to preserve the fast path (see the perf trade-
 * off note on {@link WrapAccessesMV#emitPreHook}).
 *
 * <p>Resolution uses {@link ClassLoader#getResourceAsStream} to read the
 * target class file's modifier bits without triggering a recursive
 * {@link Class#forName} from inside the transformer chain. {@code forName}
 * during transform either bypasses our own transformer for the recursively-
 * loaded inner class (so the inner class never gets {@code $$crochetAccess}
 * injected — which then breaks every subsequent field access on it) or
 * deadlocks on the loader monitor.
 *
 * <p>{@code <clinit>} takes the full skip — static initialisers are special
 * because our own {@code $$crochet*} field initialisers would recurse into
 * the static rewriter. {@code <init>} is handled via
 * {@link CtorAwareMv}: pre-super instructions forward unchanged (we cannot
 * read fields of {@code this} before the super-call), post-super instructions
 * are wrapped the same way as any ordinary method.
 */
public final class FieldAccessWrapper extends ClassVisitor {

    /**
     * Internal name of the marker interface whose instances accept
     * {@code $$crochetAccess()}. Used by the guarded emit shape below and by
     * the {@link #ownerIsSuspicious} resolver to decide when to emit it.
     */
    static final String INSTRUMENTED_INTERNAL = "net/jonbell/crochet/runtime/CRIJInstrumented";

    /**
     * Name of the F.1 dirty-bit field injected by {@link FieldAdder}. The PUTFIELD
     * pre-hook sets this to {@code 1} on the receiver <em>before</em> calling
     * {@code $$crochetAccess()} so that any concurrent {@code fastAccess} call that
     * reads the dirty-bit under the stripe lock observes {@code dirty == 1} and
     * materializes a shadow rather than incorrectly skipping.
     */
    static final String DIRTY_FIELD = FieldAdder.DIRTY_FIELD;

    /**
     * Per-owner-name cache of "is this fOwner a type that cannot host an
     * instance {@code $$crochetAccess} method?" (i.e. interface / enum /
     * annotation / module / direct enum-constant subclass). Populated by
     * {@link #ownerIsSuspicious}; keyed by internal name only because the
     * answer doesn't change with loader (the class file's own modifier bits
     * are the source of truth). Entries are {@code Boolean.TRUE} (skipped
     * form — emit the guard), {@code Boolean.FALSE} (ordinary class — emit
     * the direct {@code INVOKEVIRTUAL}), or absent (not yet resolved — try
     * again next call). We never cache a "lookup failed" outcome because a
     * later transform-time call may see the class loaded in a different
     * loader.
     */
    private static final ConcurrentHashMap<String, Boolean> OWNER_SUSPECT_CACHE =
            new ConcurrentHashMap<>();

    private final SharedLocalsProvider locals;
    private final ClassLoader loader;
    private String className;
    private String superName;

    public FieldAccessWrapper(int api, ClassVisitor delegate, SharedLocalsProvider locals) {
        this(api, delegate, locals, null);
    }

    public FieldAccessWrapper(int api, ClassVisitor delegate, SharedLocalsProvider locals,
                              ClassLoader loader) {
        super(api, delegate);
        this.locals = locals;
        this.loader = loader;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name;
        this.superName = superName;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base == null) {
            return null;
        }
        if (name.startsWith("$$crochet") || "<clinit>".equals(name)) {
            return base;
        }
        boolean isCtor = "<init>".equals(name);
        return new WrapAccessesMV(api, base, locals, className, superName, isCtor, loader);
    }

    /**
     * Returns {@code true} when {@code fOwner} is statically known to be a
     * type {@link CrochetTransformer#transform} skips — i.e. it cannot carry
     * an instance {@code $$crochetAccess} method.
     *
     * <p>Resolution reads the class file via
     * {@link ClassLoader#getResourceAsStream} and parses only the access
     * flags + super name (no class loading). Critically, this avoids
     * {@link Class#forName}: calling {@code Class.forName} from inside our
     * own {@code ClassFileTransformer} chain triggers a recursive load of
     * the target class. The JVM detects the reentrancy and either bypasses
     * our transformer for the inner load (so the inner class never gets
     * {@code $$crochetAccess} injected) or deadlocks on the loader monitor.
     * Resource-stream parsing reads the same on-disk class bytes that the
     * loader would later hand to our transformer, but doesn't materialise
     * the class.
     *
     * <p>The classification mirrors {@link CrochetTransformer#transform}'s
     * skip flags: {@code ACC_INTERFACE}, {@code ACC_ENUM},
     * {@code ACC_ANNOTATION}, {@code ACC_MODULE}, plus the
     * "extends java/lang/Enum directly" form for enum-constant subclasses.
     * Abstract classes are NOT included — the transformer happily injects
     * {@code $$crochetAccess} into abstract user classes (their concrete
     * subclasses inherit it, and any direct field access on an abstract
     * receiver's instance fields is well-defined).
     *
     * <p>If the class file isn't resolvable through any loader we can see
     * (e.g. it's a runtime-defined hidden class with no {@code .class}
     * resource), we return {@code false} — the direct {@code INVOKEVIRTUAL}
     * is retained, matching the pre-fix behaviour. The cache populates on
     * the first successful lookup so subsequent callers benefit; we never
     * cache a "lookup failed" outcome because a later loader may make the
     * class file visible.
     */
    static boolean ownerIsSuspicious(ClassLoader loader, String fOwner) {
        if (fOwner == null) {
            return false;
        }
        // Our own runtime classes never appear as fOwner here (shouldWrap
        // excludes them). java.lang.Object cannot be the field owner of a
        // GETFIELD/PUTFIELD anyway. Skip cheap obviously-not-suspect prefixes
        // before paying for the resource lookup.
        if (fOwner.startsWith("net/jonbell/crochet/")) {
            return false;
        }
        Boolean cached = OWNER_SUSPECT_CACHE.get(fOwner);
        if (cached != null) {
            return cached.booleanValue();
        }
        Boolean resolved = resolveSuspicious(loader, fOwner);
        if (resolved != null) {
            OWNER_SUSPECT_CACHE.putIfAbsent(fOwner, resolved);
            return resolved.booleanValue();
        }
        return false;
    }

    private static Boolean resolveSuspicious(ClassLoader loader, String fOwner) {
        String resource = fOwner + ".class";
        ClassLoader effective = loader != null ? loader
                : FieldAccessWrapper.class.getClassLoader();
        for (ClassLoader l = effective; l != null; l = l.getParent()) {
            Boolean r = readSuspectFlags(l, resource);
            if (r != null) {
                return r;
            }
        }
        // Fall back to the system classloader for boot-loaded classes that
        // don't show up via the agent's loader chain.
        Boolean r = readSuspectFlags(null, resource);
        return r;
    }

    /** Descriptor of the {@code @CrochetSkip} annotation. */
    private static final String CROCHET_SKIP_DESC =
            "Lnet/jonbell/crochet/annotation/CrochetSkip;";

    private static Boolean readSuspectFlags(ClassLoader l, String resource) {
        try (java.io.InputStream in = (l != null
                ? l.getResourceAsStream(resource)
                : ClassLoader.getSystemResourceAsStream(resource))) {
            if (in == null) {
                return null;
            }
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(in);
            int access = reader.getAccess();
            if ((access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM
                    | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) != 0) {
                return Boolean.TRUE;
            }
            String superName = reader.getSuperName();
            if ("java/lang/Enum".equals(superName)) {
                return Boolean.TRUE;
            }
            // @CrochetSkip: classes annotated with this opt out of Crochet
            // instrumentation, so they won't have a $$crochetAccess() method.
            // Emit the guarded form (INSTANCEOF CRIJInstrumented + IFEQ skip)
            // instead of a direct INVOKEVIRTUAL that would fail to link.
            if (hasAnnotation(reader, CROCHET_SKIP_DESC)) {
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (java.io.IOException ignored) {
            return null;
        } catch (Throwable t) {
            // A malformed class file or a broken classloader resource lookup
            // shouldn't abort the whole transform. Fall back to the
            // INVOKEVIRTUAL fast path.
            return null;
        }
    }

    /**
     * Return {@code true} iff the class file read by {@code reader} carries
     * the named annotation descriptor in its {@code RuntimeVisibleAnnotations}
     * attribute.
     */
    private static boolean hasAnnotation(org.objectweb.asm.ClassReader reader, String desc) {
        final boolean[] found = {false};
        reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                    String descriptor, boolean visible) {
                if (desc.equals(descriptor)) {
                    found[0] = true;
                }
                return null;
            }
        }, org.objectweb.asm.ClassReader.SKIP_CODE
                | org.objectweb.asm.ClassReader.SKIP_DEBUG
                | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static final class WrapAccessesMV extends CtorAwareMv {
        private final SharedLocalsProvider locals;
        private final ClassLoader loader;

        WrapAccessesMV(int api, MethodVisitor delegate, SharedLocalsProvider locals,
                       String owner, String superName, boolean isCtor, ClassLoader loader) {
            super(api, delegate, owner, superName, isCtor);
            this.locals = locals;
            this.loader = loader;
        }

        /**
         * Emit the pre-hook for a GETFIELD/PUTFIELD receiver currently on
         * top of stack.
         *
         * <p><b>Fast path (ordinary user class {@code fOwner})</b>:
         * emit {@code INVOKEVIRTUAL owner.$$crochetAccess()V} directly. This
         * is the original shape and preserves the JIT devirtualisation the
         * rest of this javadoc argues for.
         *
         * <p><b>Suspicious-owner path ({@code fOwner} is interface/enum/
         * annotation/module)</b>: these are types {@link CrochetTransformer}
         * skips — they cannot carry an instance {@code $$crochetAccess}
         * method, so a direct {@code INVOKEVIRTUAL} fails to link at runtime
         * with {@code NoSuchMethodError} (gmu-swe/crochet#5). For these
         * owners we emit a guarded form:
         *
         * <pre>
         *   DUP
         *   INSTANCEOF CRIJInstrumented
         *   IFEQ skipLabel
         *   INVOKEINTERFACE CRIJInstrumented.$$crochetAccess()V
         *   GOTO endLabel
         *   skipLabel:
         *   POP
         *   endLabel:
         * </pre>
         *
         * The guard costs a handful of extra instructions + a secondary-super
         * check per emit site — the same cost {@link javadoc} below warns
         * about — but only on receiver types whose static type already
         * couldn't host the direct method. The ordinary-class fast path is
         * unchanged. See {@link FieldAccessWrapper#ownerIsSuspicious} for the
         * classification predicate.
         *
         * <p>The caller is responsible for emitting the site-level gate
         * check around this call (see {@link #emitGatePrefix} below).
         * This keeps the {@code INVOKEVIRTUAL} dispatch — which JIT
         * profile-guided devirtualization collapses to the single-RETURN
         * NOOP body on all not-yet-checkpointed instances (the dominant
         * case) — while the outer {@code GETSTATIC VERSION_GATE + IFEQ}
         * gate skips the dispatch entirely in interpreter + C1 tiers.
         * Together those two combine for paper-target overhead (median
         * 1.04x on DaCapo); see BENCHMARK.md §10.
         *
         * <p><b>Alternative evaluated and reverted (orthogonal to the
         * site-level gate above):</b> a klass-guarded static call of shape
         * {@code INVOKESTATIC CheckpointRollbackAgent.fastAccessIfProxy(Object)V},
         * whose body would be
         * {@code if (obj instanceof CRIJFast) fastAccess((CRIJInstrumented) obj);}.
         * The intent was to avoid vtable dispatch for the common case
         * where the receiver's klass has not been swapped to a Fast proxy.
         *
         * <p>Direct measurement (graphchi/lusearch/h2 @ -s small -n 3, 5-10
         * runs each) showed the static-call variant was, on this hardware,
         * either slightly faster (graphchi, lusearch, within ~5% noise
         * band) or a net regression (h2, ~20-40% slower across multiple
         * 5-run and 10-run trials). The INVOKEVIRTUAL path benefits from
         * the JIT's profile-guided devirtualization of
         * {@code $$crochetAccess} on monomorphic-to-user-class sites — the
         * user class's {@code $$crochetAccess} body is a single RETURN, so
         * after the JIT inlines it the site costs ~0 cycles. An INSTANCEOF
         * {@code CRIJFast} check inside a static callee always pays a
         * secondary-super-cache check, which in h2's many-class workload
         * is measurably more expensive than the inlined no-op. The
         * static-call variant also did not measurably help graphchi once
         * run-to-run variance was controlled for (10-iteration medians on
         * both approaches land within 1σ of each other).
         *
         * <p>The alternative "inline class-check" emit
         * ({@code DUP / INVOKEVIRTUAL Object.getClass() / INVOKESTATIC
         * CRIJFast.isProxy / IFEQ / ...}) was evaluated conceptually but
         * inflates per-site bytecode by ~6 instructions + a stackmap
         * frame — expensive for tradebeans-class-heavy workloads with
         * &gt;10k emit sites. Not implemented globally, only on the narrow
         * suspicious-owner path above.
         */
        private void emitPreHook(MethodVisitor mv, String fOwner) {
            if (ownerIsSuspicious(loader, fOwner)) {
                // Guarded form: the caller already DUPed the receiver, so
                // on entry the stack top is the receiver (the extra copy
                // we'll consume). See class javadoc for the exact shape.
                Label skip = new Label();
                Label after = new Label();
                mv.visitInsn(Opcodes.DUP);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, INSTRUMENTED_INTERNAL);
                mv.visitJumpInsn(Opcodes.IFEQ, skip);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, INSTRUMENTED_INTERNAL,
                        "$$crochetAccess", "()V", true);
                mv.visitJumpInsn(Opcodes.GOTO, after);
                mv.visitLabel(skip);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(after);
                return;
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, fOwner,
                    "$$crochetAccess", "()V", false);
        }

        /**
         * F.1: emit the dirty-bit set for a PUTFIELD receiver.
         *
         * <p>On entry the stack top is the receiver reference (1 copy — we will
         * consume it). On exit the stack top is consumed and nothing is pushed.
         * The caller must have already DUPed the receiver before this call so
         * that another copy remains for the subsequent {@link #emitPreHook} call.
         *
         * <p>Emits:
         * <pre>
         *   INVOKESTATIC CheckpointRollbackAgent.noteDirty(Ljava/lang/Object;)V
         * </pre>
         *
         * which sets {@code $$crochetDirty = 1} on the receiver via its
         * per-class VarHandle, tolerating null and pre-F.1 classes. The
         * INVOKESTATIC is cheaper than the inline INSTANCEOF + PUTFIELD
         * alternative because the noteDirty body is a simple null-check +
         * VarHandle.set, JIT-inlined to ~4 instructions on the hot path after
         * the class loader resolves the VersionHandles.dirty handle.
         *
         * <p>Timing: this fires BEFORE {@link #emitPreHook}, establishing
         * the pre-hook timing invariant: "dirty==1 before any concurrent
         * fastAccess can observe the object" (SOUNDNESS.md §5).
         */
        private static void emitDirtySet(MethodVisitor mv) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "net/jonbell/crochet/runtime/CheckpointRollbackAgent",
                    "noteDirty", "(Ljava/lang/Object;)V", false);
        }

        /**
         * Emit the {@code GETSTATIC VERSION_GATE; IFEQ skip} prefix. Caller
         * supplies the {@code skip} label and emits the pre-hook body between
         * it and the label.
         */
        private static void emitGatePrefix(MethodVisitor mv, Label skip) {
            mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "net/jonbell/crochet/runtime/RuntimeReady",
                    "VERSION_GATE", "I");
            mv.visitJumpInsn(Opcodes.IFEQ, skip);
        }

        @Override
        protected void visitFieldInsnPostSuper(int opcode, String fOwner, String name, String descriptor) {
            if (!shouldWrap(opcode, fOwner, descriptor)) {
                super.visitFieldInsnPostSuper(opcode, fOwner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.GETFIELD) {
                // stack: [..., objref]
                Label skip = new Label();
                emitGatePrefix(mv, skip);
                // stack: [..., objref]   (gate was popped by IFEQ)
                mv.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                emitPreHook(mv, fOwner);
                // stack: [..., objref]
                mv.visitLabel(skip);
                // stack: [..., objref] on both paths — fall through to GETFIELD
                mv.visitFieldInsn(opcode, fOwner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.PUTFIELD) {
                boolean twoSlot = "J".equals(descriptor) || "D".equals(descriptor);
                Label skip = new Label();
                if (!twoSlot) {
                    // stack: [..., objref, value]
                    emitGatePrefix(mv, skip);
                    // stack: [..., objref, value]
                    mv.visitInsn(Opcodes.SWAP);
                    // stack: [..., value, objref]
                    mv.visitInsn(Opcodes.DUP);
                    // stack: [..., value, objref, objref]
                    // F.1: set dirty bit BEFORE calling $$crochetAccess so that any
                    // concurrent fastAccess observes dirty==1 and materializes a shadow
                    // (SOUNDNESS.md §5: pre-hook timing invariant).
                    emitDirtySet(mv);
                    // stack: [..., value, objref]
                    mv.visitInsn(Opcodes.DUP);
                    // stack: [..., value, objref, objref]
                    emitPreHook(mv, fOwner);
                    // stack: [..., value, objref]
                    mv.visitInsn(Opcodes.SWAP);
                    // stack: [..., objref, value]
                    mv.visitLabel(skip);
                    // Both paths converge with stack [..., objref, value].
                    mv.visitFieldInsn(opcode, fOwner, name, descriptor);
                    return;
                }
                // 2-slot PUTFIELD: stash the wide value in a scratch local
                // pulled from the chain-wide LVS on the hook path.
                Type vt = "J".equals(descriptor) ? Type.LONG_TYPE : Type.DOUBLE_TYPE;
                int slot = locals.sharedScratch(vt);
                int storeOp = vt.getOpcode(Opcodes.ISTORE);
                int loadOp = vt.getOpcode(Opcodes.ILOAD);
                // stack: [..., objref, v_hi, v_lo]
                emitGatePrefix(mv, skip);
                // stack: [..., objref, v_hi, v_lo]
                locals.emitVarInsn(storeOp, slot);
                // stack: [..., objref]
                mv.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                // F.1: set dirty bit BEFORE calling $$crochetAccess.
                emitDirtySet(mv);
                // stack: [..., objref]
                mv.visitInsn(Opcodes.DUP);
                // stack: [..., objref, objref]
                emitPreHook(mv, fOwner);
                // stack: [..., objref]
                locals.emitVarInsn(loadOp, slot);
                // stack: [..., objref, v_hi, v_lo]
                mv.visitLabel(skip);
                // Both paths converge with stack [..., objref, v_hi, v_lo].
                mv.visitFieldInsn(opcode, fOwner, name, descriptor);
                return;
            }
            // GETSTATIC/PUTSTATIC filtered out by shouldWrap; defensive pass-through.
            super.visitFieldInsnPostSuper(opcode, fOwner, name, descriptor);
        }

        private static boolean shouldWrap(int opcode, String owner, String descriptor) {
            if (opcode != Opcodes.GETFIELD && opcode != Opcodes.PUTFIELD) {
                return false;
            }
            if (owner == null) {
                return false;
            }
            // Gap 7 / paper-level correctness: JDK classes participate in
            // the field-wrap chain so HashMap's internal {@code this.size++},
            // {@code this.table = newTable}, etc. fire the
            // {@code $$crochetAccess} pre-hook, which lets {@code fastAccess}
            // snapshot the receiver before the write. Without this wrap,
            // checkpoint/rollback on JDK collections could not recover the
            // pre-mutation state — demo scenario 15-hashmap-instrumented
            // and paper §5.1 rely on it.
            //
            // Bootstrap safety: the emitted pre-hook is
            // {@code INVOKEVIRTUAL owner.$$crochetAccess()V}. Until a class's
            // first checkpoint ever fires, every instance's klass is the
            // stock user class whose {@code $$crochetAccess} body is a
            // single RETURN (emitted by InstrumentedSurfaceEmitter.emitAccessNoop),
            // so this is a no-op at JDK-bootstrap time.
            if (owner.startsWith("net/jonbell/crochet/")) {
                return false;
            }
            return true;
        }
    }
}
