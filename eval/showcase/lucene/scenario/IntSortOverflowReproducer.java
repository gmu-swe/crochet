/**
 * IntSortOverflowReproducer — standalone reproducer for the synthetic
 * subtraction-comparator bug in IndexSorter.IntSorter.getDocComparator().
 *
 * The bug: IndexSorter.IntSorter.getDocComparator() uses subtraction
 * (values[docID1] - values[docID2]) instead of Integer.compare(...).
 * Integer arithmetic overflow makes Integer.MIN_VALUE compare as GREATER
 * than any positive value:
 *
 *   Integer.MIN_VALUE - 1  ==  Integer.MAX_VALUE   (overflow, positive)
 *
 * so MIN_VALUE is placed LAST instead of FIRST in ascending order.
 *
 * Scenario structure (TTD-suited):
 *   - Failure manifests at READ time: docs in wrong docID order when
 *     reading back from the merged segment.
 *   - Cause is at WRITE time: getDocComparator() is called during
 *     DocumentsWriterPerThread.flush() to sort the flushed segment.
 *   - Stack frames separate the cause from the symptom:
 *       IndexWriter.addDocuments()
 *         → DocumentsWriterPerThread.flush()
 *           → Sorter.sort(LeafReader)
 *             → IndexSorter.IntSorter.getDocComparator()   ← BUG HERE
 *               → TimSort (invoked via sort comparator)
 *   - Back-stepping from the AssertionError to Sorter.sort() shows the
 *     comparator's return value (+2147483647 where -1 is expected).
 *
 * Usage: java -cp out:lucene-core-9.11.0-SNAPSHOT.jar IntSortOverflowReproducer
 *
 * EXPECTED (correct): [-2147483648, 1, 2147483647]
 * ACTUAL   (buggy):   [1, 2147483647, -2147483648]  (MIN_VALUE last)
 *
 * Observable failure: AssertionError — wrong sort order in flushed segment.
 */

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

public class IntSortOverflowReproducer {

    public static void main(String[] args) throws Exception {
        Path tmpDir = Files.createTempDirectory("lucene-scenario-h2-");
        System.out.println("[reproducer] Index dir: " + tmpDir);

        try (Directory dir = FSDirectory.open(tmpDir)) {
            buildIndex(dir);
            verifyOrder(dir);
        } finally {
            // Clean up temp dir
            for (Path p : (Iterable<Path>) Files.walk(tmpDir)
                    .sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    static void buildIndex(Directory dir) throws IOException {
        IndexWriterConfig iwc = new IndexWriterConfig();
        // Configure ascending int index sort.
        Sort indexSort = new Sort(new SortField("score", SortField.Type.INT));
        iwc.setIndexSort(indexSort);

        try (IndexWriter w = new IndexWriter(dir, iwc)) {
            // Add all three docs in ONE flush (no intermediate commits).
            // This ensures getDocComparator() must sort all three within the
            // single flushed segment — which is where the bug triggers.
            //
            // Insertion order is NOT ascending so the comparator must actually
            // do work (if docs were already in sorted order the sort is a no-op).
            Document d1 = new Document();
            d1.add(new NumericDocValuesField("score", 1));
            d1.add(new StoredField("label", "ONE"));
            w.addDocument(d1);                   // docID 0 before sort

            Document d2 = new Document();
            d2.add(new NumericDocValuesField("score", Integer.MAX_VALUE));
            d2.add(new StoredField("label", "MAX"));
            w.addDocument(d2);                   // docID 1 before sort

            Document d3 = new Document();
            d3.add(new NumericDocValuesField("score", Integer.MIN_VALUE));
            d3.add(new StoredField("label", "MIN"));
            w.addDocument(d3);                   // docID 2 before sort

            // forceMerge/commit triggers DocumentsWriterPerThread.flush()
            // which calls Sorter.sort(reader) → IndexSorter.IntSorter
            // .getDocComparator() to sort the three docs within the segment.
            //
            // With the bug:
            //   compare(docID_MIN, docID_1):
            //     values[MIN] = Integer.MIN_VALUE = -2147483648
            //     values[ONE] = 1
            //     subtraction: -2147483648 - 1 = Integer.MAX_VALUE (overflow)
            //     returns positive → treats MIN_VALUE as GREATER than 1 → WRONG
            w.commit();
        }
    }

    static void verifyOrder(Directory dir) throws IOException {
        try (DirectoryReader r = DirectoryReader.open(dir)) {
            if (r.leaves().size() != 1) {
                throw new AssertionError("Expected exactly 1 segment, got: "
                        + r.leaves().size());
            }
            LeafReader leaf = r.leaves().get(0).reader();
            int maxDoc = leaf.maxDoc();
            System.out.println("[reproducer] maxDoc = " + maxDoc);

            long[] actualValues = new long[maxDoc];
            NumericDocValues ndv = leaf.getNumericDocValues("score");
            int i = 0;
            while (ndv.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
                actualValues[i++] = ndv.longValue();
            }

            // Correct ascending order: MIN_VALUE < 1 < MAX_VALUE
            long[] expected = {Integer.MIN_VALUE, 1, Integer.MAX_VALUE};

            System.out.println("[reproducer] Expected order: " + java.util.Arrays.toString(expected));
            System.out.println("[reproducer] Actual   order: " + java.util.Arrays.toString(actualValues));

            if (!java.util.Arrays.equals(expected, actualValues)) {
                throw new AssertionError(
                        "\n\nWRONG SORT ORDER after segment flush!\n"
                        + "  Expected: " + java.util.Arrays.toString(expected) + "\n"
                        + "  Actual:   " + java.util.Arrays.toString(actualValues) + "\n"
                        + "\n"
                        + "Root cause: IndexSorter.IntSorter.getDocComparator() line 168\n"
                        + "  uses (values[docID1] - values[docID2]) instead of\n"
                        + "  Integer.compare(values[docID1], values[docID2]).\n"
                        + "\n"
                        + "  Integer.MIN_VALUE - 1 = Integer.MAX_VALUE  (overflow)\n"
                        + "  → MIN_VALUE treated as GREATER than 1 → sorts LAST\n"
                        + "\n"
                        + "TTD path:\n"
                        + "  This AssertionError fires at READ time (verifyOrder).\n"
                        + "  The actual bad decision was made at WRITE time:\n"
                        + "    IndexWriter.addDocument\n"
                        + "      → DocumentsWriterPerThread.flush()\n"
                        + "        → Sorter.sort(LeafReader)\n"
                        + "          → IntSorter.getDocComparator()   ← OVERFLOW HERE\n"
                        + "            → TimSort comparator called with (MIN_VALUE, 1)\n"
                        + "               returned +2147483647 (should be -1)\n"
                        + "  Back-step to this comparator call to see the overflow.");
            }

            System.out.println("[reproducer] PASS — sort order is correct.");
        }
    }
}
