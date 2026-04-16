/*
 * Derived from Galette (https://github.com/neu-se/galette),
 * Copyright (c) 2024, Katherine Hough and Jonathan Bell — BSD 3-Clause.
 * Port adaptations for Crochet.
 */
package net.jonbell.crochet.instrument;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

public final class CrochetInstrumenter {
    private CrochetInstrumenter() {
        throw new AssertionError();
    }

    public static void main(String[] args) throws IOException {
        CrochetInstrumentation instrumentation = new CrochetInstrumentation();
        instrumentation.configure(new Properties());
        File source = new File(args[0]);
        File destination = new File(args[1]);
        boolean verbose = Boolean.getBoolean("crochet.instrument.verbose");
        String modules = System.getProperty("crochet.instrument.modules", "ALL-MODULE-PATH");
        System.out.printf("Instrumenting %s to %s%n", source, destination);
        long elapsedTime = instrument(source, destination, instrumentation, verbose, modules);
        System.out.printf("Finished instrumentation after %dms%n", elapsedTime);
    }

    public static long instrument(
            File source, File destination, Instrumentation instrumentation, boolean verbose, String modules)
            throws IOException {
        if (!source.exists()) {
            throw new IllegalArgumentException("Source location not found: " + source);
        } else if (destination.exists()) {
            throw new IllegalArgumentException("Destination location already exists: " + destination);
        }
        long startTime = System.currentTimeMillis();
        try {
            if (InstrumentUtil.isModularJvm(source)) {
                JLinkInvoker.invoke(source, destination, instrumentation, modules);
            } else {
                GenericInstrumenter.process(source, destination, instrumentation, verbose);
            }
            return System.currentTimeMillis() - startTime;
        } catch (IOException | InterruptedException e) {
            throw new IOException("Failed to instrument source location", e);
        }
    }
}
