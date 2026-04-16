#!/usr/bin/env bash
# Convenience wrapper: build everything, run all configs, save results.
set -euo pipefail

SPIKE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SPIKE_DIR"

: "${JAVA_HOME:=/usr/lib/jvm/java-21-openjdk-amd64}"
export JAVA_HOME

echo "# java -version" | tee results.txt
"$JAVA_HOME/bin/java" -version 2>&1 | tee -a results.txt
echo                                    | tee -a results.txt

make all >/dev/null

for cfg in base loaded always region; do
    echo "# config=$cfg" | tee -a results.txt
    if [[ "$cfg" == "base" ]]; then
        "$JAVA_HOME/bin/java" -cp . Bench base             2>&1 | tee -a results.txt
    else
        "$JAVA_HOME/bin/java" -agentpath:./libspike.so \
            -Djava.library.path=. -cp . Bench "$cfg"       2>&1 | tee -a results.txt
    fi
    echo | tee -a results.txt
done

make printcomp >/dev/null
echo "# printcomp logs written to printcomp-{base,loaded,always}.log"
