package edu.neu.ccs.prl.crochet.ttd;

import java.util.List;

/**
 * A snapshot of one CPS save point in the current thread's resume deque,
 * as returned by {@link Ttd#captureStack()}.
 *
 * <p>The list returned by {@link Ttd#captureStack()} is ordered innermost
 * frame first: index 0 is the most-recently entered
 * {@link TimeTravelBody}-annotated method, index {@code n-1} is the outermost.
 *
 * <p><b>classMethodLine format:</b> {@code "InternalClassName.nameAndDescriptor:line"},
 * for example {@code "com/example/Foo.doWork(I)V:42"}.  If no entry has been
 * registered for the {@code (methodId, bci)} pair (e.g., because B.3 has not
 * yet been integrated), the sentinel {@code "<methodId=N bci=M>"} is used.
 *
 * <p><b>Experimental.</b>  This API is part of the Crochet TTD prototype and
 * may change without notice.
 * TODO: replace this javadoc note with a proper {@code @Experimental}
 * annotation once unit A.4 (compose-kit) merges and provides one.
 *
 * <p><b>Serialization:</b> use {@link Ttd#serializeStack(List)} for a
 * versioned JSON representation (schema version 1).
 *
 * @param classMethodLine source location label {@code "InternalClassName.nameDesc:line"},
 *                        or the sentinel {@code "<methodId=N bci=M>"} if no registration exists
 * @param locals          ordered list of local variable snapshots; primitive slots appear
 *                        before reference slots, each in ascending slot-index order
 */
public record StackEntry(
        /**
         * Source location label {@code "InternalClassName.nameDesc:line"},
         * or {@code "<methodId=N bci=M>"} if no registration exists.
         */
        String classMethodLine,

        /**
         * Ordered list of local variable snapshots for this frame.
         * Primitive slots appear before reference slots, each in
         * ascending slot-index order.
         */
        List<LocalSnapshot> locals
) {

    /**
     * Serialize this entry as a JSON object.
     *
     * <p>Schema:
     * <pre>
     * {
     *   "classMethodLine": "...",
     *   "locals": [
     *     {"name": "...", "descriptor": "...", "value": "..."},
     *     ...
     *   ]
     * }
     * </pre>
     */
    String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"classMethodLine\":").append(LocalSnapshot.jsonString(classMethodLine));
        sb.append(",\"locals\":[");
        List<LocalSnapshot> ls = locals();
        for (int i = 0; i < ls.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ls.get(i).toJson());
        }
        sb.append("]}");
        return sb.toString();
    }
}
