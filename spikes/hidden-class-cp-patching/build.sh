#!/usr/bin/env bash
# Compile the PoC. Needs JDK 15+ for Lookup.defineHiddenClass (Java 15 final).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

mkdir -p build

CP="lib/asm-9.7.1.jar:lib/asm-tree-9.7.1.jar:lib/asm-commons-9.7.1.jar"

# Compile everything under src/. We leave DumpTemplate.java in place because
# it's the one you'd use to inspect raw CP layout with `javap -v build/Template.class`.
javac -cp "$CP" -d build \
    src/Template.java \
    src/RoleIface.java \
    src/RuntimeAgent.java \
    src/UserA.java \
    src/UserB.java \
    src/TemplateBytes.java \
    src/SpecializerB.java \
    src/Main.java \
    src/DumpTemplate.java \
    src/DumpSpecialized.java

echo "build ok"
