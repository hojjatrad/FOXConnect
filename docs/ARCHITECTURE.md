# FOXConnect architecture — phase 4 diagnostic alpha 12

```text
WorkManager 24h ──▶ HTTPS Subscription ─────────────┐
One-shot panel credentials ──▶ strict panel APIs ───┤
Share / Clipboard / File / ZIP / QR / Manual Form ──┤
Standard WireGuard INI ──────────────────────────────┤
                                                     ▼
                                          UniversalConfigImporter
              plain · Base64 · gzip · ZIP (bounded)
                               │
              strict enabled-protocol parser dispatch
                               │
                               ▼
                ProfileRepository (transactional)
                               │
                   ProfileVaultCodec (versioned)
                               │
               AES-GCM + Android Keystore + AtomicFile
                               │
                               ▼
                     Encrypted profile vault
                               │ selected typed profile
                               ▼
Compose Home ──▶ AndroidTunnelController ──▶ FoxVpnService (:vpn process)
       ▲                                           │
       │ private state + heartbeat   encrypted JSON│
       └──────── TunnelStateBridge ◀───────────────┤
                                                   ▼
                                           TypedLibboxCore
                               exact API + pin + native checkConfig
                                                   │ typed openTun(options)
                                                   ▼
                               Android VpnService/TUN + physical Network
                                                   │
       10 proxy outbounds or sing-box WireGuard endpoint (11 families)
                                                   ▼
                                          User gateway
```

## Module boundaries

- `core:model`: serializable protocol, profile-vault and tunnel-state values.
- `core:parser`: strict parser dispatch for all requested protocol families,
  standard WireGuard INI parsing, VLESS formatting and bounded universal
  container import; no Android dependency.
- `core:storage`: validated vault codec, Android Keystore encryption, portable
  passphrase-backup encryption and transactional profile/subscription repository.
- `core:engine`: sing-box config, active config, VpnService/TUN, health checks,
  boot restore and runtime facts.
- `app`: localized Compose UI, file/clipboard/share flows, in-app QR scanner,
  strict-HTTPS subscription transport and one-shot authenticated panel adapters.

The engine depends on storage/parser only for boot reconstruction of the selected
profile. Storage never depends on the engine, so persistence remains independently
testable.

## Import trust boundary

All import surfaces converge on `UniversalConfigImporter`. It enforces:

- 2 MiB initial input and 4 MiB cumulative expanded data;
- no more than 128 ZIP entries, 512 configs and three decode layers;
- in-memory ZIP processing with no filesystem extraction/path traversal;
- strict UTF-8, bounded Base64/Base64URL/GZIP and semantic duplicate detection;
- narrowly scoped JSON `data/content/subscription` Base64 envelopes plus escaped
  newline/slash and HTML ampersand normalization used by subscription panels;
- every recognized protocol is accepted only after its strict parser succeeds;
- VMess requires AEAD (`alter_id=0`) and modern cipher selection;
- Shadowsocks accepts only modern AEAD/2022 methods and no plugins;
- Hysteria/TUIC/anyTLS reject unknown options and all certificate bypass flags;
- WireGuard accepts bounded standard INI, validates keys, CIDRs, endpoint, peers,
  MTU/keepalive/reserved and rejects ambiguous duplicate scalar options;
- proxy credentials use unambiguous, bounded URI user-info.

The QR scanner uses CameraX and the open-source ZXing decoder in a non-exported
activity. Frames are analyzed in-process and never passed to a camera app,
proprietary telemetry SDK, or network model. Subscription transport accepts only
HTTPS, uses platform TLS validation, refuses URL user-info/fragments, follows at
most three HTTPS-only redirects and reads at most 2 MiB. Network subscriptions
request identity encoding to avoid Android/CDN transparent-gzip ownership ambiguity;
GZIP remains supported as an imported payload/container. There is no cleartext
fallback or trust-all code. WorkManager refreshes enabled subscriptions every 24
hours only while the system reports network connectivity. Transient failures use
bounded exponential backoff; opaque internal error codes, never URLs or configs,
are stored in the encrypted vault.

