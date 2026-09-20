#!/usr/bin/env bash
# Optional: generate SwiftProtobuf from shared message.proto.
# The app currently ships a hand-written wire-compatible ProtobufCodec.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROTO="$ROOT/api/proto/message.proto"
OUT="$(cd "$(dirname "$0")/.." && pwd)/Data/Sources/Proto/Generated"
mkdir -p "$OUT"
if ! command -v protoc-gen-swift >/dev/null; then
  echo "protoc-gen-swift not found. Install: brew install swift-protobuf"
  echo "Hand-written codec remains the default."
  exit 0
fi
protoc --swift_out="$OUT" -I "$(dirname "$PROTO")" "$PROTO"
echo "Generated into $OUT"
