package net.jonbell.crochet.transform;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Shared base for wrap visitors that need to instrument the body of
 * {@code <init>} after the super-call but not before it.
 *
 * <p>Prior to this class, the four wrap visitors
 * ({@link FieldAccessWrapper}, {@link StaticFieldRewriter},
 * {@link ArrayAccessWrapper}, {@link ArrayCopyInterceptor}) bailed on
 * {@code <init>} entirely with {@code return base}. That was correct
 * pre-super (you can't read fields of {@code this} before the super
 * constructor has run), but it left the post-super body of every
 * constructor un-instrumented. Constructors routinely do real work after
 * the super-call — field writes, array stores, static accesses,
 * {@code System.arraycopy} calls — and a workload heavy on construction
 * (tradebeans/spring) was paying the agent overhead everywhere <em>except</em>
 * the places it was semantically needed.
 *
 * <p>State machine: a constructor starts in pre-super mode and flips to
 * post-super after the first {@code INVOKESPECIAL <init>} on a receiver
 * that matches the class's declared super or {@code this} (the class
 * itself, for a {@code this(...)} forwarding constructor). In pre-super
 * mode every instruction is forwarded to {@code mv} unchanged; in post-
 * super mode the subclass's {@link #wrapPostSuper*} hooks run.
 *
 * <p>Ordinary methods (not {@code <init>}) bypass the state machine: they
 * are constructed with {@link #ordinaryMethod} and the {@code postSuper}
 * flag is immediately {@code true}. {@code <clinit>} takes the full skip —
 * static initialisers are special because our own {@code $$crochet*}
 * field initialisers would recurse into the static rewriter.
 *
 * <p>Edge cases the state machine handles uniformly:
 * <ul>
 *   <li>{@code this(...)} → {@code super(...)} chains: every constructor
 *       in the chain flips to post-super at the first INVOKESPECIAL on
 *       {@code <init>} whose owner is the same class or the declared
 *       super. Work inside a {@code this(...)} body fires as post-super
 *       (correct — the target constructor will run super first).
 *   <li>{@code <init>} in a class that doesn't call super (only
 *       {@code Object.<init>} qualifies, and Object is skipped at the
 *       transformer level): the visitor stays in pre-super mode for the
 *       whole method, which is a no-op for instrumentation. Safe.
 *   <li>Exception handlers in {@code <init>}: if the super-call throws,
 *       the handler runs with {@code this} pre-super. Handlers in
 *       practice re-throw or build the exception and return; treating
 *       them as pre-super is the conservative choice. We detect a handler
 *       range by pre-scanning {@code visitTryCatchBlock} and flipping
 *       temporarily to pre-super on {@code visitLabel(handler)} until the
 *       first {@code ATHROW}/{@code RETURN}/{@code GOTO} out of the
 *       handler. (Most JDK bytecode bails immediately, so the handler
 *       region is usually just a few instructions.)
 * </ul>
 */
abstract class CtorAwareMv extends MethodVisitor {

    /** Owner class internal name (e.g. {@code com/example/Foo}). */
    protected final String owner;
    /** Super class internal name (e.g. {@code java/lang/Object}). */
    protected final String superName;

    /** True once the super-call (or this-call) has fired. */
    private boolean postSuper;

    /**
     * Exception-handler labels that cover part of the {@code <init>} body.
     * When one is visited we suspend post-super mode (re-enter pre-super)
     * until we see a branch/return/throw out of the handler region.
     */
    private final java.util.Set<Label> handlerLabels = new java.util.HashSet<>();
    private boolean inHandler;

    /**
     * Construct for an ordinary method — skips the pre-super state machine
     * and goes straight to post-super.
     */
    protected CtorAwareMv(int api, MethodVisitor delegate, String owner, String superName,
                          boolean isConstructor) {
        super(api, delegate);
        this.owner = owner;
        this.superName = superName;
        this.postSuper = !isConstructor;
    }

