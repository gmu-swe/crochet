# Checkpoint ROllbaCk via lightweight HEap Traversal (CROCHET)
[![Build Status](https://travis-ci.org/gmu-swe/crochet.svg?branch=master)](https://travis-ci.org/gmu-swe/crochet)

Checkpoint/rollback mechanisms create snapshots of the state of a running application, allowing it to later be restored to that checkpointed snapshot. CROCHET is a system for performing lightweight checkpoint and rollback inside of the JVM. CROCHET works entirely through bytecode rewriting and standard debug APIs, utilizing special proxy objects to perform a lazy heap traversal that starts at the root references and traverses the heap as objects are accessed, copying or restoring state as needed and removing each proxy immediately after it is used. 

The beauty of CROCHET is that you do not need to determine apriori what data should be included in a checkpoint. All that you need to do is ask CROCHET to perform a checkpoint (either starting with all root references or starting with a limited set of references), and then as your application traverses those objects, CROCHET will lazily make copies (on checkpoint, or restore on rollback) each object and its fields.

This repository contains the source for CROCHET. For more information about how CROCHET works and what it could be useful for, please refer to our [ECOOP 2018 paper](http://jonbell.net/publications/crochet), or email [Jonathan Bell](mailto:bellj@gmu.edu).

The authors of this software are [Jonathan Bell](http://jonbell.net) and [Luís Pina](https://www.luispina.me/).

Building
-------
CROCHET is a maven project. Build and install it with `mvn install`. This script will also run built-in integration tests, and create a CROCHET-instrumented JVM.

It is recommended to set the following variables before proceeding with the build process:
 * `JAVA_HOME`: pointing to the JVM you intent to adopt

Also, in case of errors, it may result convenient to split the build process in the following steps:
 * `mvn clean install -DskipTests=true -Dmaven.javadoc.skip=true -B -V`
 * `mvn -X verify`
 
Running
--------
CROCHET works by modifying your application's bytecode. To be complete, CROCHET also modifies the bytecode of JRE-provided classes, too. The first step to using CROCHET is generating an instrumented version of your runtime environment. We have tested CROCHET with both Oracle's HotSpot JVM and OpenJDK's IcedTea JVM (version 8). Running `mvn install` will create an instrumented JVM in the directory `target/jre-inst`.

To run CROCHET with an arbitrary java program, you'll need to use the instrumented JVM, and then specify CROCHET's java agent, JVMTI agent, and boot classpath addon. The appropriate syntax would be:
`target/jre-inst/bin/java -Xmx4G -agentpath:target/libtagging.so -javaagent:target/CRIJ-0.0.1-SNAPSHOT.jar -Xbootclasspath/p:target/CRIJ-0.0.1-SNAPSHOT.jar  ...rest of your java command.	`
 
Perhaps the easiest way to experiment with CROCHET is using the integrated test suite and test suite runner. CROCHET is a maven project, and is configured to run validation tests when you invoke `mvn verify`. You can inspect the test cases and add new ones (just make sure that the test class names end with `ITCase`. You can then use the `CheckpointRollbackAgent` API to checkpoint or rollback objects. The easiest way is to (as most of the test cases do) first generate a new and valid version number to use (e.g. `int v = getNewVersionForCheckpoint();`) and then simply call `checkpoint` or `rollback` on objects that you are interested in testing on. For instance, the test `ArrayCheckpointRollbackITCase#testPrimitiveArray` tests the correct handling of primitive arrays in CROCHET (which require special handling compared to other objects).

License
-------
This software is released under the MIT license.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
