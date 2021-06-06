#!/bin/bash

set -eo pipefail

sbtver="1.5.3"
sbtjar="sbt-launch-$sbtver.jar"
sbtsha128="1ca2d0ee419a1f82f512f2aa8556d6e262b37961"

sbtrepo="https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch"

if [ ! -f "sbt-launch.jar" ]; then
  echo "downloading $PWD/$sbtjar" 1>&2
  if ! curl --location --silent --fail -o "sbt-launch.jar" "$sbtrepo/$sbtver/$sbtjar"; then
    exit 1
  fi
fi

checksum=`openssl dgst -sha1 sbt-launch.jar | awk '{ print $2 }'`
if [ "$checksum" != $sbtsha128 ]; then
  echo "bad $PWD/sbt-launch.jar.  delete $PWD/sbt-launch.jar and run $0 again."
  exit 1
fi

[ -f ~/.sbtconfig ] && . ~/.sbtconfig

# the -DSKIP_SBT flag is set to skip tests that shouldn't be run with sbt.
java -ea                          \
  $SBT_OPTS                       \
  $JAVA_OPTS                      \
  -DSKIP_SBT=1                    \
  -jar "sbt-launch.jar" "$@"
