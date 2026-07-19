#!/bin/sh

java="${NICOCACHE_JAVA:-java}"
opts="${NICOCACHE_OPTS:--Xmx128m}"

if [ "$1" = "debug" ]; then
  opts="$opts -Ddareka.debug=true -Ddareka.logfile=debug.log -ea"
elif [ $# != 0 ]; then
  opts="$*"
fi

# この長いオプションはjava18以降でリフレクションを使うためのもの.
opts21="--add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-exports=java.base/java.lang.invoke=ALL-UNNAMED --add-exports=java.base/jdk.internal.access=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"

cd `dirname $0`

while :
do
  $java $opts21 $opts -jar `basename $0 .sh`.jar
  case $? in
  25) echo "waiting 5 seconds for restarting..."
     sleep 5
     continue;;
  *) echo "exit status is $?"
     break;;
  esac
done

exit 0
