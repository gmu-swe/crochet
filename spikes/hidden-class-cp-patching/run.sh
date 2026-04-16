#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

CP="build:lib/asm-9.7.1.jar:lib/asm-tree-9.7.1.jar:lib/asm-commons-9.7.1.jar"
# --add-opens ... needed only because we use sun.misc.Unsafe to demonstrate
# the klass-swap half of CROCHET. Dropping the klass-swap demo would make
# this flag unnecessary for a pure "defineHiddenClass works" scenario.
exec java \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    -cp "$CP" Main
