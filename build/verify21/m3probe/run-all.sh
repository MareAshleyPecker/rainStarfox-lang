#!/usr/bin/env bash
# Reproduces every probe in this directory. Usage: bash run-all.sh [JAVA_HOME]
JAVA_HOME="${1:-${JAVA_HOME:-C:/Program Files/Zulu/zulu-25}}"
JAVA="$JAVA_HOME/bin/java"; JAVAC="$JAVA_HOME/bin/javac"; JAVAP="$JAVA_HOME/bin/javap"
JFLAGS="-J-Duser.language=en -encoding UTF-8"
ASM="lib/asm-9.9.1.jar"

rm -rf classes out bad
mkdir -p classes out bad

"$JAVA" -version 2>&1
"$JAVAC" $JFLAGS -cp "$ASM" -d classes src/Gen.java src/GenBad.java
"$JAVA" -cp "classes;$ASM" Gen out
"$JAVA" -cp "classes;$ASM" GenBad bad

"$JAVAC" $JFLAGS -cp out -d out src/Reader.java src/Diag.java src/UseJava.java
"$JAVAC" $JFLAGS -cp "bad;out" -d bad src/Reader2.java

echo "########## javap -v -p out/MarkerR.class ##########"; "$JAVAP" -v -p out/MarkerR.class
echo "########## javap -v -p out/DemoR.class ##########";  "$JAVAP" -v -p out/DemoR.class
echo "########## java -cp out Reader ##########";  "$JAVA" -cp out Reader
echo "########## java -cp out Diag ##########";    "$JAVA" -cp out Diag
echo "########## java -cp out UseJava ##########"; "$JAVA" -cp out UseJava
echo "########## java -cp bad Reader2 ##########"; "$JAVA" -cp "bad;out" Reader2
