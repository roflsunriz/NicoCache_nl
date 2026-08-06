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
JAVA_BIN=${JAVA:-java}
JAVA_VERSION=${NICOCACHE_JAVA_VERSION:-25}

command -v "$JAVAC_BIN" >/dev/null 2>&1 || { echo "javacが見つかりません" >&2; exit 1; }
command -v "$JAR_BIN" >/dev/null 2>&1 || { echo "jarが見つかりません" >&2; exit 1; }
command -v "$JAVA_BIN" >/dev/null 2>&1 || { echo "javaが見つかりません" >&2; exit 1; }

case "$JAVA_VERSION" in
  17|21|25) ;;
  *) echo "対応外のTemurin JDKです: $JAVA_VERSION" >&2; exit 1 ;;
esac
JAVA_VERSION_OUTPUT=$("$JAVA_BIN" --version 2>&1) || {
  echo "Javaランタイムのバージョンを取得できません: $JAVA_BIN" >&2
  exit 1
}
case "$JAVA_VERSION_OUTPUT" in
  *Temurin*) ;;
  *) echo "Eclipse Temurin JDK $JAVA_VERSIONが必要です: $JAVA_BIN" >&2; exit 1 ;;
esac
JAVA_MAJOR=$(printf '%s\n' "$JAVA_VERSION_OUTPUT" |
  sed -n '1s/^[^0-9]*\([0-9][0-9]*\).*/\1/p')
JAVAC_MAJOR=$("$JAVAC_BIN" --version 2>&1 |
  sed -n '1s/^[^0-9]*\([0-9][0-9]*\).*/\1/p')
JAR_MAJOR=$("$JAR_BIN" --version 2>&1 |
  sed -n '1s/^[^0-9]*\([0-9][0-9]*\).*/\1/p')
if [ "$JAVA_MAJOR" != "$JAVA_VERSION" ] ||
  [ "$JAVAC_MAJOR" != "$JAVA_VERSION" ] ||
  [ "$JAR_MAJOR" != "$JAVA_VERSION" ]; then
  echo "java、javac、jarはすべてEclipse Temurin JDK $JAVA_VERSIONを使用してください" >&2
  exit 1
fi

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
