#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE_ROOT="$ROOT/src/main/java"
RESOURCE_ROOT="$ROOT/src/main/resources"
BUILD_ROOT="$ROOT/build"
CLASSES_ROOT="$BUILD_ROOT/classes"
DIST_ROOT="$ROOT/dist"
JAR_PATH="$DIST_ROOT/nico-cmaf-to-mp4.jar"
JAVAC_BIN=${JAVAC:-javac}
JAR_BIN=${JAR_TOOL:-jar}

command -v "$JAVAC_BIN" >/dev/null 2>&1 || { echo "javacが見つかりません" >&2; exit 1; }
command -v "$JAR_BIN" >/dev/null 2>&1 || { echo "jarが見つかりません" >&2; exit 1; }

rm -rf "$CLASSES_ROOT"
mkdir -p "$CLASSES_ROOT" "$DIST_ROOT"
SOURCE_LIST="$BUILD_ROOT/main-sources.txt"
find "$SOURCE_ROOT" -type f -name '*.java' -print > "$SOURCE_LIST"
test -s "$SOURCE_LIST" || { echo "Javaソースが見つかりません: $SOURCE_ROOT" >&2; exit 1; }

"$JAVAC_BIN" --release 11 -encoding UTF-8 -Xlint:all -d "$CLASSES_ROOT" "@$SOURCE_LIST"
if [ -d "$RESOURCE_ROOT" ]; then
    cp -R "$RESOURCE_ROOT"/. "$CLASSES_ROOT"/
fi
"$JAR_BIN" --create --file "$JAR_PATH" --main-class nicocache.cmaftomp4.Main -C "$CLASSES_ROOT" .
echo "作成しました: $JAR_PATH"
