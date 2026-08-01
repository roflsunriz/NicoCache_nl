#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"

java="${NICOCACHE_JAVA:-java}"
javac="${NICOCACHE_JAVAC:-javac}"
jar="${NICOCACHE_JAR:-jar}"
build_source_root="${script_dir}/tools/nicocache-build/src/main/java"
build_jar="${script_dir}/NicoCacheBuild.jar"
bootstrap_classes="${script_dir}/.build/nicocache-build-bootstrap/classes"

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
