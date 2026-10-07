#!/usr/bin/env bash
# 변환 엔진(hwpConverter + 검증 도구)을 build/engine 에 빌드한다.
# 결과: build/engine/hwpConverter.jar, build/engine/hwpxtool.jar, build/engine/lib/*.jar
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/engine"
WORK="$ROOT/build/engine-work"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
  *) SEP=":" ;;
esac

mkdir -p "$OUT/lib" "$WORK/classes" "$WORK/tool-classes"

cd "$ROOT/engine/hwpConverter"
find src -name "*.java" > "$WORK/sources.txt"
javac --release 8 -nowarn -encoding UTF-8 -d "$WORK/classes" -cp "lib/*" @"$WORK/sources.txt"
# 템플릿 등 리소스 파일도 jar에 포함
(cd src && find . -type f ! -name "*.java" -exec cp --parents {} "$WORK/classes/" \;)
jar cf "$OUT/hwpConverter.jar" -C "$WORK/classes" .
cp lib/*.jar "$OUT/lib/"

cd "$ROOT/engine/tool"
javac --release 8 -nowarn -encoding UTF-8 -d "$WORK/tool-classes" \
  -cp "$OUT/hwpConverter.jar${SEP}$OUT/lib/*" HwpxToHwpTool.java
jar cf "$OUT/hwpxtool.jar" -C "$WORK/tool-classes" .

echo "engine built: $OUT"
