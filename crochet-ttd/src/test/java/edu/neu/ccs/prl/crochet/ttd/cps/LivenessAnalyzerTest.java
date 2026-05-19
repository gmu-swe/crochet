package edu.neu.ccs.prl.crochet.ttd.cps;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.*;

import edu.neu.ccs.prl.crochet.ttd.cps.LivenessAnalyzer.LiveLocal;

/**
 * Unit tests for {@link LivenessAnalyzer}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>2-slot type handling (long, double) — no N+1 phantom entry.
 *   <li>Branch joins — value live on one branch is live at join.
 *   <li>Try/catch — local live in handler is live at throwing instruction.
 *   <li>Uninitialized-this rejection in {@code <init>} save points.
 *   <li>Empty method (just RETURN).
 *   <li>Method with no save points → empty map.
 * </ul>
 */
class LivenessAnalyzerTest {

    private static final String OWNER = "com/example/Test";

    private final LivenessAnalyzer analyzer = new LivenessAnalyzer();

    // -----------------------------------------------------------------------
    // Helper: build MethodNodes programmatically
    // -----------------------------------------------------------------------

    /** Creates a MethodNode that just returns (void), no locals. */
    private static MethodNode emptyVoidMethod() {
        MethodNode mn = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "empty", "()V", null, null);
        mn.visitCode();
        mn.visitInsn(Opcodes.RETURN);
        mn.visitMaxs(0, 0);
        mn.visitEnd();
        return mn;
    }

    /**
     * Creates:
     * <pre>
     * static void withLong(long x, int y) {
     *     // save point at BCI of LSTORE or NOP before RETURN
     *     return;
     * }
     * </pre>
     * Method signature: (JI)V → slot 0 = long x (2 slots), slot 2 = int y.
     * We insert a NOP as the save point (BCI 0), then RETURN (BCI 1).
     * At BCI 0, slot 0 is live (long), slot 2 is live (int).
     */
    private static MethodNode methodWithLongAndInt() {
        // (JI)V: param 0 = long (slot 0,1), param 1 = int (slot 2)
        MethodNode mn = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "withLong", "(JI)V", null, null);
        mn.visitCode();
        mn.visitInsn(Opcodes.NOP);   // BCI 0 — save point
        mn.visitInsn(Opcodes.RETURN); // BCI 1
        mn.visitMaxs(2, 3); // stack=2, locals=3 (long=2 slots + int=1)
        mn.visitEnd();
        return mn;
    }

    /**
     * Creates:
     * <pre>
     * static void withDouble(double d) {
     *     // NOP save point, then RETURN
     * }
     * </pre>
     * (D)V → slot 0 = double (size 2).
     */
    private static MethodNode methodWithDouble() {
        MethodNode mn = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "withDouble", "(D)V", null, null);
        mn.visitCode();
        mn.visitInsn(Opcodes.NOP);   // BCI 0 — save point
        mn.visitInsn(Opcodes.RETURN); // BCI 1
        mn.visitMaxs(2, 2);
        mn.visitEnd();
        return mn;
    }

    /**
     * Creates a method with a branch:
     * <pre>
     * static void withBranch(boolean cond, int x) {
     *     int y;
     *     if (cond) { y = 10; } else { y = 20; }
     *     // save point (NOP) here — y is live
     *     return;
     * }
     * </pre>
     * (ZI)V — slot 0=boolean, slot 1=int x, slot 2=int y
     * After the if-else join, slot 2 (y) is live.
     */
    private static MethodNode methodWithBranch() {
        MethodNode mn = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "withBranch", "(ZI)V", null, null);
        mn.visitCode();
        Label elseLabel = new Label();
        Label joinLabel = new Label();
        // if (cond == 0) goto else
        mn.visitVarInsn(Opcodes.ILOAD, 0);   // load boolean cond
        mn.visitJumpInsn(Opcodes.IFEQ, elseLabel);
        // then: y = 10
        mn.visitIntInsn(Opcodes.BIPUSH, 10);
        mn.visitVarInsn(Opcodes.ISTORE, 2);
        mn.visitJumpInsn(Opcodes.GOTO, joinLabel);
        // else: y = 20
        mn.visitLabel(elseLabel);
        mn.visitIntInsn(Opcodes.BIPUSH, 20);
        mn.visitVarInsn(Opcodes.ISTORE, 2);
        // join
        mn.visitLabel(joinLabel);
        mn.visitInsn(Opcodes.NOP);   // save point
        mn.visitInsn(Opcodes.RETURN);
        mn.visitMaxs(1, 3);
        mn.visitEnd();
        return mn;
    }

    /**
     * Creates a method with try/catch:
     * <pre>
     * static void withTryCatch(String s) {
     *     try {
     *         // NOP save point — s is live (used in handler)
     *         s.length(); // can throw NPE
     *     } catch (NullPointerException e) {
     *         System.out.println(s); // uses s
     *     }
     * }
     * </pre>
     * (Ljava/lang/String;)V — slot 0=String s, slot 1=NullPointerException e (in handler)
     *
     * At the NOP save point inside the try block, slot 0 (s) must be live.
     */
    private static MethodNode methodWithTryCatch() {
        MethodNode mn = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "withTryCatch", "(Ljava/lang/String;)V", null, null);
        mn.visitCode();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handlerStart = new Label();
        mn.visitTryCatchBlock(tryStart, tryEnd, handlerStart,
                "java/lang/NullPointerException");

        mn.visitLabel(tryStart);
        mn.visitInsn(Opcodes.NOP);                          // BCI: save point
        mn.visitVarInsn(Opcodes.ALOAD, 0);                  // load s
        mn.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "java/lang/String", "length", "()I", false); // may throw
        mn.visitInsn(Opcodes.POP);
        mn.visitLabel(tryEnd);
        mn.visitInsn(Opcodes.RETURN);

        mn.visitLabel(handlerStart);
        mn.visitVarInsn(Opcodes.ASTORE, 1);                 // store exception in slot 1
        mn.visitFieldInsn(Opcodes.GETSTATIC,
                "java/lang/System", "out", "Ljava/io/PrintStream;");
        mn.visitVarInsn(Opcodes.ALOAD, 0);                  // load s (uses slot 0)
        mn.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false);
        mn.visitInsn(Opcodes.RETURN);

        mn.visitMaxs(2, 2);
        mn.visitEnd();
        return mn;
    }

    /**
     * Creates a minimal {@code <init>} that has a save point BEFORE the
     * {@code super()} call. This should be rejected by the analyzer.
     *
     * <pre>
     * class Foo {
     *     Foo() {
     *         // save point here (before super())
     *         super();
     *     }
     * }
     * </pre>
     */
    private static MethodNode initMethodWithSavePointBeforeSuper() {
        MethodNode mn = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC,
                "<init>", "()V", null, null);
        mn.visitCode();
        mn.visitVarInsn(Opcodes.ALOAD, 0);   // load this
        mn.visitInsn(Opcodes.NOP);           // BCI 1 — save point BEFORE super()
        mn.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        mn.visitInsn(Opcodes.RETURN);
        mn.visitMaxs(1, 1);
        mn.visitEnd();
        return mn;
    }

    /**
     * Creates a {@code <init>} with a save point AFTER the super() call.
     * This should NOT be rejected.
     */
    private static MethodNode initMethodWithSavePointAfterSuper() {
        MethodNode mn = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC,
                "<init>", "()V", null, null);
        mn.visitCode();
        mn.visitVarInsn(Opcodes.ALOAD, 0);   // load this
        mn.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        mn.visitInsn(Opcodes.NOP);           // save point AFTER super() — OK
        mn.visitInsn(Opcodes.RETURN);
        mn.visitMaxs(1, 1);
        mn.visitEnd();
        return mn;
    }

    // -----------------------------------------------------------------------
    // Helper: find BCI of the NOP instruction
    // -----------------------------------------------------------------------

    private static int findNopBci(MethodNode mn) {
        int bci = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn.getOpcode() == Opcodes.NOP) return bci;
            bci++;
        }
        throw new AssertionError("No NOP found in method");
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void emptyMethod_noSavePoints_returnsEmptyMap() throws AnalyzerException {
        MethodNode mn = emptyVoidMethod();
        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Collections.emptySet());
        assertTrue(result.isEmpty(), "No save points → empty map");
    }

    @Test
    void emptyMethod_savePointAtReturn_emptyLiveSet() throws AnalyzerException {
        MethodNode mn = emptyVoidMethod();
        // The only instruction is RETURN at BCI 0.
        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Set.of(0));
        assertTrue(result.containsKey(0), "BCI 0 should be in result");
        assertTrue(result.get(0).isEmpty(), "No locals → empty live set at RETURN");
    }

    @Test
    void longParam_oneEntryPerLogicalSlot_noPhantomSlot() throws AnalyzerException {
        // (JI)V — slot 0=long, slot 2=int
        MethodNode mn = methodWithLongAndInt();
        int nopBci = findNopBci(mn);

        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Set.of(nopBci));
        List<LiveLocal> live = result.get(nopBci);
        assertNotNull(live, "should have result at NOP bci");

        // Must have exactly: (0, LONG_TYPE) and (2, INT_TYPE)
        // Must NOT have (1, ...) — that's the phantom second slot of long.
        Map<Integer, Type> slotToType = new HashMap<>();
        for (LiveLocal ll : live) {
            slotToType.put(ll.slotIndex(), ll.type());
        }
        assertEquals(Type.LONG_TYPE, slotToType.get(0),
                "slot 0 must be LONG_TYPE");
        assertEquals(Type.INT_TYPE, slotToType.get(2),
                "slot 2 must be INT_TYPE");
        assertFalse(slotToType.containsKey(1),
                "slot 1 must NOT be reported (phantom second slot of long)");
        assertEquals(2, live.size(), "only 2 live locals: long at 0, int at 2");
    }

    @Test
    void doubleParam_singleEntry_noPhantomSlot() throws AnalyzerException {
        // (D)V — slot 0=double (size 2)
        MethodNode mn = methodWithDouble();
        int nopBci = findNopBci(mn);

        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Set.of(nopBci));
        List<LiveLocal> live = result.get(nopBci);
        assertNotNull(live);

        assertEquals(1, live.size(), "only one LiveLocal for double param");
        assertEquals(0, live.get(0).slotIndex(), "slot index must be 0");
        assertEquals(Type.DOUBLE_TYPE, live.get(0).type(), "type must be DOUBLE_TYPE");
        assertEquals(2, live.get(0).type().getSize(), "DOUBLE_TYPE.getSize() == 2");
    }

    @Test
    void longAndDouble_twoEntries_noPhantomSlots() throws AnalyzerException {
        // (JD)V — slot 0=long (slots 0,1), slot 2=double (slots 2,3)
        MethodNode mn = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "withLongAndDouble", "(JD)V", null, null);
        mn.visitCode();
        mn.visitInsn(Opcodes.NOP);   // BCI 0 — save point
        mn.visitInsn(Opcodes.RETURN);
        mn.visitMaxs(4, 4);
        mn.visitEnd();
        int nopBci = findNopBci(mn);

        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Set.of(nopBci));
        List<LiveLocal> live = result.get(nopBci);
        assertNotNull(live);

        Map<Integer, Type> slotToType = new HashMap<>();
        for (LiveLocal ll : live) {
            slotToType.put(ll.slotIndex(), ll.type());
        }
        assertEquals(Type.LONG_TYPE, slotToType.get(0), "slot 0 = long");
        assertEquals(Type.DOUBLE_TYPE, slotToType.get(2), "slot 2 = double");
        assertFalse(slotToType.containsKey(1), "slot 1 is phantom (long second slot)");
        assertFalse(slotToType.containsKey(3), "slot 3 is phantom (double second slot)");
        assertEquals(2, live.size(), "exactly 2 logical locals");
    }

    @Test
    void branchJoin_localDefinedOnBothBranches_isLiveAtJoin() throws AnalyzerException {
        // withBranch(Z I)V — after if-else join, slot 2 (y) is live
        MethodNode mn = methodWithBranch();
        int nopBci = findNopBci(mn);

        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Set.of(nopBci));
        List<LiveLocal> live = result.get(nopBci);
        assertNotNull(live);

        boolean foundY = live.stream().anyMatch(ll -> ll.slotIndex() == 2
                && ll.type() == Type.INT_TYPE);
        assertTrue(foundY, "slot 2 (y) must be live at join after if-else");
    }

    @Test
    void tryCatch_localUsedInHandler_isLiveAtThrowingInstruction()
            throws AnalyzerException {
        // withTryCatch(String s) — slot 0 (s) is used in catch handler
        MethodNode mn = methodWithTryCatch();
        int nopBci = findNopBci(mn);

        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Set.of(nopBci));
        List<LiveLocal> live = result.get(nopBci);
        assertNotNull(live);

        boolean foundS = live.stream().anyMatch(ll -> ll.slotIndex() == 0);
        assertTrue(foundS, "slot 0 (String s) must be live at NOP inside try block");
    }

    @Test
    void uninitializedThis_savepointBeforeSuper_throwsIllegalState() {
        // <init> with save point before super() — must throw IllegalStateException
        MethodNode mn = initMethodWithSavePointBeforeSuper();
        int nopBci = findNopBci(mn);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                analyzer.analyze("com/example/Foo", mn, Set.of(nopBci)));
        assertTrue(ex.getMessage().contains("com/example/Foo"),
                "error message must contain the owner class name");
        assertTrue(ex.getMessage().contains("<init>"),
                "error message must mention <init>");
    }

    @Test
    void uninitializedThis_savepointAfterSuper_noException() throws AnalyzerException {
        // <init> with save point AFTER super() — should be fine
        MethodNode mn = initMethodWithSavePointAfterSuper();
        int nopBci = findNopBci(mn);

        // Should not throw
        assertDoesNotThrow(() -> analyzer.analyze("com/example/Foo", mn, Set.of(nopBci)));
    }

    @Test
    void noSavePoints_emptyMap() throws AnalyzerException {
        MethodNode mn = methodWithLongAndInt();
        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Collections.emptySet());
        assertTrue(result.isEmpty(), "Empty save-point set → empty result map");
    }

    @Test
    void outOfRangeBci_silentlyIgnored() throws AnalyzerException {
        MethodNode mn = emptyVoidMethod();
        // BCI 999 is out of range for this 1-instruction method.
        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Set.of(999));
        assertFalse(result.containsKey(999),
                "Out-of-range BCI must be silently ignored");
    }

    @Test
    void outputIsSortedBySlotIndex() throws AnalyzerException {
        // (JI)V — result should be sorted: (0, long), (2, int)
        MethodNode mn = methodWithLongAndInt();
        int nopBci = findNopBci(mn);
        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Set.of(nopBci));
        List<LiveLocal> live = result.get(nopBci);
        assertNotNull(live);
        for (int i = 1; i < live.size(); i++) {
            assertTrue(live.get(i - 1).slotIndex() <= live.get(i).slotIndex(),
                    "Output must be sorted ascending by slotIndex");
        }
    }

    @Test
    void multipleSavePoints_independentResults() throws AnalyzerException {
        // Method: NOP (bci 0), ICONST_1, ISTORE 0, NOP (bci 2), RETURN
        // At bci 0: slot 0 not yet defined (parameter method, no params)
        // At bci 2 (after ISTORE): slot 0 (int) is defined and live
        MethodNode mn = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "twoSavePoints", "()V", null, null);
        mn.visitCode();
        mn.visitInsn(Opcodes.NOP);          // BCI 0: save point A — no locals
        mn.visitInsn(Opcodes.ICONST_1);
        mn.visitVarInsn(Opcodes.ISTORE, 0); // defines slot 0
        mn.visitInsn(Opcodes.NOP);          // BCI 3: save point B — slot 0 is live
        mn.visitInsn(Opcodes.RETURN);
        mn.visitMaxs(1, 1);
        mn.visitEnd();

        Map<Integer, List<LiveLocal>> result = analyzer.analyze(OWNER, mn, Set.of(0, 3));

        List<LiveLocal> liveA = result.get(0);
        List<LiveLocal> liveB = result.get(3);
        assertNotNull(liveA);
        assertNotNull(liveB);
        assertTrue(liveA.isEmpty(), "At save point A (BCI 0), no locals are defined yet");
        assertFalse(liveB.isEmpty(), "At save point B (BCI 3), slot 0 (int) must be live");
        assertEquals(0, liveB.get(0).slotIndex());
        assertEquals(Type.INT_TYPE, liveB.get(0).type());
    }
}