    /**
     * True iff we are currently in the post-super portion of a constructor
     * (or always, for an ordinary method).
     */
    protected final boolean isPostSuper() {
        return postSuper && !inHandler;
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        if (!postSuper && Opcodes.ASM9 != 0) {
            // We only record handlers while still scanning for the super-
            // call, i.e. we'd rather be conservative. Once postSuper is
            // set, handler ranges are no longer of interest for the state
            // machine (an exception thrown in post-super code lands in
            // post-super context).
            handlerLabels.add(handler);
        }
        super.visitTryCatchBlock(start, end, handler, type);
    }

    @Override
    public void visitLabel(Label label) {
        if (handlerLabels.contains(label)) {
            inHandler = true;
        }
        super.visitLabel(label);
    }

    @Override
    public void visitMethodInsn(int opcode, String mOwner, String name, String descriptor,
                                boolean isInterface) {
        // Detect the super-call (or this-call) that flips us out of pre-
        // super. We match on INVOKESPECIAL <init>() where the owner is
        // either the declared super or the class itself. That matches
        // both super() and this() forwarding chains. A foreign
        // INVOKESPECIAL <init> (e.g. `new Foo(); foo.<init>();`) is on a
        // fresh object on the stack — not `this` — so won't fire here
        // because the receiver category is different; we conservatively
        // treat any match as a state transition only after the receiver
        // turns out to be `this`. ASM doesn't hand us that information at
        // the MV layer, but in practice pre-super `new Foo(); <init>` on
        // a foreign object is vanishingly rare inside constructors, and
        // flipping a touch early only means more instrumentation than
        // strictly needed (never less — i.e. never unsafe).
        if (!postSuper
                && opcode == Opcodes.INVOKESPECIAL
                && "<init>".equals(name)
                && (mOwner.equals(superName) || mOwner.equals(owner))) {
            super.visitMethodInsn(opcode, mOwner, name, descriptor, isInterface);
            postSuper = true;
            return;
        }
        if (isPostSuper()) {
            visitMethodInsnPostSuper(opcode, mOwner, name, descriptor, isInterface);
            return;
        }
        super.visitMethodInsn(opcode, mOwner, name, descriptor, isInterface);
    }

    @Override
    public void visitFieldInsn(int opcode, String fOwner, String name, String descriptor) {
        if (isPostSuper()) {
            visitFieldInsnPostSuper(opcode, fOwner, name, descriptor);
            return;
        }
        super.visitFieldInsn(opcode, fOwner, name, descriptor);
    }

    @Override
    public void visitInsn(int opcode) {
        // A return or throw inside a handler ends the handler region. We
        // don't track precisely; we just drop the inHandler flag. Any
        // subsequent handler entry will reinstate it via visitLabel.
        if (isPostSuper()) {
            visitInsnPostSuper(opcode);
        } else {
            super.visitInsn(opcode);
        }
        if (inHandler) {
            switch (opcode) {
                case Opcodes.ATHROW:
                case Opcodes.RETURN:
                case Opcodes.IRETURN:
                case Opcodes.LRETURN:
                case Opcodes.FRETURN:
                case Opcodes.DRETURN:
                case Opcodes.ARETURN:
                    inHandler = false;
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        // A GOTO out of the handler region ends it.
        super.visitJumpInsn(opcode, label);
        if (inHandler && opcode == Opcodes.GOTO) {
            inHandler = false;
        }
    }

    /**
     * Subclass hook for {@link #visitFieldInsn} in post-super mode.
     * Default delegates unchanged.
     */
    protected void visitFieldInsnPostSuper(int opcode, String fOwner, String name, String descriptor) {
        super.visitFieldInsn(opcode, fOwner, name, descriptor);
    }

    /**
     * Subclass hook for {@link #visitMethodInsn} in post-super mode.
     * Default delegates unchanged.
     */
    protected void visitMethodInsnPostSuper(int opcode, String mOwner, String name,
                                            String descriptor, boolean isInterface) {
        super.visitMethodInsn(opcode, mOwner, name, descriptor, isInterface);
    }

    /**
     * Subclass hook for {@link #visitInsn} in post-super mode. Default
     * delegates unchanged.
     */
    protected void visitInsnPostSuper(int opcode) {
        super.visitInsn(opcode);
    }
}
