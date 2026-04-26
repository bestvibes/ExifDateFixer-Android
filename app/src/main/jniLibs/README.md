# jniLibs/ — `libperl.so` per ABI

Each `<abi>/libperl.so` here is the perl interpreter, statically linked and
renamed so Android's installer extracts it onto an exec-mounted directory
(`applicationInfo.nativeLibraryDir`). At runtime the app invokes it via
`ProcessBuilder("${nativeLibraryDir}/libperl.so", …)`.

These files are **not built locally**. They are produced by
[`.github/workflows/native.yml`](../../../../.github/workflows/native.yml)
from the upstream sources pinned in [`native/PINS`](../../../../native/PINS),
and committed to the repo by an automated PR so an ordinary `./gradlew`
build doesn't require an NDK install.

If you're auditing: SHA256 each binary and compare against (a) the SLSA
provenance attestation on the matching `native-v*` GitHub release, and
(b) the artifact uploaded to the workflow run that produced it. They must
match exactly. If they don't, do not trust the binary.

To rebuild from source: install the NDK at `NDK_VERSION` from `PINS`, then
`native/build.sh all`.
