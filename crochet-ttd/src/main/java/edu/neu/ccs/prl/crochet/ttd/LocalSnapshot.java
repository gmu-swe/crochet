package edu.neu.ccs.prl.crochet.ttd;

/**
 * A snapshot of a single local variable at a CPS save point.
 *
 * <p>Returned by {@link Ttd#captureStack()} as part of a {@link StackEntry}.
 * Each instance corresponds to one slot in either the {@code prims} or
 * {@code refs} array of the underlying {@link ResumeFrame}.
 *
 * <p><b>Name resolution:</b> when the class was compiled with debug info
 * (i.e., a {@code LocalVariableTable} attribute is present and B.3 has
 * registered the slot info via {@link Ttd#registerMethodLine}), {@code name}
 * is the Java source name of the local (e.g., {@code "count"}).  When no
 * info is registered — either because the class was compiled with
 * {@code -g:none} or because B.3 has not yet been integrated — the fallback
 * name {@code "$slotN"} is used, where {@code N} is the zero-based slot index
 * within its array ({@code prims} or {@code refs}).
 *
 * <p><b>Type descriptor:</b> follows the JVM {@code FieldDescriptor} grammar
 * (e.g., {@code "I"} for {@code int}, {@code "Ljava/lang/String;"} for
 * {@link String}).  Falls back to {@code "?"} when type info is not registered.
 *
 * <p><b>Value encoding:</b> primitive values are the {@code long} slot content
 * formatted by {@link Long#toString}; reference values are formatted by
 * {@link String#valueOf} (i.e., {@code obj.toString()} or {@code "null"}).
 * Values are opaque strings intended for human display, not round-trip
 * deserialization.
 *
 * <p><b>Experimental.</b>  This API is part of the Crochet TTD prototype and
 * may change without notice.
 * TODO: replace this javadoc note with a proper {@code @Experimental}
 * annotation once unit A.4 (compose-kit) merges and provides one.
 *
 * @param name           source name of the local, or {@code "$slotN"} if the LocalVariableTable
 *                       is absent
 * @param typeDescriptor JVM field-descriptor of the local type, or {@code "?"} if unknown;
 *                       examples: {@code "I"} (int), {@code "Ljava/lang/Object;"} (Object)
 * @param value          human-readable value string; primitives formatted as decimal long,
 *                       references via {@link String#valueOf}, null refs as {@code "null"}
 */
public record LocalSnapshot(
        /** Source name, or {@code "$slotN"} if the LocalVariableTable is absent. */
        String name,

        /**
         * JVM field-descriptor of the local type, or {@code "?"} if unknown.
         * Examples: {@code "I"} (int), {@code "Ljava/lang/Object;"} (Object).
         */
        String typeDescriptor,

        /**
         * Human-readable value string.  Primitives: decimal {@code long} representation.
         * References: {@code String.valueOf(ref)}.  Null references: {@code "null"}.
         */
        String value
) {

    /**
     * Serialize this snapshot as a JSON object fragment (no surrounding braces).
     * Used by {@link StackEntry#toJson()}.
     *
     * <p>Format:
     * <pre>{"name":"...","descriptor":"...","value":"..."}</pre>
     *
     * <p>String fields are JSON-escaped (backslash, double-quote, and control
     * characters).
     */
    String toJson() {
        return "{\"name\":" + jsonString(name)
                + ",\"descriptor\":" + jsonString(typeDescriptor)
                + ",\"value\":" + jsonString(value)
                + "}";
    }

    /**
     * Minimal JSON string escaper: wraps {@code s} in double-quotes and
     * escapes {@code \"}, {@code \\}, and ASCII control characters
     * ({@code \n}, {@code \r}, {@code \t}; others as a 6-character unicode escape).
     */
    static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
