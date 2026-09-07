#!/usr/bin/env bash
set -euo pipefail

VERSION="1.14.0"
REVISION="0b8995879f29a9b98ee027bc17b75e101445b238"
ARCHIVE_SHA256="2375de6999f4f56ab46b4fc5ddf26a6aba1d3e61a0f4e7ddec2f4690457d5f63"
ARCHIVE="sing-box-${VERSION}-linux-amd64.tar.gz"
URL="https://github.com/SagerNet/sing-box/releases/download/v${VERSION}/${ARCHIVE}"
DESTINATION="${1:-${RUNNER_TEMP:-/tmp}/foxconnect-sing-box-check}"
BINARY="${DESTINATION}/sing-box-${VERSION}-linux-amd64/sing-box"

mkdir -p "$DESTINATION"
if [[ ! -x "$BINARY" ]]; then
  archive_path="${DESTINATION}/${ARCHIVE}"
  curl --fail --location --retry 3 --output "$archive_path" "$URL"
  printf '%s  %s\n' "$ARCHIVE_SHA256" "$archive_path" | sha256sum --check --status
  tar --extract --gzip --file "$archive_path" --directory "$DESTINATION"
  rm -f "$archive_path"
fi

version_output="$($BINARY version)"
grep -Fq "sing-box version ${VERSION}" <<<"$version_output"
grep -Fq "Revision: ${REVISION}" <<<"$version_output"
printf '%s\n' "$BINARY"
