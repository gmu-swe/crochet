package net.jonbell.crochet.runtime;

/**
 * An immutable record describing a single field whose value changed between
 * the live object (or class) and the most-recently checkpointed snapshot.
 *
 * <p>Primitive field values are <em>boxed</em> in the returned record (e.g.
 * {@code int} becomes {@link Integer}). Array fields are treated as opaque
 * references in v1: {@code snapValue} and {@code currentValue} hold the array
 * references themselves, not element-by-element copies. Use
 * {@link java.util.Arrays#equals} externally if you need element comparison.
 *
 * <p>Two {@code FieldDiff} objects are equal iff their {@link #fieldName},
 * {@link #snapValue}, and {@link #currentValue} are all equal (via
 * {@link java.util.Objects#equals}).
 *
 * <!-- TODO(A.4): annotate with @Stable once unit/A.4-compose-kit lands. -->
 *
 * @param fieldName    Declared name of the field (as returned by
 *                     {@link java.lang.reflect.Field#getName()}).
 * @param snapValue    The value the field had at checkpoint time (may be
 *                     {@code null} for reference fields that were null when
 *                     the snapshot was taken, or for a null to non-null
 *                     transition).
 * @param currentValue The current live value of the field (may be
 *                     {@code null} for a non-null to null transition).
 */
public record FieldDiff(String fieldName, Object snapValue, Object currentValue) {

    /**
     * Compact canonical constructor — validates that {@code fieldName} is
     * non-null. Values are allowed to be null (they represent null field
     * values).
     */
    public FieldDiff {
        if (fieldName == null) {
            throw new NullPointerException("fieldName");
        }
    }
}
