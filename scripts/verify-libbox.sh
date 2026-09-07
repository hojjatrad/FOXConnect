#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAR="${1:-$ROOT/core/engine/libs/libbox.aar}"
[[ -f "$AAR" ]] || { echo "Missing libbox AAR: $AAR" >&2; exit 1; }
[[ -f "$AAR.sha256" ]] || { echo "Missing checksum: $AAR.sha256" >&2; exit 1; }

(
    cd "$(dirname "$AAR")"
    sha256sum -c "$(basename "$AAR").sha256"
)

mapfile -t native_entries < <(unzip -Z1 "$AAR" | grep -E '^jni/[^/]+/libbox\.so$' | sort)
expected_entries=(
    "jni/arm64-v8a/libbox.so"
    "jni/armeabi-v7a/libbox.so"
)
if [[ "${native_entries[*]}" != "${expected_entries[*]}" ]]; then
    printf 'Unexpected libbox ABI set: %s\n' "${native_entries[*]:-(none)}" >&2
    exit 1
fi

classes="$(mktemp)"
class_list="$(mktemp)"
trap 'rm -f "$classes" "$class_list"' EXIT
unzip -p "$AAR" classes.jar > "$classes"
unzip -Z1 "$classes" > "$class_list"
for required_class in \
    io/nekohasekai/libbox/Libbox.class \
    io/nekohasekai/libbox/CommandServer.class \
    io/nekohasekai/libbox/CommandServerHandler.class \
    io/nekohasekai/libbox/PlatformInterface.class \
    io/nekohasekai/libbox/TunOptions.class; do
    grep -Fxq "$required_class" "$class_list" || {
        echo "Missing required libbox API: $required_class" >&2
        exit 1
    }
done

echo "Verified libbox AAR ABIs and required API surface: $AAR"
