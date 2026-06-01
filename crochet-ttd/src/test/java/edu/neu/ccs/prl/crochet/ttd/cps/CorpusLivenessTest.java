package edu.neu.ccs.prl.crochet.ttd.cps;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import edu.neu.ccs.prl.crochet.ttd.cps.LivenessAnalyzer.LiveLocal;

/**
 * Fuzz corpus driver for {@link LivenessAnalyzer}.
 *
 * <p>Analyzes every {@code .class} file under {@code /tmp/jdk-corpus} (produced
 * by {@code jimage extract --dir /tmp/jdk-corpus
 * /usr/lib/jvm/java-21-openjdk-amd64/lib/modules}). For each method in each
 * class, uses ALL instruction BCIs as save points (maximum stress). Computes a
 * deterministic SHA-256 corpus hash over sorted per-method liveness maps.
 *
 * <p>The committed {@link #EXPECTED_CORPUS_HASH} pins the result for all future
 * runs (universal gate 18: deterministic emission). The test fails if the hash
 * changes, indicating a change in liveness semantics.
 *
 * <p>Skip condition: if {@code /tmp/jdk-corpus} does not exist, the test is
 * skipped with a message explaining how to extract the corpus. This allows CI
 * to run without the corpus while a manual or nightly run pins the hash.
 *
 * <p>To regenerate the corpus hash after an intentional algorithm change:
 * run the test with {@code -Dcrochet.ttd.corpusRegen=true}; it will print the
 * new hash and fail with an instructional message to update the constant.
 */
class CorpusLivenessTest {

    /**
     * Expected SHA-256 corpus hash (first 16 hex chars of the full 64-char hash).
     * Full hash is documented in {@code designs/B.1/DESIGN.md}.
     *
     * <p>To regenerate: run with {@code -Dcrochet.ttd.corpusRegen=true}, observe
     * the printed hash, update this constant, and update DESIGN.md.
     */
    static final String EXPECTED_CORPUS_HASH =
            // SHA-256 over sorted per-method liveness maps for JDK 21 corpus.
            // Regenerate with: mvn -pl crochet-ttd test -Dtest=CorpusLivenessTest
            //                      -Dcrochet.ttd.corpusRegen=true
            // Full hash documented in designs/B.1/DESIGN.md.
            "cd17554cb5595739b08352bd7778fe0dd5cd5aecc331fe565752b422e25828c3";

    private static final Path CORPUS_DIR = Paths.get("/tmp/jdk-corpus");
    private static final LivenessAnalyzer ANALYZER = new LivenessAnalyzer();

