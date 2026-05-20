# Showcase Target: Apache Lucene 9.11.0

## Chosen target

**Apache Lucene 9.11.0** — the Java full-text search library.

Release tag: `releases/lucene/9.11.0`  
Upstream: https://github.com/apache/lucene  
Download: https://archive.apache.org/dist/lucene/java/9.11.0/lucene-9.11.0-src.tgz

## Why Lucene

Lucene is the canonical non-trivial Java library benchmark:

1. **Algorithmic heap diversity** — index segments, posting lists, FSTs, and codec
   buffers represent a cross-section of real-world heap shapes. Checkpoint/rollback
   exercises deep object graphs (IndexReader → LeafReader → StoredFields → …) as
   well as flat arrays (doc-id arrays, term vectors).

2. **No JNI / native dependencies in core** — `lucene-core` compiles and runs
   purely on the JVM. No platform-specific setup beyond a standard Java 21 install.

3. **Strong test suite** — `lucene-core` ships ~500 JUnit tests that exercise
   codecs, query evaluation, concurrent indexing, and merge policies. A green
   baseline is easy to reproduce and fails loudly when something is wrong.

4. **Practical relevance** — Lucene underpins Elasticsearch, Solr, and many
   production search stacks. Demonstrating Crochet compatibility is a meaningful
   existence proof that real workloads work.

5. **No checkpoint API calls in upstream code** — Lucene does not call
   Crochet APIs, making it a pure compatibility test: does Crochet's bytecode
   transformation break anything an unmodified library does?

## Version pin rationale

9.11.0 (released 2024-04) is the newest GA release that builds cleanly under
Java 21. Later releases (10.x) require Java 21 as a minimum at build time, which
matches `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, but the Gradle build
script for 10.x generates sources that trigger `MethodTooLargeException` under
Crochet's PUTFIELD-hook expansion for `ICUCollationKeyAnalyzer`. The 9.11.0
branch avoids this class entirely in `lucene-core`, keeping the demo
representative without codec-specific workarounds.

## Alternatives considered

| Candidate | Reason not chosen |
|-----------|------------------|
| H2 Database 2.2.x | SQL parser methods exceed 64 KB after hook expansion — requires per-class `@CrochetSkip` annotations on user-visible entry points |
| HikariCP 5.x | Correct but trivially small test suite (~40 tests); coverage story weaker |
| Jackson 2.17 | Annotation processing integration with Crochet's APT conflicts with Jackson's own annotation processor, complicating build setup |
