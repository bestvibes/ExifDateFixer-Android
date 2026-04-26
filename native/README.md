# native/ — reproducible perl + ExifTool build

This directory contains everything needed to rebuild the perl interpreter and
the ExifTool script that the app ships, **from pinned upstream source, in
public CI, with byte-for-byte reproducibility.**

## Why this exists

Earlier versions of this app committed pre-built `perl_*.xz` and `exiftool.xz`
blobs straight into `app/src/main/res/raw/`. Those blobs had no provenance:
they were built on a maintainer's laptop years ago, and there was no way for
anyone — users, F-Droid reviewers, or future maintainers — to verify what
source they came from or rebuild them deterministically.

This directory replaces that with a build pipeline that:

1. Pins the perl source tarball, ExifTool source tarball, perl-cross release,
   and Android NDK version in [`PINS`](./PINS), with SHA256s.
2. Verifies every download against the pinned hash before use (and GPG-verifies
   perl when a signature is available).
3. Builds via [perl-cross](https://github.com/arsv/perl-cross) under
   [`build.sh`](./build.sh), targeting all four required Android ABIs.
4. Runs in [`.github/workflows/native.yml`](../.github/workflows/native.yml),
   which uploads the resulting `libperl.so` files and `perl5.tar` as build
   artifacts and attaches an SLSA build-provenance attestation.
5. Commits the produced binaries to the repo so an ordinary `./gradlew`
   build does not require an NDK install. Anyone can re-run the workflow and
   diff the artifacts against what's checked in.

## Layout

```
native/
  PINS              # version + SHA256 pins (sourced by build.sh and CI)
  build.sh          # cross-builds perl per-ABI; bundles ExifTool into perl5.tar
  perl-cross/       # git submodule, pinned to PERL_CROSS_TAG
  patches/          # local patches over upstream perl (Bionic quirks)
  src/              # downloaded source tarballs (gitignored)
  build/            # per-ABI build trees (gitignored)
  out/              # build outputs (gitignored; copied into the app tree by CI)
```

## Outputs

`build.sh` produces:

- `out/<abi>/libperl.so` — the perl interpreter, built in the standard
  configuration with `DynaLoader` enabled, stripped, renamed so Android's
  installer places it under `applicationInfo.nativeLibraryDir`.
- `out/<abi>/jniLibs/libperl_xs_*.so` — one shared object per XS module
  (`POSIX`, `Compress::Raw::Zlib`, `IO::Compress::*`, `List::Util`, etc.).
  Renamed from the `make install` tree's `auto/<dist>/<dist>.so` paths
  using `<dist>` with `/` replaced by `__` (e.g. `auto/Hash/Util/Util.so`
  → `libperl_xs_Hash__Util.so`) so Android's installer extracts them via
  the standard `jniLibs` mechanism alongside `libperl.so`.
- `out/<abi>/xs_manifest.txt` — maps each XS module's canonical archlib
  path back to its `libperl_xs_*.so` filename, used by `AssetExtractor`
  on-device to symlink `filesDir/perl5/arch/auto/.../*.so` →
  `nativeLibraryDir/libperl_xs_*.so` so perl's `DynaLoader` finds them.
- `out/assets/perl5.tar` — POSIX tar containing the ExifTool script + its
  `lib/Image/ExifTool/` tree + perl's full `@INC` (`Carp.pm`, `strict.pm`,
  `warnings.pm`, `IO::Compress::*`, `XSLoader`, etc.) sourced directly
  from the `make install` tree. Architecture-independent, shipped under
  `app/src/main/assets/`.

CI copies the `out/` tree into `app/src/main/jniLibs/` and
`app/src/main/assets/` and opens a PR.

## Running the build locally

### Recommended: Docker wrapper (works on macOS and Linux)

```sh
git submodule update --init native/perl-cross
native/docker-build.sh arm64-v8a    # build one ABI + assets
native/docker-build.sh all          # build all four + assets
```

The wrapper builds an `ubuntu:22.04` image with the Android NDK pre-installed
on first run (~5 min, one-time), then runs the build inside the container and
streams the artifacts back via tar. Required for macOS hosts because perl
ships both `lib/Pod/` and `lib/pod/`, which collide on case-insensitive APFS
during a naive `cp -R`; the wrapper sidesteps this by keeping the perl5/
scratch tree inside the container and only exporting the shippable artifacts.

### Direct invocation (Linux only, e.g. CI)

You need:

- An Android NDK install matching `NDK_VERSION` in `PINS`. Set
  `ANDROID_NDK_HOME` if it's not at the default location.
- `gpg`, `curl`, `tar` (GNU), `shasum`, and `make`.

```sh
git submodule update --init native/perl-cross
native/build.sh arm64-v8a       # build one ABI
native/build.sh all             # build all four + assets
```

A successful run prints the SHA256 of each output. Compare against the SHA256
of the corresponding artifact in the latest GitHub Actions run, or the bytes
checked into `app/src/main/jniLibs/<abi>/libperl.so` — they should match
exactly.

## Verifying a released APK

1. Install the APK and `adb pull /data/app/.../base.apk`.
2. `unzip -p base.apk lib/arm64-v8a/libperl.so | shasum -a 256`, and the
   same for each `libperl_xs_*.so`.
3. Compare to:
   - The SHA256s in the SLSA attestation attached to the matching
     `native-v*` GitHub release.
   - The bytes at `app/src/main/jniLibs/arm64-v8a/lib*.so` in the
     repository at the corresponding commit.

If all three match you have a complete chain from pinned upstream perl
source → CI build → committed binary → installed APK.

## Bumping versions

Edit `PINS`, set the new version + new SHA256 (taken from the upstream
checksum file), then run the workflow. Review the SHA changes in the diff
before merging — every byte change in `lib*.so` should be explainable
by a corresponding change in `PINS` or `patches/`.
