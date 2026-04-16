// Builds the single "template" byte[] that SpecializerB will rewrite into a
// concrete per-user specialization. Mirrors how CROCHET's
// CheckpointRollbackStubClassGenerator seeds the constant pool with sentinel
// strings and method refs that *sun.misc.Unsafe.defineAnonymousClass* would
// have patched.
//
// Sentinels we use here (chosen to be visually obvious in javap output):
//   - Super class:       "crij/sentinel/SuperSentinel"
//   - Interface class:   "crij/sentinel/IfaceSentinel"
//   - Agent owner class: "crij/sentinel/AgentSentinel"
//   - Method names:      "__onCheckpointSentinel__", "__onRollbackSentinel__"
//
// The generated class has the shape:
//
//   public class CrijTemplate extends SuperSentinel implements IfaceSentinel {
//     public void $$crijCheckpoint(int v) {
//       AgentSentinel.__onCheckpointSentinel__(this, CrijTemplate.class);
//     }
//     public void $$crijRollback(int v) {
//       AgentSentinel.__onRollbackSentinel__(this, CrijTemplate.class);
//     }
//   }
//
// CROCHET's real template reads a specialized Class<?> out of a static field
// and passes *that* as the second argument instead of CrijTemplate.class; we
// skip that here because this spike only tests CP-patching feasibility, not
// the full per-state klass cache.
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.*;

public final class TemplateBytes {
    public static final String TEMPLATE_NAME   = "crij/TemplateSentinel";
    public static final String SUPER_SENTINEL  = "crij/sentinel/SuperSentinel";
    public static final String IFACE_SENTINEL  = "crij/sentinel/IfaceSentinel";
    public static final String AGENT_SENTINEL  = "crij/sentinel/AgentSentinel";
    public static final String CHECKPOINT_NAME = "__onCheckpointSentinel__";
    public static final String ROLLBACK_NAME   = "__onRollbackSentinel__";
    public static final String AGENT_SIG       = "(Ljava/lang/Object;Ljava/lang/Class;)V";

    private TemplateBytes() {}

    public static byte[] build() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        // V55 == Java 11 bytecode. Hidden classes require >= V55.
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, TEMPLATE_NAME, null, SUPER_SENTINEL,
                 new String[] { IFACE_SENTINEL });

        // Default constructor: super();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, SUPER_SENTINEL, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // $$crijCheckpoint(int)
        mv = cw.visitMethod(ACC_PUBLIC, "$$crijCheckpoint", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(TEMPLATE_NAME));
        mv.visitMethodInsn(INVOKESTATIC, AGENT_SENTINEL, CHECKPOINT_NAME, AGENT_SIG, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // $$crijRollback(int)
        mv = cw.visitMethod(ACC_PUBLIC, "$$crijRollback", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(TEMPLATE_NAME));
        mv.visitMethodInsn(INVOKESTATIC, AGENT_SENTINEL, ROLLBACK_NAME, AGENT_SIG, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
