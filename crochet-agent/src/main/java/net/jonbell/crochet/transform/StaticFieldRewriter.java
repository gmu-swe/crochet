package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Gap 3 (bytecode): inserts a runtime registration pre-hook before every
 * GETSTATIC / PUTSTATIC targeting an instrumented user class.
 *
 * <p>Pre-hook emits {@code sfHelperFor(owner.class).$$crochetAccess()} — net
 * stack delta of zero (1-slot helper ref pushed then consumed). Works without
 * any scratch local, so this visitor doesn't need the
 * {@link SharedLocalsProvider}. PUTSTATIC's existing 1- or 2-slot value sits
 * below the transient helper-ref and stays intact for the wrapped PUTSTATIC.
 *
 * <p>{@code <clinit>} takes the full skip to avoid recursion into the static
 * rewriter via our own $$crochet* field initialisers. {@code <init>}
 * forwards pre-super instructions unchanged via {@link CtorAwareMv} — the
 * pre-hook only fires post-super so instance construction doesn't pay
 * noteStaticAccess for statics referenced from super-call arguments.
 */
public final class StaticFieldRewriter extends ClassVisitor {

    /**
     * Internal name of the bootstrap-safe forwarder. Pre-hooks are
     * emitted as {@code INVOKESTATIC RuntimeReady.noteStaticAccess(Class)V}
     * rather than directly calling {@link CheckpointRollbackAgent} so
     * that early-bootstrap invocations (from instrumented JDK classes
     * like {@code HashMap} used inside the JVM startup sequence itself)
     * see {@code RuntimeReady.READY == false} and return immediately,
     * without triggering class-init of the full agent-runtime
     * dependency closure. The forwarder is a single volatile-load +
     * branch on the hot path; at steady state HotSpot predicts the
     * taken branch and the gate is essentially free.
     */
    private static final String RUNTIME_READY_INTERNAL = "net/jonbell/crochet/runtime/RuntimeReady";
    /**
     * Descriptor of the fused pre-hook. The agent-side implementation does the
     * {@code sfHelperFor(C).$$crochetAccess()} job in a single static call so
     * the JIT can inline the fast path; previously the emitted two-call
     * pattern ({@code INVOKESTATIC sfHelperFor} + {@code INVOKEINTERFACE
     * $$crochetAccess}) forced the JIT to itable-lookup through an open
     * polymorphic world of generated SF-helper classes, which dominated
     * WildFly startup (see Logger$Level @ 8.3M hits in
     * {@code /tmp/crochet-runtime-counts.log}).
     */
    private static final String NOTE_STATIC_ACCESS_DESC = "(Ljava/lang/Class;)V";

    private String className;
    private String superName;
    private final ClassLoader loader;

    public StaticFieldRewriter(int api, ClassVisitor delegate) {
        this(api, delegate, null);
    }

    /**
     * @param loader the caller's class loader, used by
     *        {@link StaticFieldAnalysis} to probe owner classes for
     *        mutable static fields. May be {@code null} (boot loader).
     */
    public StaticFieldRewriter(int api, ClassVisitor delegate, ClassLoader loader) {
        super(api, delegate);
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
        return new WrapStaticsMV(api, base, className, superName, isCtor, loader);
    }

    private static final class WrapStaticsMV extends CtorAwareMv {
        private final ClassLoader loader;

        WrapStaticsMV(int api, MethodVisitor delegate, String owner, String superName,
                      boolean isCtor, ClassLoader loader) {
            super(api, delegate, owner, superName, isCtor);
            this.loader = loader;
        }

        /**
         * GETSTATIC/PUTSTATIC both handled without any scratch local —
         * invokestatic pushes a 1-slot helper reference, invokevirtual
         * consumes it. The original stack shape underneath is preserved,
         * so any existing 2-slot value for PUTSTATIC stays intact.
         */
        @Override
        protected void visitFieldInsnPostSuper(int opcode, String owner, String name, String descriptor) {
            if (!shouldWrap(opcode, owner, name, loader)) {
                super.visitFieldInsnPostSuper(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.GETSTATIC) {
                // stack: [...]
                emitPreHook(owner);
                // stack: [...]
                mv.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.PUTSTATIC) {
                // stack: [..., value]  (value is 1 or 2 slots, doesn't matter)
                // Each emitted call pushes then pops exactly one slot, so
                // value is untouched at the bottom when we hit PUTSTATIC.
                emitPreHook(owner);
                // stack: [..., value]
                mv.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            super.visitFieldInsnPostSuper(opcode, owner, name, descriptor);
        }

        private void emitPreHook(String owner) {
            mv.visitLdcInsn(Type.getObjectType(owner));
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_READY_INTERNAL,
                    "noteStaticAccess", NOTE_STATIC_ACCESS_DESC, false);
        }

        private static boolean shouldWrap(int opcode, String owner, String name, ClassLoader loader) {
            if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.PUTSTATIC) {
                return false;
            }
            if (owner == null || name == null) {
                return false;
            }
            // Gap 7 note: JDK classes participate in the FIELD and ARRAY
            // wrapper chains (so user-object traversal into JDK-owned
            // reference state is captured on snapshot). STATIC state on
            // java/*, jdk/*, sun/*, com/sun/* owners is intentionally out
            // of scope — it's mostly singletons / caches the paper never
            // intends to snapshot, and the emitted pre-hook would
            // recursively call back through instrumented JDK methods
            // during bootstrap (see RuntimeReady's javadoc for the
            // specific reentry: allowSecurityManager's GETSTATIC →
            // noteStaticAccess → ClassMeta.of → ClassValue.get → first
            // load of ClassValueMap → transformer → same owner).
            if (owner.startsWith("java/") || owner.startsWith("jdk/")
                    || owner.startsWith("sun/") || owner.startsWith("com/sun/")) {
                return false;
            }
            if (owner.startsWith("net/jonbell/crochet/")) {
                // Our own runtime must never recurse — the pre-hook body
                // lives inside it.
                return false;
            }
            if (name.startsWith("$$crochet")) {
                return false;
            }
            // Transform-time elision: if the owner class declares no non-final,
            // non-synthetic, non-$$crochet static fields, the pre-hook has
            // nothing to track — checkpointStatics already skips final/synthetic
            // fields during its reflective scan, so the hook's materialisation
            // would be a no-op at runtime. Skip the whole wrap emit.
            //
            // The probe is a cached resource-stream read of the owner's class
            // file (StaticFieldAnalysis). Misses are a one-time cost; hits are
            // lock-free. A probe that cannot resolve the class returns "mutable"
            // conservatively, so unresolved classes still get the wrap.
            if (!StaticFieldAnalysis.ownerMayHaveMutableStatics(owner, loader)) {
                return false;
            }
            return true;
        }
    }
}
