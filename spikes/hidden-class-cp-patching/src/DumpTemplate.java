// Dumps a byte[] template whose layout exactly matches
// CheckpointRollbackStubClassGenerator.generate() in the CROCHET source.
// We write it to disk so we can run `javap -v` on it and confirm which CP slots
// the patches[] array is actually patching.
//
// The ORDER in which ASM writes CP entries is the ORDER we first mention them.
// CheckpointRollbackAgent.generateClass() patches slots 1,4,6,9,15,21,24,29,32.
// This program reconstructs the *exact same sequence of ClassWriter calls*
// that generate() performs and then dumps the resulting class file.
//
// Compile: javac -cp lib/asm-9.7.1.jar src/DumpTemplate.java -d build
// Run:     java -cp build:lib/asm-9.7.1.jar DumpTemplate

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.io.FileOutputStream;

import static org.objectweb.asm.Opcodes.*;

public class DumpTemplate {
    public static void main(String[] args) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String origName = "_ORIG_CLASS_NAME_";
        String newName  = "_ANON_CLASS_NAME_";
        String ifaceName= "_ANON_IFACE_NAME_";

        cw.visit(V1_8, ACC_PUBLIC, newName, null, origName, new String[]{ ifaceName });

        // $$crijAccess()V
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "$$crijAccess", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, origName, "__transition_to_on_get__",       "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent",
                           "_GET_PLACEHOLDER",
                           "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // $$crijCheckpoint(I)V
        mv = cw.visitMethod(ACC_PUBLIC, "$$crijCheckpoint", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, origName, "__transition_to_on_checkpoint__",
                           "()Ljava/lang/Class;", false);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent",
                           "_CHECKPOINT_PLACEHOLDER",
                           "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;I)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // $$crijRollback(I)V
        mv = cw.visitMethod(ACC_PUBLIC, "$$crijRollback", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, origName, "__transition_to_on_rollback__",
                           "()Ljava/lang/Class;", false);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "net/jonbell/crij/runtime/CheckpointRollbackAgent",
                           "_ROLLBACK_PLACEHOLDER",
                           "(Lnet/jonbell/crij/runtime/CRIJInstrumented;Ljava/lang/Class;I)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();

        byte[] bytes = cw.toByteArray();
        try (FileOutputStream fos = new FileOutputStream("build/Template.class")) {
            fos.write(bytes);
        }
        System.out.println("wrote build/Template.class (" + bytes.length + " bytes)");
    }
}