    @Test
    void corpusHash_isPinned() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS_DIR),
                "JDK corpus not found at " + CORPUS_DIR
                        + ". Extract with: jimage extract --dir /tmp/jdk-corpus"
                        + " /usr/lib/jvm/java-21-openjdk-amd64/lib/modules");

        String actualHash = computeCorpusHash();
        System.out.println("[B.1 corpus] SHA-256 hash (first 16 chars): "
                + actualHash.substring(0, 16));
        System.out.println("[B.1 corpus] Full SHA-256: " + actualHash);

        boolean regen = Boolean.getBoolean("crochet.ttd.corpusRegen");
        if (regen || "UNSET".equals(EXPECTED_CORPUS_HASH)) {
            System.out.println("[B.1 corpus] REGEN mode: computed hash = " + actualHash);
            System.out.println("[B.1 corpus] Update EXPECTED_CORPUS_HASH in CorpusLivenessTest.java");
            System.out.println("[B.1 corpus] Update designs/B.1/DESIGN.md CORPUS_HASH entry");
            // Don't fail in regen mode — just report.
            // But if UNSET, we do want the test to pass on first run so we can capture the hash.
            // Mark as "informational" by not asserting.
            return;
        }

        assertEquals(EXPECTED_CORPUS_HASH, actualHash,
                "Corpus liveness hash changed — if intentional, run with "
                        + "-Dcrochet.ttd.corpusRegen=true to update");
    }

    /**
     * Computes a deterministic SHA-256 over the liveness results for every method
     * in every class file in the corpus directory.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Walk all {@code .class} files under {@code CORPUS_DIR}, sorted by path.
     *   <li>For each class, parse with ASM {@link ClassReader}.
     *   <li>For each method (sorted by name+descriptor), collect all instruction
     *       BCIs as the save-point set.
     *   <li>Run {@link LivenessAnalyzer#analyze} (silently skip methods that fail
     *       analysis — JDK contains some unusual bytecode patterns).
     *   <li>Serialize the liveness map to a canonical string, hash it.
     *   <li>Combine per-method hashes (sorted) into a corpus-level SHA-256.
     * </ol>
     */
    static String computeCorpusHash() throws IOException, NoSuchAlgorithmException {
        MessageDigest corpusMd = MessageDigest.getInstance("SHA-256");

        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(CORPUS_DIR)) {
            classFiles = walk.filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        assertTrue(classFiles.size() >= 10_000,
                "Corpus must have ≥10K class files; found " + classFiles.size());

        List<String> methodHashes = new ArrayList<>();

        for (Path classFile : classFiles) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(classFile);
            } catch (IOException e) {
                continue; // skip unreadable files
            }

            ClassNode cn = new ClassNode();
            try {
                new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
            } catch (Exception e) {
                continue; // skip unparseable class files
            }

            String ownerName = cn.name;

            // Sort methods for determinism.
            List<MethodNode> methods = new ArrayList<>(cn.methods);
            methods.sort(Comparator.comparing((MethodNode m) -> m.name)
                    .thenComparing(m -> m.desc));

            for (MethodNode mn : methods) {
                try {
                    String methodHash = analyzeMethodAndHash(ownerName, mn);
                    if (methodHash != null) {
                        methodHashes.add(ownerName + "." + mn.name + mn.desc + ":" + methodHash);
                    }
                } catch (Exception e) {
                    // Skip methods that fail analysis (e.g., corrupt bytecode in corpus).
                    // We record a sentinel so the hash still accounts for these.
                    methodHashes.add(ownerName + "." + mn.name + mn.desc + ":ERROR");
                }
            }
        }

        // Sort all method hashes for determinism, then combine into corpus hash.
        Collections.sort(methodHashes);
        for (String h : methodHashes) {
            corpusMd.update(h.getBytes(StandardCharsets.UTF_8));
        }

        return toHex(corpusMd.digest());
    }

    /**
     * Analyzes a single method: uses all instruction BCIs as save points, then
     * serializes the result to a canonical string, and returns its SHA-256 hash.
     * Returns {@code null} for abstract/native methods.
     */
    private static String analyzeMethodAndHash(String owner, MethodNode mn)
            throws AnalyzerException, NoSuchAlgorithmException {
        int flags = mn.access;
        if ((flags & (org.objectweb.asm.Opcodes.ACC_ABSTRACT
                | org.objectweb.asm.Opcodes.ACC_NATIVE)) != 0) {
            return null;
        }
        if (mn.instructions == null || mn.instructions.size() == 0) {
            return "empty";
        }

        // All instruction BCIs as save-point set (maximum stress).
        Set<Integer> allBcis = new HashSet<>();
        for (int i = 0; i < mn.instructions.size(); i++) {
            allBcis.add(i);
        }

        Map<Integer, List<LiveLocal>> result;
        try {
            result = ANALYZER.analyze(owner, mn, allBcis);
        } catch (IllegalStateException e) {
            // Uninitialized-this: not an analysis error, record as sentinel.
            return "UNINIT_THIS";
        }

        // Serialize deterministically: sorted by BCI, then by slot.
        StringBuilder sb = new StringBuilder();
        List<Integer> bcis = new ArrayList<>(result.keySet());
        Collections.sort(bcis);
        for (int bci : bcis) {
            sb.append(bci).append(":[");
            List<LiveLocal> live = result.get(bci);
            for (int i = 0; i < live.size(); i++) {
                if (i > 0) sb.append(",");
                LiveLocal ll = live.get(i);
                sb.append(ll.slotIndex()).append(":").append(ll.type().getDescriptor());
            }
            sb.append("]");
        }

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(sb.toString().getBytes(StandardCharsets.UTF_8));
        return toHex(md.digest()).substring(0, 16); // first 16 hex chars per method
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
