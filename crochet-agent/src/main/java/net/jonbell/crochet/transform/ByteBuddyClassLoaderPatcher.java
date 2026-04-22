package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches ByteBuddy's private classloaders so dynamically-defined classes that
 * carry Crochet's {@code implements CRIJInstrumented} stamp can resolve the
 * runtime jar even when the classloader's parent chain doesn't include it.
 *
 * <p><b>The bug we are fixing.</b> Mockito's subclass mock-maker funnels
 * through ByteBuddy's {@code ByteArrayClassLoader.defineClass}, which fires
 * Crochet's {@code ClassFileTransformer} on the synthesised mock class and
 * stamps it with {@code implements net.jonbell.crochet.runtime.CRIJInstrumented}.
 * But {@code ByteArrayClassLoader}'s {@code parent} is the loader of the
 * class being mocked (e.g. an internal Kafka classloader), which has no
 * visibility of Crochet's runtime jar. The interface fails to resolve at
 * link-time:
 *
 * <pre>
 *   java.lang.ClassNotFoundException: net.jonbell.crochet.runtime.CRIJInstrumented
 *     at ByteArrayClassLoader.findClass(ByteArrayClassLoader.java:...)
 * </pre>
 *
 * <p>This blocks every Tapestry bench workload that uses
 * {@code Mockito.mock(SomeClass.class)} (kafka-17371, 17394, 17402, 17946,
 * 18418). The {@code -Xbootclasspath/a:crochet-agent.jar} workaround dodges
 * the CNFE but loads {@code CRIJInstrumented} twice (bootstrap loader + agent
 * loader) which trips a JVM-level SIGSEGV during ByteBuddy's
 * {@code ClassInjector$UsingUnsafe.<clinit>}.
 *
 * <p><b>The fix.</b> Inject a prelude at the entry of {@code findClass} (and
 * the analogous {@code loadClass} on {@code MultipleParentClassLoader}) that
 * intercepts the {@code net.jonbell.crochet.runtime.*} prefix and routes the
 * lookup through {@link net.jonbell.crochet.patch.ByteBuddyClassLoaderSupport#tryAgentClassLoader},
 * which delegates to {@code Class.forName(name, false, CrochetAgent.class
 * .getClassLoader())}. {@code initialize=false} means we never re-trigger the
 * runtime class's {@code <clinit>}; {@link Class#forName} returns the same
 * {@link Class} instance every call so there's no double-load.
 *
 * <p><b>Emit shape.</b> For {@code findClass(String name)} (slot 1 = name):
 *
 * <pre>
 *   ALOAD_1
 *   INVOKESTATIC ByteBuddyClassLoaderSupport.tryAgentClassLoader(String) Class
 *   DUP
 *   IFNULL fallthrough
 *   ARETURN
 *   fallthrough:
 *   POP
 *   // ... original body unchanged ...
 * </pre>
 *
 * The DUP/IFNULL/ARETURN branch returns the agent-loaded {@link Class} when
 * the helper succeeds; the POP path discards the {@code null} and falls
 * straight into the original method body. No locals are introduced, so we
 * don't have to grow the method's max-locals budget. The
 * {@link net.jonbell.crochet.patch.ByteBuddyClassLoaderSupport} helper itself
 * never throws — every internal failure collapses to {@code null} — so the
 * patched body's {@code throws ClassNotFoundException} contract is preserved
 * unchanged.
 *
 * <p><b>Where this patcher fits in the chain.</b> Both ByteBuddy classloaders
 * are excluded from the rest of Crochet's bytecode pipeline by membership in
 * {@link CrochetTransformer#shouldSkip}? No — they take the full pipeline
 * because the user code (e.g. a benchmark or test) instantiates them and
 * touches their {@code parents} / {@code typeDefinitions} fields. We only
 * intervene on the specific {@code findClass} / {@code loadClass} method.
 *
 * <p><b>Bootstrap reentrancy.</b> The injected static call dispatches to a
 * helper in {@code net.jonbell.crochet.patch.}, which the transformer skips
 * via the package-prefix check in {@link CrochetTransformer#shouldSkip}, so
 * loading the helper does not recurse into us. The helper's {@link Class#forName}
 * call also runs entirely through the agent classloader, which has the
 * runtime jar visible by construction.
 */
final class ByteBuddyClassLoaderPatcher extends ClassVisitor {

    private static final String SUPPORT_INTERNAL =
            "net/jonbell/crochet/patch/ByteBuddyClassLoaderSupport";
    private static final String TRY_LOOKUP_METHOD = "tryAgentClassLoader";
    private static final String TRY_LOOKUP_DESC =
            "(Ljava/lang/String;)Ljava/lang/Class;";

    /** Internal name of the patched method's enclosing class. */
    private final String targetClass;

    /** Method name whose body is being prefixed (e.g. {@code findClass}). */
    private final String targetMethodName;

    /**
     * JVM descriptor of the patched method (e.g. {@code (Ljava/lang/String;)Ljava/lang/Class;}).
     * Used to disambiguate overloaded names — {@code MultipleParentClassLoader.loadClass}
     * coexists with {@code ClassLoader.loadClass(String)Ljava/lang/Class;}.
     */
    private final String targetMethodDesc;

    /** Local-variable slot of the {@code String} class-name argument. */
    private final int nameLocalSlot;

    ByteBuddyClassLoaderPatcher(int api, ClassVisitor delegate,
                                String targetClass, String targetMethodName,
                                String targetMethodDesc, int nameLocalSlot) {
        super(api, delegate);
        this.targetClass = targetClass;
        this.targetMethodName = targetMethodName;
        this.targetMethodDesc = targetMethodDesc;
        this.nameLocalSlot = nameLocalSlot;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (base != null
                && targetMethodName.equals(name)
                && targetMethodDesc.equals(descriptor)) {
            return new PrependLookupMV(api, base, nameLocalSlot);
        }
        return base;
    }

    /**
     * Configurations for the classes we patch. Each entry pins the method
     * name/descriptor and the slot of the {@code String} class-name argument
     * (instance methods have {@code this} at slot 0, so the first parameter
     * is at slot 1).
     */
    static final class Target {
        final String internalName;
        final String methodName;
        final String methodDesc;
        final int nameLocalSlot;

        Target(String internalName, String methodName, String methodDesc, int nameLocalSlot) {
            this.internalName = internalName;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.nameLocalSlot = nameLocalSlot;
        }
    }

    /** All ByteBuddy classloaders patched by this visitor. */
    static final Target[] TARGETS = new Target[] {
            // ByteBuddy's primary classloader for dynamically-defined classes.
            // findClass(String) is called by the JVM during link/load when an
            // internal name in this loader's namespace must be resolved.
            new Target(
                    "net/bytebuddy/dynamic/loading/ByteArrayClassLoader",
                    "findClass",
                    "(Ljava/lang/String;)Ljava/lang/Class;",
                    /*nameLocalSlot=*/ 1),
            // ByteBuddy's MultipleParentClassLoader overrides loadClass(String,
            // boolean) to walk a list of parent loaders. If none of those
            // parents has the runtime jar, the same CRIJInstrumented CNFE
            // surfaces here. Patching loadClass intercepts before the parent
            // walk, so our runtime types short-circuit cleanly. Empirically:
            // the bench failure mode for the kafka-1740x family does NOT
            // route through MultipleParentClassLoader (Mockito's
            // SubclassInjectionLoader uses ByteArrayClassLoader instead),
            // but covering this loader here is cheap insurance for any
            // future ByteBuddy consumer that does.
            new Target(
                    "net/bytebuddy/dynamic/loading/MultipleParentClassLoader",
                    "loadClass",
                    "(Ljava/lang/String;Z)Ljava/lang/Class;",
                    /*nameLocalSlot=*/ 1),
    };

    /**
     * Returns the {@link Target} for {@code internalName}, or {@code null}
     * when this class is not one of the ByteBuddy loaders we need to patch.
     */
    static Target targetFor(String internalName) {
        if (internalName == null) {
            return null;
        }
        for (Target t : TARGETS) {
            if (t.internalName.equals(internalName)) {
                return t;
            }
        }
        return null;
    }

    /**
     * MethodVisitor that prefixes the original body with a Crochet-runtime
     * fallback lookup. The emitted shape is documented on
     * {@link ByteBuddyClassLoaderPatcher}.
     */
    private static final class PrependLookupMV extends MethodVisitor {
        private final int nameLocalSlot;
        private boolean emitted;

        PrependLookupMV(int api, MethodVisitor delegate, int nameLocalSlot) {
            super(api, delegate);
            this.nameLocalSlot = nameLocalSlot;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (emitted) {
                return;
            }
            emitted = true;
            // Stack contract: every branch leaves the stack as it was on entry
            // (empty before the original body's first instruction).
            Label fallthrough = new Label();
            // ALOAD name
            mv.visitVarInsn(Opcodes.ALOAD, nameLocalSlot);
            // INVOKESTATIC tryAgentClassLoader(String) -> Class
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    SUPPORT_INTERNAL, TRY_LOOKUP_METHOD, TRY_LOOKUP_DESC,
                    /*itf=*/ false);
            // DUP : keep result for ARETURN if non-null, drop on null branch
            mv.visitInsn(Opcodes.DUP);
            // IFNULL fallthrough : if helper returned null, take the
            // original-body path
            mv.visitJumpInsn(Opcodes.IFNULL, fallthrough);
            // ARETURN : helper resolved a Class, return it directly
            mv.visitInsn(Opcodes.ARETURN);
            // fallthrough: pop the null and execute the original body
            mv.visitLabel(fallthrough);
            mv.visitInsn(Opcodes.POP);
            // visitFrame is not required: COMPUTE_FRAMES on the surrounding
            // ClassWriter recomputes the stack-map for this label and the
            // original first instruction picks up the empty stack we
            // restored.
        }
    }
}
