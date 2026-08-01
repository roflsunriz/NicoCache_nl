#!/bin/sh

java="${NICOCACHE_JAVA:-java}"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir" || exit 1

if [ ! -e "certificate-targets.txt" ]; then
  echo "certificate-targets.txt is empty or not found."
  exit 1
fi
if [ ! -e "NicoCacheCA.jar" ]; then
  echo "NicoCacheCA.jar is not found."
  exit 1
fi

for libfile in "lib/bcprov.jar" "lib/bcpkix.jar" "lib/bcutil.jar"; do
  if [ ! -e "$libfile" ]; then
    echo "$libfile is not found."
    echo
    echo "NicoCacheCA.jar require lib/bcprov.jar and lib/bcpkix.jar and lib/bcutil.jar"
    echo "Please download Bouncy Castle:"
    echo "  https://www.bouncycastle.org/latest_releases.html"
    exit 1
  fi
done

exec "$java" -jar NicoCacheCA.jar --headless \
  --targets-file="$script_dir/certificate-targets.txt"
