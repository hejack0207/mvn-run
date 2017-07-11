#!/bin/bash

mvn dependency:build-classpath -Dmdep.outputFile=cp.txt &>/dev/null
java -cp $(cat cp.txt) -jar target/mvnrun.jar $*
