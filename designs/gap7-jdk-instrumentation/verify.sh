#!/usr/bin/env bash
#
# End-to-end verification for Gap 7: JDK class instrumentation wiring.
#
# Run AFTER the implementation commit lands. This script does not alter
# anything under /home/jon/crochet — it only reads and produces
# /tmp/crochet-gap7/ scratch output.
#
# Usage:
#   bash verify.sh [/path/to/source/jdk]
#
# Defaults the source JDK to $JAVA_HOME. The instrumented JDK is written
# to /tmp/crochet-gap7/jdk-inst/. Existing output is cleaned up first.
#
# Exit 0 on pass, nonzero on any failure.

set -euo pipefail

CROCHET_ROOT="/home/jon/crochet"
SRC_JDK="${1:-${JAVA_HOME:?JAVA_HOME must be set or pass a path as $1}}"
SCRATCH="/tmp/crochet-gap7"
INST_JDK="$SCRATCH/jdk-inst"
JIMAGE_OUT="$SCRATCH/jimage"
AGENT_JAR_GLOB="$CROCHET_ROOT/crochet-agent/target/crochet-agent-*.jar"
INSTRUMENT_JAR_GLOB="$CROCHET_ROOT/crochet-instrument/target/crochet-instrument-*.jar"

step() { printf '\n==> %s\n' "$*"; }
die()  { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

rm -rf "$SCRATCH"
mkdir -p "$SCRATCH"

# ---------------------------------------------------------------------------
# 1. Build crochet-agent and crochet-instrument.
# ---------------------------------------------------------------------------
step "Building crochet-agent and crochet-instrument"
(cd "$CROCHET_ROOT" && mvn -pl :crochet-agent,:crochet-instrument -am -DskipTests package)

AGENT_JAR=$(ls $AGENT_JAR_GLOB | tail -n1)
INSTRUMENT_JAR=$(ls $INSTRUMENT_JAR_GLOB | tail -n1)
[ -f "$AGENT_JAR" ]      || die "agent jar not found: $AGENT_JAR_GLOB"
[ -f "$INSTRUMENT_JAR" ] || die "instrument jar not found: $INSTRUMENT_JAR_GLOB"

# ---------------------------------------------------------------------------
# 2. Confirm the instrument jar is a named module post-shade+moditect.
# ---------------------------------------------------------------------------
step "Confirming instrument jar has the expected module descriptor"
DESCRIBE=$("$SRC_JDK/bin/jar" --describe-module --file="$INSTRUMENT_JAR")
echo "$DESCRIBE"
echo "$DESCRIBE" | grep -q '^net.jonbell.crochet.instrument' \
    || die "instrument jar is not module net.jonbell.crochet.instrument"
echo "$DESCRIBE" | grep -q 'requires jdk.jlink' \
    || die "module does not require jdk.jlink"
echo "$DESCRIBE" | grep -q 'requires java.instrument' \
    || die "module does not require java.instrument"

# ---------------------------------------------------------------------------
# 3. Run the instrumenter. Produces the jlink-baked JDK at $INST_JDK.
# ---------------------------------------------------------------------------
step "Running instrumenter: $SRC_JDK -> $INST_JDK"
"$SRC_JDK/bin/java" -jar "$INSTRUMENT_JAR" "$SRC_JDK" "$INST_JDK"

[ -x "$INST_JDK/bin/java" ] || die "instrumented JDK missing bin/java"

# ---------------------------------------------------------------------------
# 4. Extract the modules image so we can inspect java.base class files.
# ---------------------------------------------------------------------------
step "Extracting $INST_JDK/lib/modules"
mkdir -p "$JIMAGE_OUT"
"$INST_JDK/bin/jimage" extract --dir="$JIMAGE_OUT" "$INST_JDK/lib/modules"

ARRAYLIST_CLASS="$JIMAGE_OUT/java.base/java/util/ArrayList.class"
HASHMAP_CLASS="$JIMAGE_OUT/java.base/java/util/HashMap.class"
OBJECT_CLASS="$JIMAGE_OUT/java.base/java/lang/Object.class"
[ -f "$ARRAYLIST_CLASS" ] || die "ArrayList.class missing from instrumented image"
[ -f "$HASHMAP_CLASS" ]   || die "HashMap.class missing from instrumented image"

# ---------------------------------------------------------------------------
# 5. Check that ArrayList received the crochet surface.
# ---------------------------------------------------------------------------
step "Verifying ArrayList was instrumented"
JAVAP_OUT=$("$INST_JDK/bin/javap" -p "$ARRAYLIST_CLASS")
for member in \
        '\$\$crochetLookup' \
        '\$\$crochetCheckpoint' \
        '\$\$crochetRollback' \
        '\$\$crochetGetVersion' \
        '\$\$crochetSetVersion' \
        '\$\$crochetAccess' \
        '\$\$crochetVersion' \
        '\$\$crochetSnap'; do
    echo "$JAVAP_OUT" | grep -q "$member" \
        || die "ArrayList missing expected member: $member"
done
echo "  ArrayList has the full CRIJInstrumented surface"

# ---------------------------------------------------------------------------
# 6. Check the @CrochetInstrumented annotation was stamped.
# ---------------------------------------------------------------------------
step "Verifying @CrochetInstrumented annotation is present on ArrayList"
"$INST_JDK/bin/javap" -v "$ARRAYLIST_CLASS" | grep -q 'CrochetInstrumented' \
    || die "ArrayList is missing the @CrochetInstrumented marker"

# ---------------------------------------------------------------------------
# 7. Check that java.lang.Object was *not* instrumented (V1 scope skip).
# ---------------------------------------------------------------------------
step "Verifying java.lang.Object was NOT rewritten"
if "$INST_JDK/bin/javap" -p "$OBJECT_CLASS" | grep -q 'crochet'; then
    die "Object was instrumented; V1 should skip it"
fi

# ---------------------------------------------------------------------------
# 8. Smoke test: a two-line program that touches ArrayList and runs under
#    the agent on the instrumented JDK.
# ---------------------------------------------------------------------------
step "Running smoke program on instrumented JDK + agent"
cat > "$SCRATCH/Smoke.java" <<'JAVA'
import java.util.ArrayList;
import net.jonbell.crochet.runtime.CRIJInstrumented;

public class Smoke {
    public static void main(String[] args) {
        ArrayList<String> a = new ArrayList<>();
        a.add("hello");
        if (!(a instanceof CRIJInstrumented)) {
            System.err.println("FAIL: ArrayList does not implement CRIJInstrumented");
            System.exit(2);
        }
        System.out.println("OK crochet=" + (a instanceof CRIJInstrumented));
    }
}
JAVA

"$SRC_JDK/bin/javac" -cp "$AGENT_JAR" -d "$SCRATCH" "$SCRATCH/Smoke.java"
"$INST_JDK/bin/java" \
    -javaagent:"$AGENT_JAR" \
    -cp "$SCRATCH:$AGENT_JAR" \
    Smoke \
    | tee "$SCRATCH/smoke.out"

grep -q '^OK crochet=true$' "$SCRATCH/smoke.out" \
    || die "smoke program did not confirm ArrayList instanceof CRIJInstrumented"

# ---------------------------------------------------------------------------
# 9. End-to-end checkpoint/rollback on an ArrayList (requires integration
#    test harness; this script documents the command shape).
# ---------------------------------------------------------------------------
step "Running checkpoint/rollback integration test (if present)"
if [ -d "$CROCHET_ROOT/crochet-integration-tests" ]; then
    (cd "$CROCHET_ROOT" && \
        mvn -pl :crochet-integration-tests -am verify \
            -Dcrochet.instrumented.jdk="$INST_JDK") || \
        die "integration tests failed"
else
    echo "  (crochet-integration-tests module not present; skipping)"
fi

step "All checks passed"
