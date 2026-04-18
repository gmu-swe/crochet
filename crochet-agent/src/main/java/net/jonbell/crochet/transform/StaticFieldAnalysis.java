package net.jonbell.crochet.transform;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Transform-time analysis that answers "does this class declare any
 * mutable static field worth snapshotting?" via a cached resource-stream
 * read of the class file.
 *
 * <p>Used by {@link StaticFieldRewriter} to elide the
 * {@code noteStaticAccess} pre-hook when the owner class's statics are
 * all {@code final}. The paper's perf evaluation (BENCHMARK.md §9)
 * showed that per-call-site cost of the pre-hook is already ≈4.5 ns —
 * near the floor for "dispatch + branch + return" — so the productive
 * optimisation is to reduce the NUMBER of call sites emitted, not the
 * cost of each one.
 *
 * <p>{@code checkpointStatics} skips final and synthetic fields
 * ({@code CheckpointRollbackAgent.checkpointStatics} at its reflective
 * scan filters {@code Modifier.isFinal} || {@code isSynthetic} ||
 * {@code name.startsWith("$$crochet")}). A class whose non-final
 * non-synthetic non-crochet static-field count is zero has nothing
 * for {@code noteStaticAccess} to track — the pre-hook is pure
 * overhead. This class's {@link #ownerMayHaveMutableStatics} returns
 * false for such owners, signalling the caller's transform to skip the
 * wrap.
 *
 * <p><b>Cache shape</b>: process-wide {@link ConcurrentHashMap} keyed by
 * internal name. A sentinel ({@link #CLASS_NOT_FOUND} for "probe failed
 * — be conservative") distinguishes "definitely no mutable statics"
 * from "probe could not resolve the class — treat as if it might".
 * Misses are a one-time cost; hits are lock-free reads.
 *
 * <p><b>Cross-loader safety</b>: keyed by internal name alone. Same
 * tradeoff as {@code SafeClassWriter.SUPER_CACHE} — in the vanishingly
 * rare case of same-named classes with different field layouts across
 * classloaders, the conservative fallback (emit the wrap) is chosen
 * because any uncertainty returns {@link Result#HAS_MUTABLE} from the
 * probe path. No correctness risk, just potential missed elision.
 */
final class StaticFieldAnalysis {

    private StaticFieldAnalysis() {}

    /** Per-internal-name cache. */
    private static final ConcurrentHashMap<String, Result> CACHE = new ConcurrentHashMap<>();

    /** Sentinel for a class-file probe that couldn't resolve the target. */
    private static final Result CLASS_NOT_FOUND = Result.HAS_MUTABLE;

    enum Result {
        /** The class has at least one non-final, non-synthetic, non-{@code $$crochet*} static field. */
        HAS_MUTABLE,
        /** The class's statics are all final/synthetic/crochet-internal — pre-hook elidable. */
        ALL_FINAL
    }

    /**
     * Returns {@code true} iff the owner class <em>may</em> have a
     * mutable static — i.e. the pre-hook <em>must</em> be emitted. A
     * {@code false} return means the owner's statics are proven to all
     * be {@code final}/{@code synthetic}/{@code $$crochet*}; callers
     * can safely skip the pre-hook wrap.
     *
     * <p>The probe is conservative on failure: if the class file cannot
     * be resolved via any loader in the chain, we assume mutable
     * statics exist (return {@code true}) and the wrap is emitted as
     * before. No correctness regression, just a missed elision.
     *
     * @param ownerInternalName the owner class's internal name
     *        (e.g. {@code "org/h2/value/ValueNull"})
     * @param loader the caller's class loader, used to resolve the
     *        owner's class file. May be {@code null} (boot loader).
     */
    static boolean ownerMayHaveMutableStatics(String ownerInternalName, ClassLoader loader) {
        Result cached = CACHE.get(ownerInternalName);
        if (cached != null) {
            return cached == Result.HAS_MUTABLE;
        }
        Result fresh = probe(ownerInternalName, loader);
        CACHE.putIfAbsent(ownerInternalName, fresh);
        return fresh == Result.HAS_MUTABLE;
    }

    private static Result probe(String internalName, ClassLoader loader) {
        byte[] bytes = readClassFile(internalName, loader);
        if (bytes == null) {
            return CLASS_NOT_FOUND;
        }
        MutableStaticDetector det = new MutableStaticDetector();
        try {
            new ClassReader(bytes).accept(det,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException ignored) {
            // Corrupt or unreadable — be conservative.
            return CLASS_NOT_FOUND;
        }
        return det.mutableSeen ? Result.HAS_MUTABLE : Result.ALL_FINAL;
    }

    private static byte[] readClassFile(String internalName, ClassLoader loader) {
        String resource = internalName + ".class";
        ClassLoader effective = loader != null ? loader
                : StaticFieldAnalysis.class.getClassLoader();
        for (ClassLoader l = effective; l != null; l = l.getParent()) {
            try (InputStream in = l.getResourceAsStream(resource)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            } catch (IOException ignored) {
            }
        }
        // System-loader fallback (picks up boot-loaded JDK classes).
        try (InputStream in = ClassLoader.getSystemResourceAsStream(resource)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * ASM visitor that flips {@link #mutableSeen} on first encounter of
     * a non-final non-synthetic non-{@code $$crochet*} static field.
     * Uses {@code SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES} so parsing is
     * cheap — only header + attribute table are read.
     */
    private static final class MutableStaticDetector extends ClassVisitor {
        boolean mutableSeen;

        MutableStaticDetector() {
            super(Opcodes.ASM9);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if ((access & Opcodes.ACC_STATIC) == 0) {
                return null;
            }
            if ((access & Opcodes.ACC_FINAL) != 0) {
                return null;
            }
            if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
                return null;
            }
            if (name != null && name.startsWith("$$crochet")) {
                return null;
            }
            mutableSeen = true;
            // Early-out is not possible with the visitor contract, but
            // the field-visit loop is cheap (max a few dozen fields per
            // class).
            return null;
        }
    }
}
