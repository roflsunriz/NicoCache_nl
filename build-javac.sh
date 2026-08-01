#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"

java="${NICOCACHE_JAVA:-java}"
javac="${NICOCACHE_JAVAC:-javac}"
jar="${NICOCACHE_JAR:-jar}"
build_source="${script_dir}/tools/nicocache-build/src/main/java/nicocache/build/BuildMain.java"
build_jar="${script_dir}/NicoCacheBuild.jar"
bootstrap_classes="${script_dir}/.build/nicocache-build-bootstrap/classes"

if [ ! -f "$build_source" ]; then
  echo "NicoCacheBuild source is not found: $build_source" >&2
  exit 1
fi

needs_bootstrap=false
if [ ! -f "$build_jar" ] || [ "$build_source" -nt "$build_jar" ]; then
  needs_bootstrap=true
fi

if [ "$needs_bootstrap" = true ]; then
  mkdir -p "$bootstrap_classes"
  "$javac" --release 11 -encoding UTF-8 -Xlint:all -Werror \
    -d "$bootstrap_classes" "$build_source"
  "$jar" --create --file "$build_jar" \
    --main-class nicocache.build.BuildMain \
    -C "$bootstrap_classes" nicocache
fi

exec "$java" -jar "$build_jar" "--root=$script_dir" "$@"
