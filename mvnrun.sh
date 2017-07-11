#!/bin/bash

#mvn dependency:build-classpath -Dmdep.outputFile=cp.txt &>/dev/null
#java -cp $(cat cp.txt) -jar target/mvnrun.jar $*

java -cp $(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) -jar target/mvnrun.jar $*
