// Approach B: take the template byte[], run a ClassReader -> rewriting
// ClassVisitor -> ClassWriter pipeline that replaces every sentinel reference
// with the per-specialization values, then hand the result to
// Lookup.defineHiddenClass.
//
// Why a ClassVisitor and not raw CP surgery? Because ASM's ClassWriter
// deduplicates CP entries; if we rename a sentinel to the real owner it will
// naturally collide with an existing entry referencing that owner, so ASM
// handles that for us. This is semantically equivalent to mutating the
// constant pool entries in place, which is exactly what
// sun.misc.Unsafe.defineAnonymousClass's cpPatches argument used to do.
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandles.Lookup.ClassOption;
import java.lang.reflect.Method;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class SpecializerB {
    public enum RoleState { CHECKPOINT, ROLLBACK, NORMAL }

    public static final class SpecSpec {
        final Class<?> userClass;                   // super to extend
        final Class<?> ifaceClass;                  // interface to implement
        final String   specializedInternalName;     // new this_class
        final String   agentOwnerInternal;          // replaces AGENT_SENTINEL
        final String   checkpointMethodName;        // replaces __onCheckpointSentinel__
        final String   rollbackMethodName;          // replaces __onRollbackSentinel__

        public SpecSpec(Class<?> userClass, Class<?> ifaceClass,
                        String specializedInternalName,
                        String agentOwnerInternal,
                        String checkpointMethodName,
                        String rollbackMethodName) {
            this.userClass = userClass;
            this.ifaceClass = ifaceClass;
            this.specializedInternalName = specializedInternalName;
            this.agentOwnerInternal = agentOwnerInternal;
            this.checkpointMethodName = checkpointMethodName;
            this.rollbackMethodName = rollbackMethodName;
        }
    }

    public static byte[] rewrite(byte[] templateBytes, SpecSpec spec) {
        ClassReader cr = new ClassReader(templateBytes);
        // COMPUTE_FRAMES is safe because we don't change any opcode shapes.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        final String userInternal = Type.getInternalName(spec.userClass);
        final String ifaceInternal = Type.getInternalName(spec.ifaceClass);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                // Everything the CROCHET cpPatches[] array used to replace is
                // rewritten right here:
                //   - patches[1]: this_class's internal name
                //   - patches[4]: super_class Class ref (we pass internal name
                //                 of the user class; JVM resolves it normally)
                //   - patches[6]: interface Class ref
                String newSuper = TemplateBytes.SUPER_SENTINEL.equals(superName)
                                  ? userInternal : superName;
                String[] newIfaces = interfaces;
                if (interfaces != null) {
                    newIfaces = new String[interfaces.length];
                    for (int i = 0; i < interfaces.length; i++) {
                        newIfaces[i] = TemplateBytes.IFACE_SENTINEL.equals(interfaces[i])
                                       ? ifaceInternal : interfaces[i];
                    }
                }
                super.visit(version, access, spec.specializedInternalName, signature,
                            newSuper, newIfaces);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (base == null) return null;
                return new MethodVisitor(Opcodes.ASM9, base) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                String desc, boolean isInterface) {
                        // patches[9]/[21]/[29] (Utf8 method names) and
                        // patches[15]/[24]/[32] (Utf8 method names)
                        //   -> rewritten together in the same methodref.
                        String newOwner = owner;
                        String newName  = mname;
                        if (TemplateBytes.SUPER_SENTINEL.equals(owner)) {
                            newOwner = userInternal;
                        } else if (TemplateBytes.AGENT_SENTINEL.equals(owner)) {
                            newOwner = spec.agentOwnerInternal;
                            if (TemplateBytes.CHECKPOINT_NAME.equals(mname))
                                newName = spec.checkpointMethodName;
                            else if (TemplateBytes.ROLLBACK_NAME.equals(mname))
                                newName = spec.rollbackMethodName;
                        }
                        super.visitMethodInsn(opcode, newOwner, newName, desc, isInterface);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        // ldc Type handles the "pass CrijTemplate.class" marker
                        if (value instanceof Type) {
                            Type t = (Type) value;
                            if (t.getSort() == Type.OBJECT
                                && TemplateBytes.TEMPLATE_NAME.equals(t.getInternalName())) {
                                super.visitLdcInsn(Type.getObjectType(spec.specializedInternalName));
                                return;
                            }
                        }
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        String nt = TemplateBytes.TEMPLATE_NAME.equals(type)
                                    ? spec.specializedInternalName : type;
                        super.visitTypeInsn(opcode, nt);
                    }
                };
            }
        }, 0);

        return cw.toByteArray();
    }

    /**
     * Produces a hidden specialization. Uses an injected accessor on the user
     * class (CROCHET's instrumenter would add this) to obtain a full-power
     * Lookup anchored inside the user class's package.
     */
    public static Class<?> specialize(byte[] templateBytes, SpecSpec spec) throws Throwable {
        byte[] specialized = rewrite(templateBytes, spec);
        Lookup L = obtainLookup(spec.userClass);
        // NESTMATE so the hidden class can call package/protected members on
        // the user class. STRONG would tie its lifetime to the user class's
        // loader, which matches CROCHET's cache strategy (it keeps one Class
        // per user class as sfHelperClass / $$crij_class_XXX).
        return L.defineHiddenClass(specialized, true, ClassOption.NESTMATE, ClassOption.STRONG)
                .lookupClass();
    }

    private static Lookup obtainLookup(Class<?> userClass) throws Throwable {
        // CROCHET's instrumenter adds $$crijLookup() to every user class.
        Method m = userClass.getDeclaredMethod("$$crijLookup");
        return (Lookup) m.invoke(null);
    }
}
