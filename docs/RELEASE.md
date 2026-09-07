# FOXConnect production release procedure

This procedure keeps the production signing key and every password outside source,
build logs, APK metadata, documentation and chat.

## 1. Generate the key locally

Use JDK 17 `keytool` on a trusted offline machine. Choose unique strong passwords in the
interactive prompts; do not place them in a shell command or text file.

```bash
umask 077
keytool -genkeypair -v \
  -keystore foxconnect-release.jks \
  -alias foxconnect \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10000
```

Create at least two encrypted offline backups. Losing this key prevents in-place updates.
Never commit or send the keystore/passwords in an issue, release, action artifact or chat.

Record the signing certificate's SHA-256 fingerprint locally:

```bash
keytool -list -v -keystore foxconnect-release.jks -alias foxconnect
```

## 2. Configure GitHub Actions secrets

In repository **Settings → Secrets and variables → Actions**, create these secrets:

- `FOXCONNECT_KEYSTORE_BASE64`: one-line Base64 of the JKS file
- `FOXCONNECT_SIGNING_KEY_ALIAS`
- `FOXCONNECT_SIGNING_KEY_PASSWORD`
- `FOXCONNECT_SIGNING_STORE_PASSWORD`
- `FOXCONNECT_SIGNING_CERT_SHA256`: certificate SHA-256 fingerprint from `keytool`

Generate the first value without writing an unencrypted copy:

```bash
base64 -w0 foxconnect-release.jks
```

Paste values only into GitHub's secret UI. Repository code and the public updater require
no PAT. The publish job uses the run-scoped `${{ github.token }}` with `contents: write`;
all build/test jobs remain `contents: read`.

## 3. One-time migration from diagnostic alpha

The physically tested alpha uses `com.foxconnect.app.debug` and Android's debug
certificate. Production uses `com.foxconnect.app` and the new Release certificate, so it
cannot update the diagnostic package in place.

1. In the diagnostic app, create an encrypted backup with a strong passphrase.
2. Keep the backup and passphrase separately.
3. Install the first production APK as a separate application.
4. Restore the encrypted backup in production and validate profiles while disconnected.
5. Test real connectivity, DNS, traffic counters and disconnect.
6. Remove the diagnostic package only after validation.

## 4. Publish

Update `versionCode` monotonically and commit only reviewed source. Push an annotated tag
such as `v0.5.0`. The workflow rebuilds pinned libbox, runs tests/lint, signs both ABI
splits, validates the fixed package/signing certificate/ABI/alignment, creates companion
SHA-256 files and publishes the GitHub Release with the ephemeral Actions token.

Do not manually replace an asset under an existing tag. Publish a higher versionCode in a
new release. Keep pre-release status for diagnostic channels; production clients ignore
pre-releases unless the user explicitly opts in.

## 5. Publish a locally re-signed diagnostic build

The persistent diagnostic key must remain local and must never become a GitHub Secret.
Download the ARM64 APK only from a successful mandatory main-branch Actions run, verify
its package/version/ABI/alignment, and re-sign it locally with the existing diagnostic
certificate. Prepare exactly these four public assets:

```text
FOXConnect-v<versionCode>-debug-arm64-v8a.apk
FOXConnect-v<versionCode>-debug-arm64-v8a.apk.sha256
SHA256SUMS
VERIFICATION.txt
```

Push an annotated `diagnostic-v<version>-alpha<number>` tag, then create a **private draft**
pre-release and upload those four assets. A draft is only staging and is not public.
Run **Publish verified diagnostic draft** with the exact tag, version code, and lowercase
APK SHA-256. The workflow checks the annotated tag and main ancestry, exact asset set,
checksums, verification metadata, debug package, version, ARM64-only payload, v2-only
signature, persistent diagnostic certificate, 16 KiB ZIP alignment, and credential
patterns. Only after every gate passes does its run-scoped `${{ github.token }}` change
the draft to a public pre-release. Any mismatch leaves the draft private.

Delete the failed draft rather than replacing assets after a verification failure. Create
a fresh draft and always use a higher versionCode for any APK content change.
