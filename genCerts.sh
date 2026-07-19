#!/bin/sh

java="${NICOCACHE_JAVA:-java}"
DOMAINS='*.nicovideo.jp *.ce.nicovideo.jp *.sl.nicovideo.jp *.sv.nicovideo.jp *.live.nicovideo.jp *.live2.nicovideo.jp *.nicoad.nicovideo.jp *.seiga.nicovideo.jp *.ch.nicovideo.jp *.ads.nicovideo.jp *.i.nicovideo.jp *.account.nicovideo.jp *.upload.nicovideo.jp *.search.nicovideo.jp *.news.nicovideo.jp *.api.nicovideo.jp *.domand.nicovideo.jp *.smilevideo.jp *.nimg.jp *.cdn.nimg.jp *.video.nimg.jp *.dmc.nico *.nvcomment.nicovideo.jp'

cd `dirname $0`

for libfile in "lib/bcprov.jar" "lib/bcpkix.jar" "lib/bcutil.jar"; do
  if [ ! -e $libfile ]; then
    echo "$libfile is not found."
    echo
    echo "NicoCacheCA.jar require lib/bcprov.jar and lib/bcpkix.jar and lib/bcutil.jar"
    echo "Please download Bouncy Castle:"
    echo "  https://www.bouncycastle.org/latest_releases.html"
    exit 1
  fi
done

$java -jar NicoCacheCA.jar $DOMAINS

