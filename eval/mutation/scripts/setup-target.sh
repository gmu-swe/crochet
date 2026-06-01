#!/usr/bin/env bash
# Clone + build Apache Commons Lang 3.12.0 as the mutation target, and dump
# the test-scope classpath that the harness needs.
#
# Idempotent: skips clone if the tree already exists.
set -eu
cd "$(dirname "$0")/.."
source scripts/env.sh

if [ ! -d "$TARGET_DIR" ]; then
    mkdir -p "$(dirname "$TARGET_DIR")"
    git clone --depth 1 --branch rel/commons-lang-3.12.0 \
        https://github.com/apache/commons-lang.git "$TARGET_DIR"
fi

cd "$TARGET_DIR"
# Compile main + test classes (test-classes are needed because the harness
# loads FractionTest as a JUnit-Jupiter test class from the system classloader).
JAVA_HOME="$JAVA_HOME" mvn -q -DskipTests test-compile

# Dump test-scope classpath (Surefire deps + Jupiter runtime + Hamcrest)
JAVA_HOME="$JAVA_HOME" mvn -q dependency:build-classpath \
    -Dmdep.outputFile="$TARGET_CP_FILE" \
    -DincludeScope=test

echo "OK target ready: $TARGET_DIR"
echo "OK classpath dumped: $TARGET_CP_FILE"
