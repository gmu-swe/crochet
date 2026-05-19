package net.jonbell.crochet.transform;

import net.jonbell.crochet.tests.CheckpointFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural bytecode tests for {@link CheckpointWrapper}.
 *
 * <p>Each test loads the transformed bytecode of {@link CheckpointFixture}
 * and inspects the emitted instructions for the target method.  Tests do not
 * execute the code — they verify that {@code CheckpointWrapper} generates the
 * correct call sites and exception handlers.
 */
class CheckpointWrapperTest {

    private static ClassNode transformed;

    @BeforeAll
    static void transform() throws Exception {
        byte[] original = bytesOf(CheckpointFixture.class);
        byte[] result = new CrochetTransformer().transform(original, false);
        assertNotNull(result, "transform returned null — class was skipped unexpectedly");
        transformed = toNode(result);
    }

    // -----------------------------------------------------------------------
    // Positive: methods annotated with @CrochetCheckpoint + @CrochetRoot
    // -----------------------------------------------------------------------

    @Test
    void voidMethodGetsCheckpointCall() {
        MethodNode m = findMethod("doVoid");
        assertCheckpointAndRollback(m, "doVoid");
    }

    @Test
    void intReturnMethodGetsCheckpointCall() {
        MethodNode m = findMethod("doInt");
        assertCheckpointAndRollback(m, "doInt");
    }

    @Test
    void longReturnMethodGetsCheckpointCall() {
        MethodNode m = findMethod("doLong");
        assertCheckpointAndRollback(m, "doLong");
    }

    @Test
    void doubleReturnMethodGetsCheckpointCall() {
        MethodNode m = findMethod("doDouble");
        assertCheckpointAndRollback(m, "doDouble");
    }

    @Test
    void floatReturnMethodGetsCheckpointCall() {
        MethodNode m = findMethod("doFloat");
        assertCheckpointAndRollback(m, "doFloat");
    }

    @Test
    void refReturnMethodGetsCheckpointCall() {
        MethodNode m = findMethod("doRef");
        assertCheckpointAndRollback(m, "doRef");
    }

    @Test
    void booleanReturnMethodGetsCheckpointCall() {
        // boolean compiles to IRETURN — same slot path as int.
        MethodNode m = findMethod("doBoolean");
        assertCheckpointAndRollback(m, "doBoolean");
    }

    @Test
    void throwingMethodGetsCheckpointCall() {
        // doThrow has no normal return — handler must still be emitted.
        MethodNode m = findMethod("doThrow");
        assertCheckpointAndRollback(m, "doThrow");
    }

    @Test
    void innerTryCatchPreservesWrap() {
        // The original body has its own try/catch; the wrapper adds one more.
        // We expect at least 2 try/catch blocks in the transformed method.
        MethodNode m = findMethod("doWithInnerTryCatch");
        assertCheckpointAndRollback(m, "doWithInnerTryCatch");
        assertTrue(m.tryCatchBlocks.size() >= 2,
                "doWithInnerTryCatch should have at least 2 TCBs (inner + wrapper), "
                        + "got " + m.tryCatchBlocks.size());
    }

    @Test
    void multiParamRootIsSecondParam() {
        // @CrochetRoot is on the second parameter (index 1).
        // CheckpointWrapper must load the correct slot (slot 2 = 1 int slot for extra).
        MethodNode m = findMethod("doMultiParam");
        assertCheckpointAndRollback(m, "doMultiParam");
    }

    // -----------------------------------------------------------------------
    // Negative: methods that must NOT be wrapped
    // -----------------------------------------------------------------------

    @Test
    void methodWithNoRootIsNotWrapped() {
        MethodNode m = findMethod("noRoot");
        assertNoCheckpoint(m, "noRoot");
    }

    @Test
    void staticMethodIsNotWrapped() {
        MethodNode m = findMethod("staticMethod");
        assertNoCheckpoint(m, "staticMethod");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private MethodNode findMethod(String name) {
        return transformed.methods.stream()
                .filter(mn -> mn.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("method not found: " + name));
    }

    private static void assertCheckpointAndRollback(MethodNode m, String ctx) {
        List<MethodInsnNode> statics = invokeStatics(m);

        boolean hasCheckpoint = statics.stream()
                .anyMatch(mi -> CheckpointWrapper.CROCHET_OWNER.equals(mi.owner)
                        && "checkpoint".equals(mi.name)
                        && CheckpointWrapper.CHECKPOINT_DESC.equals(mi.desc));
        assertTrue(hasCheckpoint,
                ctx + ": expected INVOKESTATIC Crochet.checkpoint but none found");

        boolean hasRollback = statics.stream()
                .anyMatch(mi -> CheckpointWrapper.CROCHET_OWNER.equals(mi.owner)
                        && "rollback".equals(mi.name)
                        && CheckpointWrapper.ROLLBACK_DESC.equals(mi.desc));
        assertTrue(hasRollback,
                ctx + ": expected INVOKESTATIC Crochet.rollback but none found");

        boolean hasTcb = m.tryCatchBlocks.stream()
                .anyMatch(tcb -> tcb.type == null); // null = catch Throwable
        assertTrue(hasTcb,
                ctx + ": expected a catch-Throwable TryCatchBlockNode but none found");
    }

    private static void assertNoCheckpoint(MethodNode m, String ctx) {
        boolean hasCheckpoint = invokeStatics(m).stream()
                .anyMatch(mi -> CheckpointWrapper.CROCHET_OWNER.equals(mi.owner)
                        && "checkpoint".equals(mi.name));
        assertFalse(hasCheckpoint,
                ctx + ": expected NO Crochet.checkpoint INVOKESTATIC but one was found");
    }

    private static List<MethodInsnNode> invokeStatics(MethodNode m) {
        return StreamSupport
                .stream(m.instructions.spliterator(), false)
                .filter(n -> n.getOpcode() == Opcodes.INVOKESTATIC)
                .map(n -> (MethodInsnNode) n)
                .toList();
    }

    private static byte[] bytesOf(Class<?> c) throws IOException {
        String path = c.getName().replace('.', '/') + ".class";
        try (InputStream is = c.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "could not find class bytes for " + c.getName());
            return is.readAllBytes();
        }
    }

    private static ClassNode toNode(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }
}
