#!/usr/bin/env bash
# JVM tests for the launcher's pure-Java logic (phrase matcher, JARVIS persona, config.json). No TV or emulator.
# Needs a JDK 17 and org.json (json-20240303.jar; ORG_JSON=<path to it>, or it is fetched from Maven Central once).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
LIB="$ROOT/tests/.tmp"
mkdir -p "$LIB/classes"
JAR="${ORG_JSON:-$LIB/json-20240303.jar}"
if [ ! -f "$JAR" ]; then
  curl -sSL -o "$JAR" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
  echo "0ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a  $JAR" | sha1sum -c - >/dev/null 2>&1 \
    || shasum -a 1 "$JAR" | grep -q 0ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a || { echo "org.json checksum mismatch"; exit 1; }
fi
SRC="$ROOT/launcher/app/src/io/freemytv"
"${JAVA_BIN}javac" -nowarn -encoding UTF-8 -cp "$JAR" -d "$LIB/classes" \
  "$SRC/Intents.java" "$SRC/Persona.java" "$ROOT/tests/launcher/LauncherLogicTest.java"
"${JAVA_BIN}java" -cp "$LIB/classes:$JAR" io.freemytv.LauncherLogicTest "$ROOT"
