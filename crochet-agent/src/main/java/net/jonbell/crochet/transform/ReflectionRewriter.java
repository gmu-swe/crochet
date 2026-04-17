package net.jonbell.crochet.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites reflective API call sites in user classes so our injected members
 * ({@code $$crochet*} fields/methods, {@link
 * net.jonbell.crochet.runtime.CRIJInstrumented}) stay invisible to framework
 * code that enumerates reflectively.
 *
 * <p>Redirected call sites:
 * <ul>
 *   <li>{@code Class.getFields()}, {@code Class.getDeclaredFields()} →
 *       result filtered via {@link
 *       net.jonbell.crochet.runtime.ReflectionFilter#filterFields(
 *       java.lang.reflect.Field[])}</li>
 *   <li>{@code Class.getMethods()}, {@code Class.getDeclaredMethods()} →
 *       result filtered via
 *       {@link net.jonbell.crochet.runtime.ReflectionFilter#filterMethods(
 *       java.lang.reflect.Method[])}</li>
 *   <li>{@code Class.getInterfaces()} → result filtered via
 *       {@link net.jonbell.crochet.runtime.ReflectionFilter#filterInterfaces(
 *       Class[])}</li>
 *   <li>{@code Field.get*}/{@code Field.set*} → replaced with
 *       {@link net.jonbell.crochet.runtime.ReflectionFilter#getField} and
 *       friends, which trigger {@code $$crochetAccess} on the target before
 *       delegating. Bytecode GETFIELD/PUTFIELD already goes through the
 *       same hook via {@link FieldAccessWrapper}; this closes the
 *       reflection bypass.</li>
 * </ul>
 *
 * <p>Scope: only runs on user classes (non-JDK, non-skipped). The caller in
 * {@link CrochetTransformer} chains this visitor above the bytecode
 * wrappers ({@link FieldAccessWrapper}, {@link StaticFieldRewriter},
 * {@link ArrayAccessWrapper}) but inserts it only for user classes — we
 * must never fight the JVM's own reflection machinery inside
 * {@code java.lang.Class} et al.
 *
 * <p>Chain position: ReflectionRewriter touches only INVOKEVIRTUAL /
 * INVOKESTATIC call sites, never GETFIELD/PUTFIELD or array stores. It can
 * sit anywhere in the chain without interfering with frame computation.
 * We place it on top so the inserted static calls are visible to every
 * downstream wrapper (although none of them inspect INVOKESTATIC for our
 * helper, so the position is effectively arbitrary).
 */
public final class ReflectionRewriter extends ClassVisitor {

    private static final String FILTER =
            "net/jonbell/crochet/runtime/ReflectionFilter";

    public ReflectionRewriter(int api, ClassVisitor delegate) {
        super(api, delegate);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor base = super.visitMethod(access, name, descriptor,
                signature, exceptions);
        if (base == null) {
            return null;
        }
        // Never rewrite our own synthetic methods — they contain the very
        // calls we're interposing on (e.g., {@code Class.getDeclaredFields}
        // walks the Specializer/ProxyTemplate hits). Defensive: if the user
        // class authored a method whose name collides with $$crochet*,
        // we'd skip it here; FieldAdder already refuses to re-emit those.
        if (name.startsWith("$$crochet")) {
            return base;
        }
        return new RedirectMV(api, base);
    }

    private static final class RedirectMV extends MethodVisitor {

        RedirectMV(int api, MethodVisitor delegate) {
            super(api, delegate);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            // -- Class.get{Fields,DeclaredFields,Methods,DeclaredMethods,
            //        Interfaces}()
            // INVOKEVIRTUAL on java/lang/Class, zero-arg, array return type.
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && "java/lang/Class".equals(owner)) {
                if ("getFields".equals(name)
                        && "()[Ljava/lang/reflect/Field;".equals(descriptor)) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, FILTER,
                            "filterFields",
                            "([Ljava/lang/reflect/Field;)[Ljava/lang/reflect/Field;",
                            false);
                    return;
                }
                if ("getDeclaredFields".equals(name)
                        && "()[Ljava/lang/reflect/Field;".equals(descriptor)) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, FILTER,
                            "filterFields",
                            "([Ljava/lang/reflect/Field;)[Ljava/lang/reflect/Field;",
                            false);
                    return;
                }
                if ("getMethods".equals(name)
                        && "()[Ljava/lang/reflect/Method;".equals(descriptor)) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, FILTER,
                            "filterMethods",
                            "([Ljava/lang/reflect/Method;)[Ljava/lang/reflect/Method;",
                            false);
                    return;
                }
                if ("getDeclaredMethods".equals(name)
                        && "()[Ljava/lang/reflect/Method;".equals(descriptor)) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, FILTER,
                            "filterMethods",
                            "([Ljava/lang/reflect/Method;)[Ljava/lang/reflect/Method;",
                            false);
                    return;
                }
                if ("getInterfaces".equals(name)
                        && "()[Ljava/lang/Class;".equals(descriptor)) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, FILTER,
                            "filterInterfaces",
                            "([Ljava/lang/Class;)[Ljava/lang/Class;",
                            false);
                    return;
                }
            }

            // -- Field.get* / Field.set*
            // Redirect to static ReflectionFilter.getXxx/setXxx which accept
            // the Field as the first argument. Signature transform: prepend
            // "Ljava/lang/reflect/Field;" to the existing arg list.
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && "java/lang/reflect/Field".equals(owner)
                    && isFieldGetterOrSetter(name, descriptor)) {
                String newDesc = "(Ljava/lang/reflect/Field;"
                        + descriptor.substring(1);
                // Map Field.get(Object) → ReflectionFilter.getField(Field, Object).
                // For the primitive variants, keep the same method name:
                //   Field.getInt       → ReflectionFilter.getInt
                //   Field.setLong      → ReflectionFilter.setLong
                String mappedName = mapFieldMethodName(name);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, FILTER,
                        mappedName, newDesc, false);
                return;
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        private static boolean isFieldGetterOrSetter(String name, String descriptor) {
            // Must be an instance method (first arg is the target Object).
            // Instance get/set on Field have descriptors beginning with
            // "(Ljava/lang/Object;" — the object the reflection is against.
            if (!descriptor.startsWith("(Ljava/lang/Object;")) {
                return false;
            }
            switch (name) {
                case "get":
                case "set":
                case "getBoolean":
                case "setBoolean":
                case "getByte":
                case "setByte":
                case "getChar":
                case "setChar":
                case "getShort":
                case "setShort":
                case "getInt":
                case "setInt":
                case "getLong":
                case "setLong":
                case "getFloat":
                case "setFloat":
                case "getDouble":
                case "setDouble":
                    return true;
                default:
                    return false;
            }
        }

        private static String mapFieldMethodName(String name) {
            // "get" and "set" on Field become "getField" / "setField" on
            // ReflectionFilter to avoid name collision with the primitive
            // methods. The primitive variants keep the same name.
            if ("get".equals(name)) {
                return "getField";
            }
            if ("set".equals(name)) {
                return "setField";
            }
            return name;
        }
    }
}