Authenticated panel import is deliberately one-shot. Marzban and PasarGuard use
their form-token and bearer-authenticated user-list APIs; Hiddify accepts its
personal Basic-auth path or the documented hidden admin API path plus client path.
The Hiddify client-path field explicitly selects admin mode, so admin credentials
are sent only to that documented API and never to a speculative personal endpoint.
Admin authentication never follows redirects and is never forwarded to a returned
subscription URL. User lists, response bodies, user/config counts and total payload
bytes are bounded. The credential dialog temporarily enables Android `FLAG_SECURE`.
Passwords are held in an owning `CharArray` and overwritten after the operation;
credentials, bearer values, Hiddify UUID URLs and raw responses are not stored in
the vault, WorkManager, events or UI summaries. Successful payloads still pass
through `UniversalConfigImporter` and only validated profiles enter the encrypted
vault.

## Encrypted profile persistence

`ProfileVault` contains raw credential-bearing configs, subscriptions, metadata
and selected ID. The encoded vault is encrypted with random-IV AES-GCM using a
non-exportable Android Keystore key, then committed through `AtomicFile`.
Authentication/version/schema/size failures make the repository unavailable;
they do not replace a damaged vault with an empty file.

The active native JSON is separately encrypted under a different key. This
separation keeps service process recovery small. Selecting/editing/deleting or
explicitly disconnecting clears stale active JSON. On boot, if auto-connect is
enabled and VPN consent remains valid, `BootReceiver` can reconstruct fresh
native JSON from the encrypted selected profile.

System cloud backup and device-transfer backup are disabled for all app data.

## Native process and lifecycle boundary

`FoxVpnService` runs in the dedicated `:vpn` process. The UI never loads libbox, so
a Go/JNI abort can terminate only the VPN process, not the activity process. Explicit
connect/disconnect actions still require an app-private authorization marker. A null
restart intent becomes recovery only when authorization, encrypted active config and
Android VPN consent all remain valid. That authorized path returns `START_STICKY` and
has a persistent limit of three process recoveries per five minutes; exceeding the
budget revokes authorization rather than forming a crash loop. Unknown or unauthorized
actions remain fail-closed. Auto-connect defaults off, and package replacement never
connects. Boot connection is a separate opt-in policy.

A bounded private `AtomicFile` bridge with an inter-process file lock carries only
sanitized runtime state and a monotonic heartbeat. The UI polls it and converts a
missing heartbeat into a truthful recovery-pending failure after seven seconds, but
does not revoke a verified session's authorization. An uncaught UI-process failure
records only a categorical event and does not disconnect the isolated VPN. Explicit
disconnect first publishes `Disconnecting`, then the VPN process publishes
`Disconnected` only after teardown. VPN permission revocation and Android Force Stop
still terminate recovery. `Connected` is never reconstructed from stale state; every
restored native tunnel must pass a new routed HTTPS verification.

The Home control is an original solid-color Compose drawing: separate shadow, lower
rim and moving face layers provide depth without gradients or copied assets. Press and
release change face depth and trigger haptics. Orbit ticks/arcs, connected ripples,
disconnect motion and failure shake are selected exclusively from the real
`ConnectionState`; animation never manufactures a protected/connected state.

The JNI boundary implements the exact pinned gomobile `PlatformInterface` and
`CommandServerHandler` types; dynamic proxies and nullable/default callback guesses
are not used. Command-server ownership is recorded before start, and close order is
service → command server → TUN descriptor → physical-network monitor.

User-exported backups are a separate portable `FCBK` envelope. A random 128-bit
salt and 600,000-iteration PBKDF2-HMAC-SHA256 derive a 256-bit AES key; AES-GCM
uses a random 96-bit IV and authenticates the full version/KDF/length header as
AAD. Restore size, format, KDF parameters, authentication tag, schema, IDs,
references and field limits are verified before the existing `AtomicFile` vault
is replaced. Password-derived keys and plaintext byte arrays are overwritten on
best effort; only encrypted bytes may be staged in the app-private cache.

## Connection truth invariant

`Connected` is emitted only after:

1. official libbox v1.14.0 setup completes and reports the pinned version;
2. native `checkConfig` accepts JSON;
3. OOM draft promotion and command-server construction/start complete;
4. an API-appropriate non-VPN physical-network callback is registered;
5. command server starts the native service;
6. `PlatformInterface.openTun` establishes Android TUN;
7. outbound sockets are protected onto a non-VPN physical network while Android
   TUN receives the native address/route/exclude/DNS/package options; and
8. at least one of several independent strict-TLS probes succeeds through the tunnel.

