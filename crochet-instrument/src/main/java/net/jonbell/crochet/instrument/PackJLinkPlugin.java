/*
 * Derived from Galette (https://github.com/neu-se/galette),
 * Copyright (c) 2024, Katherine Hough and Jonathan Bell — BSD 3-Clause.
 * Port adaptations for Crochet.
 */
package net.jonbell.crochet.instrument;

import jdk.tools.jlink.plugin.ResourcePoolEntry;

public class PackJLinkPlugin extends CrochetJLinkPlugin {
    @Override
    public String getName() {
        return "pack";
    }

    @Override
    public String getDescription() {
        return "Packs classes into the java.base module";
    }

    @Override
    public Category getType() {
        return Category.FILTER;
    }

    @Override
    protected ResourcePoolEntry transform(ResourcePoolEntry entry) {
        if (entry.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE)
                && entry.path().endsWith(".class")) {
            if (entry.path().endsWith("module-info.class")) {
                if (entry.path().startsWith("/java.base")) {
                    // Transform java.base's module-info.class file and pack core classes into java.base
                    return packer.pack(entry);
                }
            }
        }
        return entry;
    }
}
