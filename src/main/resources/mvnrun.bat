@echo off

@rem mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
java -jar target\mvnrun.jar %*
