#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"

java="${NICOCACHE_JAVA:-java}"
javac="${NICOCACHE_JAVAC:-javac}"
jar="${NICOCACHE_JAR:-jar}"
java_version="${NICOCACHE_JAVA_VERSION:-25}"
build_source_root="${script_dir}/tools/nicocache-build/src/main/java"
build_jar="${script_dir}/NicoCacheBuild.jar"
bootstrap_classes="${script_dir}/.build/nicocache-build-bootstrap/classes"

case "$java_version" in
  17|21|25) ;;
  *)
    echo "対応外のTemurin JDKです: $java_version（対応: 17、21、25）" >&2
    exit 1
    ;;
esac

java_version_output=$("$java" --version 2>&1) || {
  echo "Javaランタイムのバージョンを取得できません: $java" >&2
  exit 1
}
case "$java_version_output" in
  *Temurin*) ;;
  *)
    echo "Eclipse Temurin JDK $java_versionが必要です: $java" >&2
    exit 1
    ;;
esac
java_major=$(printf '%s\n' "$java_version_output" |
  sed -n '1s/^[^0-9]*\([0-9][0-9]*\).*/\1/p')
javac_major=$("$javac" --version 2>&1 |
  sed -n '1s/^[^0-9]*\([0-9][0-9]*\).*/\1/p')
jar_major=$("$jar" --version 2>&1 |
  sed -n '1s/^[^0-9]*\([0-9][0-9]*\).*/\1/p')
if [ "$java_major" != "$java_version" ] ||
  [ "$javac_major" != "$java_version" ] ||
  [ "$jar_major" != "$java_version" ]; then
  echo "java、javac、jarはすべてEclipse Temurin JDK $java_versionを使用してください" >&2
  exit 1
fi

if [ ! -d "$build_source_root" ] ||
  ! find "$build_source_root" -type f -name '*.java' -print -quit | grep -q .; then
  echo "NicoCacheBuild source is not found: $build_source_root" >&2
  exit 1
fi

needs_bootstrap=false
if [ ! -f "$build_jar" ] ||
  find "$build_source_root" -type f -name '*.java' -newer "$build_jar" -print -quit | grep -q .; then
  needs_bootstrap=true
fi

if [ "$needs_bootstrap" = true ]; then
  mkdir -p "$bootstrap_classes"
  find "$build_source_root" -type f -name '*.java' -exec \
    "$javac" --release 11 -encoding UTF-8 -Xlint:all -Werror \
    -d "$bootstrap_classes" {} +
  "$jar" --create --file "$build_jar" \
    --main-class nicocache.build.BuildMain \
    -C "$bootstrap_classes" nicocache
fi

exec "$java" -jar "$build_jar" "--root=$script_dir" "$@"
