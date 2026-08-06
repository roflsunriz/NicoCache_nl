#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$ROOT/build.sh"
TEST_CLASSES_ROOT="$ROOT/build/test-classes"
JAVAC_BIN=${JAVAC:-javac}
JAVA_BIN=${JAVA:-java}
rm -rf "$TEST_CLASSES_ROOT"
mkdir -p "$TEST_CLASSES_ROOT"
find "$ROOT/src/test/java" -type f -name '*.java' -print > "$ROOT/build/test-sources.txt"
"$JAVAC_BIN" --release 11 -encoding UTF-8 -Xlint:all \
    -cp "$ROOT/build/classes" \
    -d "$TEST_CLASSES_ROOT" \
    @"$ROOT/build/test-sources.txt"
"$JAVA_BIN" -cp "$ROOT/build/classes:$TEST_CLASSES_ROOT" nicocache.cmaftomp4.CmafToMp4Tests
