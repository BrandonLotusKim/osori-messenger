#!/usr/bin/env bash
# 오소리 메신저 — Protobuf 양쪽 재생성 스크립트
#
# 단일 진실 원천: proto/messenger.proto
#   - Kotlin 서버: protobuf-gradle-plugin 이 server/build/generated/source/proto/main/java/ 로 출력
#   - C# 클라  : Grpc.Tools 가 client-csharp/src/OsoriClient.Core/obj/Debug/net8.0/Messenger.cs 로 출력
#
# 사용:
#   ./scripts/regen-proto.sh           # 양쪽 다 재생성
#   ./scripts/regen-proto.sh --kotlin  # 서버만
#   ./scripts/regen-proto.sh --csharp  # 클라만
#
# 종료 코드: 0 성공 / 1 실패

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTO_FILE="$ROOT_DIR/proto/messenger.proto"

if [[ ! -f "$PROTO_FILE" ]]; then
  echo "[regen-proto] FAIL — proto 파일을 찾을 수 없습니다: $PROTO_FILE" >&2
  exit 1
fi

DO_KOTLIN=1
DO_CSHARP=1

case "${1:-}" in
  --kotlin) DO_CSHARP=0 ;;
  --csharp) DO_KOTLIN=0 ;;
  --help|-h)
    grep '^#' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
esac

echo "[regen-proto] proto = $PROTO_FILE"
echo "[regen-proto] proto bytes = $(wc -c < "$PROTO_FILE")"

if (( DO_KOTLIN )); then
  echo "[regen-proto] (1/2) Kotlin 서버 generateProto..."
  pushd "$ROOT_DIR/server" > /dev/null
  if [[ -n "${JAVA_HOME:-}" ]]; then
    echo "[regen-proto]      JAVA_HOME=$JAVA_HOME"
  fi
  ./gradlew.bat generateProto --quiet
  popd > /dev/null
  echo "[regen-proto] (1/2) Kotlin OK"
fi

if (( DO_CSHARP )); then
  echo "[regen-proto] (2/2) C# 클라 dotnet build (Grpc.Tools 가 proto 컴파일 트리거)..."
  DOTNET="${DOTNET:-C:/Program Files/dotnet/dotnet.exe}"
  if [[ ! -x "$DOTNET" ]] && command -v dotnet >/dev/null 2>&1; then
    DOTNET="dotnet"
  fi
  "$DOTNET" build "$ROOT_DIR/client-csharp/src/OsoriClient.Core/OsoriClient.Core.csproj" \
    --nologo --verbosity quiet
  echo "[regen-proto] (2/2) C# OK"
fi

echo "[regen-proto] DONE — proto 양쪽 재생성 완료."