Missing/wrong core, permission/config/TUN errors or failed probes emit `Failed`.
After verification, best-effort UID RX/TX and Cloudflare trace exit identity are
published. Null values remain em dashes; UID counters are not advertised as exact
per-profile libbox counters.

The physical-network monitor filters on `NET_CAPABILITY_NOT_VPN`, excludes VPN
transports from interface discovery, uses best-matching callbacks on Android 12+,
an explicit network request on Android 9–11, and a listening callback on Android 8.
An OEM-safe registration fallback and delayed `LinkProperties` handling prevent a
transient interface lookup from becoming a false no-network state. It reports real
interface flags plus metered/constrained state and performs local resolver lookups
on the selected physical `Network`. Android 10+ connection-owner lookup uses the
platform API; older systems use libbox procfs lookup. `openTun` consumes libbox's
actual TUN addresses, MTU, DNS servers,
route/exclude ranges and application lists. The previous leak-guard descriptor is
released immediately before a replacement TUN is established, while every owned
PFD is closed exactly once on failure/stop.

`tun.auto_route=true` and `route.auto_detect_interface=true` are a required pair on
Android. Auto-route captures packets from the VPN UID; platform auto-detection makes
libbox invoke `autoDetectInterfaceControl(fd)`, which calls `VpnService.protect(fd)`
on each proxy socket. Alpha 6 incorrectly disabled auto-detection, so an established
TUN could capture its own upstream socket and produce no returning traffic. Alpha 7
enables this path without excluding the app UID or bypassing ordinary application
traffic.

Generated JSON now declares `route.default_domain_resolver=bootstrap-dns` as required
by sing-box 1.14. The bootstrap resolver is Android-local and bound to the selected
physical Network; it resolves only dial targets. The normal secure DNS transport has
an explicit `proxy` detour, breaking the former proxy → DoH → unresolved-proxy cycle.
Every supported family plus VLESS Reality and the complete pinned V2Ray transport
set (HTTP, WebSocket, QUIC, gRPC and HTTPUpgrade) passes `sing-box check` with exact
version 1.14.0/revision `0b8995879f29a9b98ee027bc17b75e101445b238` (plain TCP needs
no transport wrapper). XHTTP and mKCP are retained on import but rejected before
service start because this libbox version has no such transport; incompatible QUIC
extra encryption is rejected by the parser and invalid JSON is never handed to native
code.

Startup exceptions are wrapped at eight typed boundaries: setup, version, config
check, command construction, command start, network monitor, service start and
post-start. Post-TUN verification separately identifies physical-interface,
bootstrap-DNS, protected-socket, secure-DNS, strict-TLS, HTTPS-response and routed-
response failures using only in-memory counts and fixed codes. Only these categorical
codes reach state, notifications or the bounded event log; native exception messages,
destinations and stack traces are never persisted or shown.

## Watchdog, failover and leak guard

A versioned encrypted engine payload contains the selected profile followed by up
to 31 bounded fallback configs for later recovery. An explicit initial request starts
with the selected profile and can then inspect up to four ranked alternatives in a
bounded round. A previously verified session continues ranked rounds with a 5–60 second
delay after currently eligible candidates fail. Explicit disconnect or lost VPN consent
ends recovery. `FailoverPolicy` skips persisted cooldowns and chooses the lowest fresh
verified tunnel latency first, then the lowest fresh TCP endpoint latency, then a stable
unknown-order fallback. Endpoint reachability is never numerically substituted for
verified tunneled quality.

After verification, a 1.5-second watchdog interval runs concurrent strict-TLS routed
HTTPS probes with a 1.5-second per-probe timeout. Any syntactically valid HTTP response
after authenticated TLS proves the path; it need not be a region-dependent 200/204.
The first successful probe completes the round and cancels the others. Two consecutive
failed rounds create a six-second worst-case detection budget before native reconnect
work. Successful samples feed a bounded EWMA. Quality switching requires the same
better candidate for four consecutive samples, at least 30 seconds on the active tunnel,
at least 250 ms and 35 percent improvement (or a reachable trial when the active tunnel
exceeds the configurable weak threshold), and a three-minute post-switch cooldown.
Every candidate must pass a new real probe before `Connected` is published. Physical
device timing remains a release gate and a zero-gap TUN handoff is not claimed.

