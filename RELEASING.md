# Releasing

Releases are built by [`.github/workflows/release.yml`](./.github/workflows/release.yml)
on GitHub Actions, signed with a release keystore stored as encrypted GitHub
Actions secrets, and published as a GitHub Release with SHA256 sums and a
SLSA build-provenance attestation.

The keystore and its passwords are also kept in 1Password as the canonical /
disaster-recovery copy. Nothing release-signing-related is ever committed to
this repo.

## One-time setup

You only need to do this when there is no release keystore yet, or the old
keystore is lost. If you rotate the keystore, every existing installed APK
will fail to update and testers must uninstall first — so don't do this
casually.

### 1. Generate the keystore locally

`keytool` ships inside Android Studio's bundled JDK; the system Java may not
exist on macOS. Either set `JAVA_HOME` first or call `keytool` by full path.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="${JAVA_HOME}/bin:${PATH}"

mkdir -p ~/keystores
keytool -genkey -v \
  -keystore ~/keystores/exiftoolwrapper-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias exiftoolwrapper
chmod 600 ~/keystores/exiftoolwrapper-release.jks
```

`keytool` prompts for a keystore password and the distinguished-name fields.
It will *not* prompt for a separate key password: modern `keytool` defaults
to PKCS12 format, which forces the key password to equal the keystore
password. That's fine — it just means we use the same password for both
`KEYSTORE_PASSWORD` and `KEY_PASSWORD` later.

The DN fields end up baked into the public APK. Don't put a real address;
something like `CN=Vaibhav Aggarwal, C=US` is enough.

### 2. Stash it in 1Password

In the 1Password GUI, create a new item (type: Document) named
`ExifToolWrapper Release Keystore` in your Private vault and:

- Attach `~/keystores/exiftoolwrapper-release.jks` as the document.
- Add a `password` field (the keystore password).
- Add a `Key Alias` field set to `exiftoolwrapper`.

### 3. Push the secrets to GitHub Actions

Capture each secret into a shell variable first, then check it's non-empty
before piping into `gh secret set`. Direct `op read | gh secret set` looks
clean but silently sets an empty secret if `op read` fails (e.g. field
name mismatch) — which then fails the release build with a confusing
"missing META-INF/MANIFEST.MF" error that's hard to trace back. Don't.

Run from this repo's working directory:

```bash
set -euo pipefail

# Keystore file → base64 → secret. (`base64 -i FILE` doesn't go through
# stdin so an empty/missing file produces a non-zero exit, not silent empty.)
base64 -i ~/keystores/exiftoolwrapper-release.jks \
  | gh secret set KEYSTORE_BASE64

# Keystore password (also used as the key password — PKCS12 unifies them)
keystore_password=$(op read "op://Private/ExifToolWrapper Release Keystore/password")
[[ -n "${keystore_password}" ]] || { echo "empty keystore password"; exit 1; }
printf '%s' "${keystore_password}" | gh secret set KEYSTORE_PASSWORD
printf '%s' "${keystore_password}" | gh secret set KEY_PASSWORD
unset keystore_password

# Key alias is not a secret — it's just the string "exiftoolwrapper" baked
# into the keystore. Hardcode it rather than round-tripping through op,
# whose field-name resolution for labels with spaces ("Key Alias") is
# unreliable and silently returns empty on miss.
printf 'exiftoolwrapper' | gh secret set KEY_ALIAS
```

Verify with `gh secret list` — you should see all four. To sanity-check
that none are empty, kick off a workflow run and look at the masked env
in any "Build" step's expanded log: a working secret prints as `***`,
an empty one prints as a blank value after the `:`.

## Cutting a release

Releases use date-based tags. From a clean `master`:

```bash
git pull
date_tag="release-$(date +%Y-%m-%d)"
git tag "${date_tag}"
git push origin "${date_tag}"
```

GitHub Actions will:

1. Build signed release APKs for all four ABIs (`arm64-v8a`,
   `armeabi-v7a`, `x86_64`, `x86`) plus a universal APK.
2. Verify each APK's signature with `apksigner verify`.
3. Compute `SHA256SUMS`.
4. Attach a SLSA build-provenance attestation pinning each APK to the
   workflow run + commit SHA.
5. Create a GitHub Release at `${date_tag}` with all APKs + `SHA256SUMS`
   uploaded.

If you cut more than one release on the same date, append a suffix:
`release-2026-05-05-2`.

## Verifying a release as a downloader

```bash
# checksum
shasum -a 256 -c SHA256SUMS

# build provenance: was this APK actually produced by our workflow from a
# specific commit?
gh attestation verify ExifToolWrapper-release-2026-05-05-universal.apk \
  -R bestvibes/exiftoolwrapper-android
```

## Local release builds (only for smoke-testing the variant)

The release variant can be built locally without the keystore. With the
release signing env vars unset, Gradle skips signing entirely and produces
`app/build/outputs/apk/release/app-*-release-unsigned.apk` files. Useful
only for "does the release buildtype compile" sanity checks — Android
won't install an unsigned APK.

```bash
ANDROID_HOME=~/Library/Android/sdk \
  JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ./gradlew :app:assembleRelease
```

To produce a locally-signed release APK for on-device testing without
going through CI, export the four signing env vars first:

```bash
export KEYSTORE_PATH=~/keystores/exiftoolwrapper-release.jks
export KEYSTORE_PASSWORD=$(op read "op://Private/ExifToolWrapper Release Keystore/password")
export KEY_PASSWORD="${KEYSTORE_PASSWORD}"
export KEY_ALIAS=$(op read "op://Private/ExifToolWrapper Release Keystore/Key Alias")
./gradlew :app:assembleRelease
```

Don't share APKs built this way — they bypass the CI provenance chain that
makes releases trustworthy.
