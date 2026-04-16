package net.jonbell.crochet.patch;

import java.util.function.Function;

public class Patcher {

    @SuppressWarnings("unused")
    private final Function<String, byte[]> entryLocator;

    public Patcher(Function<String, byte[]> entryLocator) {
        this.entryLocator = entryLocator;
    }

    public byte[] patch(String name, byte[] bytes) {
        return bytes;
    }
}