Before closing a failed core, the service establishes a full-route sink TUN when the
default-enabled in-service Kill Switch is on. The sink remains after terminal
failure until explicit disconnect, so the live service does not intentionally fall
back to the physical network. Android Always-on “Block connections without VPN” is
still required to cover force-stop, process kill, revoke, or OS teardown. This
system distinction is stated in the UI.

A Failed-state sink also blocks FOXConnect's own ordinary HTTPS sockets by design.
Before a user-triggered subscription refresh or endpoint test, maintenance flow now
sends an explicit disconnect, waits up to three seconds for `Disconnected`, and only
then opens a physical-network socket. Refreshing the active subscription similarly
disconnects first; periodic background refresh does not interrupt a healthy tunnel.

Health metadata contains random profile ID, separately keyed endpoint latency/timestamp,
verified tunnel latency/timestamp, endpoint-probe failure bit and cooldown only;
credentials/endpoints remain in the encrypted vault. Legacy alpha8 latency keys migrate
in place as endpoint measurements and are never relabelled as tunnel quality. The manual
“ping all” operation performs up to eight real, three-second-bounded TCP handshakes in
parallel. It is labelled endpoint latency rather than tunnel validation;
UDP/QUIC-only configurations remain unavailable instead of receiving inferred values.
The event log stores only bounded timestamps and enum codes. It cannot contain profile names, URLs, endpoints,
raw config or exception messages.

## Token-free update boundary

The update subsystem is isolated under `app/update` and has no dependency on the VPN
engine or profile vault. `UpdateRepository` reads at most ten public releases from the
compile-time repository `hojjatrad/FOXConnect`; no runtime repository override or token
exists. `StrictHttpsClient` requires HTTPS, default platform certificate/hostname
validation, bounded responses, a fixed GitHub host allowlist and at most five HTTPS
redirects. It never accepts cleartext fallback.

Release selection requires a newer integer versionCode in the exact ABI-specific asset
name. Stable is the default policy; pre-releases are opt-in. `UpdateCheckWorker` is also
opt-in, runs at a 24-hour interval under a connected-network constraint, reads metadata
only and can only post a localized notification. It cannot download or install.

A foreground user action fetches the exact companion `.sha256`, validates any GitHub API
digest, streams the APK into a size-bounded cache file while hashing, then validates the
archive application ID, exact/newer versionCode, single compatible ABI and current
installed signing-certificate set. Failure deletes the file. A non-exported FileProvider
exposes only `cache/verified-updates/`; a second user action hands the verified APK to the
Android package installer, which retains final user approval. There is no silent-install API.

Production signing configuration exists only when all four release-key environment
variables are present. The release workflow rebuilds the pinned native AAR, runs tests and
lint, signs split APKs from GitHub Secrets, compares the signer certificate with its pinned
secret, checks package/ABI/16-KiB ZIP alignment, emits companion hashes and lets only a
separate `contents: write` job publish with the ephemeral `GITHUB_TOKEN`.

## Native artifact

The AAR is built from sing-box v1.14.0 at exact commit
`0b8995879f29a9b98ee027bc17b75e101445b238`. The build script creates ARM64 and
ARMv7 sequentially; the verifier checks checksum, ABI allowlist and generated API
classes. `:core:engine` uses the AAR only on its compile classpath and the application
packages that exact verified AAR directly; this avoids unsupported nested local-AAR
bundling in AGP while preserving the typed boundary. App packaging emits one APK per
ABI to control size. Representative complete
configs for all 11 protocol families, including the 1.14 WireGuard `endpoints`
schema, pass `sing-box check` using the exact pinned Linux CLI. This checks schema,
not Android native loading or real connectivity. Every core update still requires
API inspection and physical-device regression.

## Open security/product gates

- Physical-device tests for Keystore, CameraX, WorkManager/SAF backup, boot,
  native config acceptance and real routing for all protocol families.
- Real UDP/QUIC behavior for Hysteria/TUIC and WireGuard needs device/server tests.
- Structured manual editing remains VLESS-only; other protocols use strict links
  or standard WireGuard files.
- Watchdog recovery time and in-service leak guard need adversarial device tests;
  system-equivalent lockdown still depends on the Android Always-on VPN setting.
- Full measured ranking needs observations for each profile; unknown latency remains unknown.
- Structured forms beyond VLESS and split tunnel/DNS/rules remain open.
- The updater and GitHub release workflow require CI compilation plus physical checks of manual/periodic policy, notification permission, hash/signature rejection and Android installer handoff.
