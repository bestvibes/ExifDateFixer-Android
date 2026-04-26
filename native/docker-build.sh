#!/usr/bin/env bash
# Local-dev convenience wrapper for native/build.sh that runs the build inside
# a Docker container with the Android NDK pre-installed. Use this when you
# don't have the NDK installed natively, or when you're on macOS (perl-cross
# expects GNU sed/tar/binutils — fixable but inconvenient on macOS).
#
# Usage:
#   native/docker-build.sh <abi>...    # build one or more ABIs + assets
#   native/docker-build.sh all         # build all four ABIs + assets
#
# The host-shippable artifacts (libperl.so, libperl_xs_*.so, perl5.tar,
# xs_manifest.txt) are streamed out via tar — the perl5/ scratch tree stays
# inside the container because perl ships both `lib/Pod/` (the namespace) and
# `lib/pod/` (pod2man helpers), which collide on case-insensitive APFS and
# break a naive `cp -R` from container to host.
#
# CI on Linux uses native/build.sh directly without this wrapper.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE=perlbuild:r26d

if ! docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    echo ">>> First-time setup: building ${IMAGE} (~5 min, downloads NDK r26d)"
    docker build -t "${IMAGE}" - <<'DOCKERFILE'
FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive NDK_VER=r26d
RUN apt-get update -qq && apt-get install -qq -y --no-install-recommends \
      curl gpg gnupg make patch xz-utils ca-certificates unzip git python3 \
      binutils gcc libc6-dev libdigest-sha-perl
RUN curl -fsSL "https://dl.google.com/android/repository/android-ndk-${NDK_VER}-linux.zip" -o /tmp/ndk.zip && \
    unzip -q /tmp/ndk.zip -d /opt && \
    rm /tmp/ndk.zip
ENV ANDROID_NDK_HOME=/opt/android-ndk-r26d
WORKDIR /work
DOCKERFILE
fi

ABIS=()
for arg in "$@"; do
    case "${arg}" in
        all) ABIS=(arm64-v8a armeabi-v7a x86_64 x86) ;;
        arm64-v8a|armeabi-v7a|x86_64|x86|assets) ABIS+=("${arg}") ;;
        *) echo "usage: $0 {arm64-v8a|armeabi-v7a|x86_64|x86|assets|all}..." >&2; exit 2 ;;
    esac
done
if [[ ${#ABIS[@]} -eq 0 ]]; then
    echo "usage: $0 {arm64-v8a|armeabi-v7a|x86_64|x86|assets|all}..." >&2; exit 2
fi

mkdir -p "${REPO_ROOT}/native/out"

# stdout of the docker run is a tar stream; pipe to host tar to extract.
# stderr stays attached so build progress is visible.
docker run --rm \
    -v "${REPO_ROOT}:/work-ro:ro" \
    -e "ABIS=${ABIS[*]}" \
    "${IMAGE}" bash -c '
        set -euo pipefail
        cp -R /work-ro /tmp/build
        cd /tmp/build
        export SOURCE_DATE_EPOCH=1735689600
        rm -rf native/build native/out
        for abi in $ABIS; do
            echo ">>> Building $abi" >&2
            native/build.sh "$abi" >&2
        done
        if [[ "$ABIS" == *assets* ]] || true; then
            : # assets always built last when included; otherwise run separately
        fi
        # Always (re)build assets if any per-ABI build ran — the perl5.tar
        # depends on the install tree.
        if [[ "$ABIS" != "assets" ]]; then
            echo ">>> Building assets" >&2
            native/build.sh assets >&2
        fi
        # Tar back only the host-shippable artifacts; skip per-ABI perl5/
        # trees (collide on APFS, only needed inside container as input to
        # build_assets which already ran).
        tar -C native/out --exclude="*/perl5" -cf - .
    ' | tar -C "${REPO_ROOT}/native/out" -xf -

echo ">>> Done. Outputs at:"
ls -la "${REPO_ROOT}/native/out/"
