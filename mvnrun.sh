#!/bin/bash

# resolve links - $0 may be a softlink
PRG="$0"

while [ -h "$PRG" ] ; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=$(dirname "$PRG")/"$link"
  fi
done

PRGDIR=$(dirname "$PRG")

java -jar $PRGDIR/mvnrun.jar $*

#java -jar target/mvnrun.jar $*
#java -cp $(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) -jar target/mvnrun.jar $*
