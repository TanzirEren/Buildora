#!/bin/bash
# Annotation-processing regression tests. Runs on a desktop JVM that has NO java.compiler module,
# so the JSR 269 API comes from Buildora's own sources (app/src/main/java/javax), exactly like on Android.
#
# Needs (see the project notes): TC=<dir with android.jar + lambda-stubs.jar + aapt2>, ECJ jar, R8/D8 jar (bundletool-all), org.json jar.
# Optional: M2=<offline Maven fixture dir> (tests/apt/make_m2.py builds one from real AutoValue/AutoService jars).
set -e
: "${TC:?set TC}"; : "${ECJ_JAR:?set ECJ_JAR}"; : "${R8_JAR:?set R8_JAR}"; : "${JSON_JAR:?set JSON_JAR}"
W=${WORK:-/tmp/apt-tests}; rm -rf "$W"; mkdir -p "$W/api" "$W/cls"
cd "$(dirname "$0")/.."
ecj() { java -cp "$ECJ_JAR" org.eclipse.jdt.internal.compiler.batch.Main "$@" 2>&1 | grep -v "^Picked up" || true; }
find app/src/main/java/javax -name '*.java' > "$W/api.txt"
ecj -1.8 -proc:none -nowarn -encoding UTF-8 -bootclasspath "$TC/android.jar" -classpath "$TC/lambda-stubs.jar" -d "$W/api" @"$W/api.txt"
ecj -1.8 -proc:none -nowarn -encoding UTF-8 -bootclasspath "$TC/android.jar" -classpath "$TC/lambda-stubs.jar:$R8_JAR:$ECJ_JAR:$JSON_JAR:$W/api" \
    -sourcepath app/src/main/java -d "$W/cls" tests/AptTest.java
TC="$TC" API="$W/api" WORK="$W/run" M2="$M2" java --limit-modules java.base,java.xml,java.logging,java.naming,java.sql,java.management,jdk.unsupported \
    -cp "$W/cls:$W/api:$ECJ_JAR:$R8_JAR:$JSON_JAR" AptTest
