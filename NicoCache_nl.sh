#!/bin/sh

java="${NICOCACHE_JAVA:-java}"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
launcher="$script_dir/NicoCacheLauncher.jar"
if [ ! -f "$launcher" ]; then
  echo "NicoCacheLauncher.jar is not found. Run build-javac.ps1 first: $launcher" >&2
  exit 1
fi

exec "$java" -jar "$launcher" "$@"
