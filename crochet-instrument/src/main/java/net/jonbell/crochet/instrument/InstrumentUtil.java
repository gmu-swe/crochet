/*
 * Derived from Galette (https://github.com/neu-se/galette),
 * Copyright (c) 2024, Katherine Hough and Jonathan Bell — BSD 3-Clause.
 * Port adaptations for Crochet.
 */
package net.jonbell.crochet.instrument;

import java.io.File;

public final class InstrumentUtil {
    private InstrumentUtil() {
        throw new AssertionError();
    }

    public static File javaHomeToBin(File javaHome) {
        return new File(javaHome, "bin");
    }

    public static File javaHomeToJavaExec(File javaHome) {
        return new File(javaHomeToBin(javaHome), "java");
    }

    public static File javaHomeToJLinkExec(File javaHome) {
        return new File(javaHomeToBin(javaHome), "jlink");
    }

    public static boolean isJavaHome(File directory) {
        return javaHomeToJavaExec(directory).isFile();
    }

    public static boolean isModularJvm(File javaHome) {
        return isJavaHome(javaHome) && javaHomeToJLinkExec(javaHome).isFile();
    }

    public static File getClassPathElement(Class<?> clazz) {
        return new File(
                clazz.getProtectionDomain().getCodeSource().getLocation().getPath());
    }
}
