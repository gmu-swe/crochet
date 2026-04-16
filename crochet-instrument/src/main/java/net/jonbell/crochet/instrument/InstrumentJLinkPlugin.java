/*
 * Derived from Galette (https://github.com/neu-se/galette),
 * Copyright (c) 2024, Katherine Hough and Jonathan Bell — BSD 3-Clause.
 * Port adaptations for Crochet.
 */
package net.jonbell.crochet.instrument;

import jdk.tools.jlink.plugin.ResourcePoolEntry;

public class InstrumentJLinkPlugin extends CrochetJLinkPlugin {
    @Override
    public String getName() {
        return "instrument";
    }

    @Override
    public String getDescription() {
        return "Applies instrumentation to the runtime image.";
    }

    @Override
    public Category getType() {
        return Category.MODULEINFO_TRANSFORMER;
    }

    @Override
    protected ResourcePoolEntry transform(ResourcePoolEntry entry) {
        if (entry.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE)
                && entry.path().endsWith(".class")) {
            byte[] instrumented = instrumentation.apply(entry.contentBytes());
            return instrumented == null ? entry : entry.copyWithContent(instrumented);
        }
        return entry;
    }
}
