#!/usr/bin/env bash
set -euo pipefail

# Builds and merges the official GPL-3.0 gomobile bridge for the two Android
# ABIs supported by FOXConnect. Requirements: Go, JDK 17 and Android NDK r28.
VERSION="${LIBBOX_VERSION:-v1.14.0}"
EXPECTED_COMMIT="${LIBBOX_COMMIT:-0b8995879f29a9b98ee027bc17b75e101445b238}"
ARCHES="${LIBBOX_ARCHES:-arm64 arm}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/.cache/libbox-src"
OUTPUT="$ROOT/core/engine/libs/libbox.aar"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28}"
: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-}}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT (or ANDROID_HOME) to the Android SDK}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export GOPATH="${GOPATH:-$HOME/.cache/go}"
export GOCACHE="${GOCACHE:-$HOME/.cache/go-build}"
export TMPDIR="${TMPDIR:-$HOME/.cache/libbox-tmp}"
mkdir -p "$GOPATH" "$GOCACHE" "$TMPDIR"

command -v go >/dev/null || { echo "Go is required" >&2; exit 1; }
command -v java >/dev/null || { echo "JDK 17 is required" >&2; exit 1; }
[[ -f "$ANDROID_NDK_HOME/source.properties" ]] || { echo "Invalid Android NDK path" >&2; exit 1; }
grep -Eq '^Pkg\.Revision[[:space:]]*=[[:space:]]*28\.' "$ANDROID_NDK_HOME/source.properties" || {
    echo "FOXConnect's pinned libbox build requires Android NDK r28" >&2
    exit 1
}

rm -rf "$WORK"
git clone --depth 1 --branch "$VERSION" https://github.com/SagerNet/sing-box.git "$WORK"
pushd "$WORK" >/dev/null
ACTUAL_COMMIT="$(git rev-parse HEAD)"
[[ "$ACTUAL_COMMIT" == "$EXPECTED_COMMIT" ]] || {
    echo "Refusing unexpected sing-box commit: expected $EXPECTED_COMMIT, got $ACTUAL_COMMIT" >&2
    exit 1
}
make lib_install
export PATH="$PATH:$GOPATH/bin"
mkdir -p libbox-inputs
for arch in $ARCHES; do
    echo "==> Building libbox for android/$arch"
    go run ./cmd/internal/build_libbox -target android -platform "android/$arch"
    mkdir -p "libbox-inputs/$arch"
    mv libbox.aar "libbox-inputs/$arch/libbox.aar"
    mv libbox-legacy.aar "libbox-inputs/$arch/libbox-legacy.aar"
done

echo "==> Merging ABI-specific AAR files"
go run ./cmd/internal/merge_aar -output libbox.aar libbox-inputs/*/libbox.aar
popd >/dev/null

install -Dm644 "$WORK/libbox.aar" "$OUTPUT"
(
    cd "$(dirname "$OUTPUT")"
    sha256sum "$(basename "$OUTPUT")" > "$(basename "$OUTPUT").sha256"
)
"$ROOT/scripts/verify-libbox.sh" "$OUTPUT"
echo "Installed $OUTPUT from sing-box $VERSION ($EXPECTED_COMMIT)"
cat "$OUTPUT.sha256"
