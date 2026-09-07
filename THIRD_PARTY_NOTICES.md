# Third-party notices

## sing-box / libbox

- Project: https://github.com/SagerNet/sing-box
- Purpose: native proxy, protocol and TUN packet-processing core
- License: GNU General Public License v3.0 (GPL-3.0)
- Pinned version: `v1.14.0`
- Pinned commit: `0b8995879f29a9b98ee027bc17b75e101445b238`

The binary AAR is intentionally not committed. `scripts/build-libbox.sh` builds it
from the exact upstream commit and records its SHA-256 digest. A release workflow
must publish the corresponding source and notices alongside APKs.

## ZXing

- Project: https://github.com/zxing/zxing
- Purpose: local QR decoding; no network model or telemetry SDK
- Version: 3.5.4
- License: Apache License 2.0

## AndroidX, Kotlin and kotlinx libraries

AndroidX (including CameraX), Kotlin, kotlinx and Compose dependencies are
resolved from Google Maven and Maven Central under their respective licenses,
primarily Apache License 2.0. Their packaged license metadata remains
authoritative. A machine-generated complete dependency notice remains a release
hardening gate.
