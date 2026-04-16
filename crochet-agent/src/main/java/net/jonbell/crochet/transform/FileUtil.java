package net.jonbell.crochet.transform;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class FileUtil {

    private FileUtil() {}

    public static File createTemporaryFile(String prefix, String suffix) throws IOException {
        File f = File.createTempFile(prefix, suffix);
        f.deleteOnExit();
        return f;
    }

    public static byte[] checksum(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
