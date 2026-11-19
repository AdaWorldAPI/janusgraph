#!/usr/bin/env bash
# Compile lance-graph-java, its graph consumer and this module against the
# JanusGraph built from this checkout, then run the tests.
#
# Needs:
#   - JDK 28 with JEP 401 preview (LGJ_JDK, default /opt/jdks/jdk-28+16)
#   - a lance-graph-java checkout (LGJ_HOME) and its native library (LGJ_SO),
#     built with `cargo build --release` in $LGJ_HOME/native/lgj-abi
#   - JanusGraph's core and in-memory modules installed from this checkout:
#       mvn -q -pl janusgraph-inmemory -am -DskipTests install
#   - JG_CP: the in-memory module's runtime classpath, from
#       mvn -q -pl janusgraph-inmemory dependency:build-classpath -Dmdep.outputFile=FILE
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
J="${LGJ_JDK:-/opt/jdks/jdk-28+16}/bin"
: "${LGJ_HOME:?set LGJ_HOME to a lance-graph-java checkout}"
SO="${LGJ_SO:-$LGJ_HOME/native/lgj-abi/target/release/liblgj_abi.so}"
: "${JG_CP:?set JG_CP to a file holding the janusgraph-inmemory classpath}"
[ -f "$SO" ] || { echo "missing native library $SO" >&2; exit 1; }
jar="$(ls "$root"/janusgraph-inmemory/target/janusgraph-inmemory-*-SNAPSHOT.jar 2>/dev/null | awk 'NR==1')"
[ -n "$jar" ] || { echo "janusgraph-inmemory is not built" >&2; exit 1; }
cp="$(cat "$JG_CP"):$jar"
out="${LGJ_OUT:-$here/out}"
rm -rf "$out" && mkdir -p "$out"
if ! "$J/javac" --release 28 --enable-preview -d "$out" -cp "$cp" \
  $(find "$LGJ_HOME/java/src/main" "$LGJ_HOME/java/src/test" \
         "$LGJ_HOME/consumers/graph/src/main" "$here/src" -name '*.java') \
  >"$out/javac.log" 2>&1; then
  cat "$out/javac.log" >&2
  echo "compile failed" >&2
  exit 1
fi
failed=""
for main in JanusLoweringTest JanusCrossingsTest JanusAllocationTest; do
  "$J/java" --enable-preview --enable-native-access=ALL-UNNAMED -Dlgj.library="$SO" \
    -cp "$out:$cp" "org.janusgraph.lgj.$main" || failed="$failed $main"
done
if [ -n "$failed" ]; then
  echo "FAILED:$failed" >&2
  exit 1
fi
