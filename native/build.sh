#!/usr/bin/env bash
# Reproducible cross-build of perl + ExifTool for Android.
#
# Usage:
#   native/build.sh <abi>           # build one ABI (arm64-v8a | armeabi-v7a | x86_64 | x86)
#   native/build.sh assets          # build the perl5.tar shared across all ABIs
#   native/build.sh all             # all four ABIs + assets
#
# Outputs (relative to repo root):
#   native/out/<abi>/libperl.so     # the perl interpreter, renamed to be jniLibs-shippable
#   native/out/assets/perl5.tar     # exiftool + pure-perl @INC, extracted on first launch
#
# Inputs are pinned in native/PINS. Any source download is integrity-verified before use.
# Designed to produce bit-identical output across runs (SOURCE_DATE_EPOCH respected).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE_DIR="${REPO_ROOT}/native"
SRC_DIR="${NATIVE_DIR}/src"
OUT_DIR="${NATIVE_DIR}/out"
BUILD_DIR="${NATIVE_DIR}/build"

# shellcheck source=PINS
. "${NATIVE_DIR}/PINS"

# SOURCE_DATE_EPOCH pin so timestamps embedded in Config.pm / strip output are stable.
# Set to the perl release date if not provided by the caller.
: "${SOURCE_DATE_EPOCH:=1735689600}"  # 2025-01-01 UTC, arbitrary but fixed
export SOURCE_DATE_EPOCH

# Default NDK location: caller may override via $ANDROID_NDK_HOME.
: "${ANDROID_NDK_HOME:=${HOME}/Library/Android/sdk/ndk/${NDK_VERSION}}"

mkdir -p "${SRC_DIR}" "${OUT_DIR}" "${BUILD_DIR}"

#############################################
# Source download + integrity verification  #
#############################################

verify_sha256() {
    local file=$1 expected=$2
    local actual
    actual=$(shasum -a 256 "${file}" | awk '{print $1}')
    if [[ "${actual}" != "${expected}" ]]; then
        echo "FATAL: SHA256 mismatch for ${file}" >&2
        echo "  expected: ${expected}" >&2
        echo "  actual:   ${actual}" >&2
        exit 1
    fi
}

verify_gpg() {
    local file=$1 sig=$2 fingerprint=$3
    if ! command -v gpg >/dev/null 2>&1; then
        echo "FATAL: gpg required for perl tarball verification" >&2
        exit 1
    fi
    gpg --keyserver hkps://keys.openpgp.org --recv-keys "${fingerprint}" >/dev/null 2>&1 || true
    gpg --verify "${sig}" "${file}" 2>&1 | grep -q "${fingerprint}" || {
        echo "FATAL: GPG signature verification failed for ${file}" >&2
        exit 1
    }
}

fetch_perl() {
    local tarball="${SRC_DIR}/perl-${PERL_VERSION}.tar.gz"
    if [[ ! -f "${tarball}" ]]; then
        curl -fsSL -o "${tarball}" \
            "https://www.cpan.org/src/5.0/perl-${PERL_VERSION}.tar.gz"
    fi
    verify_sha256 "${tarball}" "${PERL_TARBALL_SHA256}"
    # Optional defense-in-depth: GPG-verify the SHA256SUMS file from cpan.org if a key
    # fingerprint is pinned and a signature is published for this release. Many recent
    # perl releases only ship MD5SUMS.asc (not per-tarball detached sigs), so this is
    # best-effort and never blocks the build — the SHA256 pin above is the primary trust
    # anchor.
    if [[ -n "${PERL_GPG_KEY_FINGERPRINT:-}" ]] && command -v gpg >/dev/null 2>&1; then
        local sums="${SRC_DIR}/perl-${PERL_VERSION}.SHA256SUMS"
        local sig="${sums}.asc"
        if curl -fsSL -o "${sums}" "https://www.cpan.org/src/5.0/SHA256SUMS" 2>/dev/null && \
           curl -fsSL -o "${sig}" "https://www.cpan.org/src/5.0/SHA256SUMS.asc" 2>/dev/null; then
            verify_gpg "${sums}" "${sig}" "${PERL_GPG_KEY_FINGERPRINT}" || \
                echo "WARN: GPG verification of SHA256SUMS failed (continuing on SHA256 pin)" >&2
        fi
    fi
}

fetch_exiftool() {
    local tarball="${SRC_DIR}/Image-ExifTool-${EXIFTOOL_VERSION}.tar.gz"
    if [[ ! -f "${tarball}" ]]; then
        curl -fsSL -o "${tarball}" \
            "https://exiftool.org/Image-ExifTool-${EXIFTOOL_VERSION}.tar.gz"
    fi
    verify_sha256 "${tarball}" "${EXIFTOOL_TARBALL_SHA256}"
}

ensure_perl_cross() {
    local pc_dir="${NATIVE_DIR}/perl-cross"
    # perl-cross may already be present as a git submodule (gitlink file), a normal clone
    # (.git directory), or absent entirely. Only clone if it's actually missing.
    if [[ ! -e "${pc_dir}/configure_misc" ]] && [[ ! -e "${pc_dir}/configure" ]]; then
        rm -rf "${pc_dir}"
        git clone --depth 1 --branch "${PERL_CROSS_TAG}" \
            https://github.com/arsv/perl-cross.git "${pc_dir}"
    fi
}

#############################################
# Per-ABI perl build                        #
#############################################

build_perl_for_abi() {
    local abi=$1
    local triple cc_prefix
    case "${abi}" in
        arm64-v8a)    triple=aarch64-linux-android   ; cc_prefix=aarch64-linux-android24 ;;
        armeabi-v7a)  triple=armv7a-linux-androideabi; cc_prefix=armv7a-linux-androideabi24 ;;
        x86_64)       triple=x86_64-linux-android    ; cc_prefix=x86_64-linux-android24 ;;
        x86)          triple=i686-linux-android      ; cc_prefix=i686-linux-android24 ;;
        *) echo "Unknown ABI: ${abi}" >&2; exit 1 ;;
    esac

    local ndk_host
    case "$(uname -s)" in
        Linux)  ndk_host=linux-x86_64 ;;
        Darwin) ndk_host=darwin-x86_64 ;;
        *) echo "Unsupported host OS for NDK toolchain" >&2; exit 1 ;;
    esac
    local toolchain="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${ndk_host}"
    local sysroot="${toolchain}/sysroot"

    if [[ ! -d "${sysroot}" ]]; then
        echo "FATAL: NDK sysroot not found at ${sysroot}" >&2
        echo "Set ANDROID_NDK_HOME to your NDK ${NDK_VERSION} install." >&2
        exit 1
    fi

    fetch_perl
    ensure_perl_cross

    local work="${BUILD_DIR}/perl-${PERL_VERSION}-${abi}"
    rm -rf "${work}"
    mkdir -p "${work}"
    tar -xf "${SRC_DIR}/perl-${PERL_VERSION}.tar.gz" -C "${work}" --strip-components=1

    # Layer perl-cross over the perl source. Perl tarballs contain some read-only
    # files; chmod first so cp -R can overwrite them.
    chmod -R u+w "${work}"
    cp -Rf "${NATIVE_DIR}/perl-cross/." "${work}/"

    # Apply any local patches (e.g. bionic stdlib quirks).
    if [[ -d "${NATIVE_DIR}/patches" ]]; then
        for p in "${NATIVE_DIR}/patches/"*.patch; do
            [[ -f "${p}" ]] || continue
            (cd "${work}" && patch -p1 < "${p}")
        done
    fi

    (
        cd "${work}"

        export AR="${toolchain}/bin/llvm-ar"
        export CC="${toolchain}/bin/${cc_prefix}-clang"
        export CXX="${toolchain}/bin/${cc_prefix}-clang++"
        export RANLIB="${toolchain}/bin/llvm-ranlib"
        export STRIP="${toolchain}/bin/llvm-strip"
        export NM="${toolchain}/bin/llvm-nm"
        export READELF="${toolchain}/bin/llvm-readelf"
        export OBJDUMP="${toolchain}/bin/llvm-objdump"

        # The runtime @INC paths are the on-device locations the AssetExtractor populates.
        # Keep these in sync with FileUtils.getPerlAssetDir() in Kotlin.
        local runtime_perl5="/data/data/me.bestvibes.exiftoolwrapper/files/perl5"

        # Build a NORMAL perl (with DynaLoader). XS modules get their own .so files
        # which ship via jniLibs (see post-build step). This is what Termux uses
        # generally — the static-link variant turned out to require xs_init wiring
        # that perl-cross doesn't generate cleanly under clang-NDK.
        #
        # NOTE: do NOT pass -Dcc=clang here. perl-cross uses $CC from env (the
        # NDK wrapper script aarch64-linux-android24-clang) which already has the
        # right sysroot baked in. Overriding with -Dcc=clang breaks header probes.
        ./configure \
            --target="${triple}" \
            --sysroot="${sysroot}" \
            --prefix="${runtime_perl5}" \
            -Dprivlib="${runtime_perl5}/lib" \
            -Darchlib="${runtime_perl5}/arch" \
            -Dsitelib="${runtime_perl5}/site" \
            -Dsitearch="${runtime_perl5}/site/arch" \
            -Dvendorlib="${runtime_perl5}/vendor" \
            -Dvendorarch="${runtime_perl5}/vendor/arch" \
            -Doptimize='-Os' \
            -Dldflags='-Wl,-z,max-page-size=16384' \
            -Dlibs='-lm -ldl -lc' \
            -Dlddlflags='-shared -Wl,-z,max-page-size=16384 -lm -ldl -lc'

        make -j"$(getconf _NPROCESSORS_ONLN || echo 4)"
        make install DESTDIR="${work}/install"
    )

    # Stage outputs:
    #   out/<abi>/libperl.so          — perl interpreter, jniLibs-shippable
    #   out/<abi>/jniLibs/lib*.so     — XS modules renamed for jniLibs shipping
    #   out/<abi>/xs_manifest.txt     — maps logical XS path → jniLibs filename
    #   out/<abi>/perl5/              — pure-perl @INC tree (arch-independent;
    #                                    folded into perl5.tar by build_assets)
    local out_abi="${OUT_DIR}/${abi}"
    rm -rf "${out_abi}"
    mkdir -p "${out_abi}/jniLibs"

    "${toolchain}/bin/llvm-strip" --strip-all -o "${out_abi}/libperl.so" "${work}/perl"
    chmod 0644 "${out_abi}/libperl.so"

    # Rename every XS .so under archlib/auto/<dist>/<dist>.so to libperl_xs_<flat>.so
    # where <flat> = <dist> with / replaced by __ (so Hash/Util/Util.so → Hash__Util).
    local install_root="${work}/install/data/data/me.bestvibes.exiftoolwrapper/files/perl5"
    local arch_root="${install_root}/arch"
    if [[ ! -d "${arch_root}/auto" ]]; then
        echo "FATAL: no XS auto tree at ${arch_root}/auto" >&2
        exit 1
    fi
    : > "${out_abi}/xs_manifest.txt"
    # Sort so that manifest entry order is reproducible — `find` defaults to
    # filesystem traversal order which varies between ext4, tmpfs, Docker
    # overlay, etc. and would otherwise leak into the manifest's bytes.
    while IFS= read -r -d '' so; do
        local rel="${so#${arch_root}/auto/}"
        local dist="${rel%/*}"
        local soname="lib${PERL_XS_PREFIX}${dist//\//__}.so"
        "${toolchain}/bin/llvm-strip" --strip-all -o "${out_abi}/jniLibs/${soname}" "${so}"
        chmod 0644 "${out_abi}/jniLibs/${soname}"
        echo "${rel} ${soname}" >> "${out_abi}/xs_manifest.txt"
    done < <(find "${arch_root}/auto" -name '*.so' -print0 | LC_ALL=C sort -z)

    # Capture the pure-perl @INC tree (privlib + archlib's .pm subtree, minus auto/).
    local perl5_tree="${out_abi}/perl5"
    mkdir -p "${perl5_tree}/lib"
    cp -R "${install_root}/lib/." "${perl5_tree}/lib/"
    if [[ -d "${arch_root}" ]]; then
        (cd "${arch_root}" && find . -type f -name '*.pm' \( ! -path './auto/*' \) -print0 | \
            while IFS= read -r -d '' f; do
                rel="${f#./}"
                mkdir -p "${perl5_tree}/lib/$(dirname "${rel}")"
                cp "${f}" "${perl5_tree}/lib/${rel}"
            done)
    fi
    cp "${out_abi}/xs_manifest.txt" "${perl5_tree}/xs_manifest.txt"

    echo "Built ${out_abi}/libperl.so ($(shasum -a 256 "${out_abi}/libperl.so" | awk '{print $1}'))"
    echo "Built $(find "${out_abi}/jniLibs" -name '*.so' | wc -l | tr -d ' ') XS .so files"
}

# Renaming prefix used for XS .so files in jniLibs. Matches AssetExtractor.kt.
PERL_XS_PREFIX="perl_xs_"

#############################################
# Shared assets (exiftool + pure-perl @INC) #
#############################################

build_assets() {
    fetch_exiftool

    local work="${BUILD_DIR}/exiftool-${EXIFTOOL_VERSION}"
    rm -rf "${work}"
    mkdir -p "${work}"
    tar -xf "${SRC_DIR}/Image-ExifTool-${EXIFTOOL_VERSION}.tar.gz" -C "${work}" --strip-components=1

    local stage="${BUILD_DIR}/perl5-stage"
    rm -rf "${stage}"
    mkdir -p "${stage}/lib"

    # Start from the perl install's @INC tree (built by build_perl_for_abi).
    # We use one ABI's tree as the source — the .pm files are arch-independent.
    local perl5_src=""
    for abi in arm64-v8a armeabi-v7a x86_64 x86; do
        if [[ -d "${OUT_DIR}/${abi}/perl5/lib" ]]; then
            perl5_src="${OUT_DIR}/${abi}/perl5"
            break
        fi
    done
    if [[ -z "${perl5_src}" ]]; then
        echo "FATAL: no per-ABI perl install tree present; run a per-ABI build first" >&2
        exit 1
    fi
    cp -R "${perl5_src}/lib/." "${stage}/lib/"
    cp "${perl5_src}/xs_manifest.txt" "${stage}/xs_manifest.txt"

    # exiftool script + lib/Image/ExifTool tree
    cp "${work}/exiftool" "${stage}/exiftool"
    cp -R "${work}/lib/." "${stage}/lib/"

    # The perl @INC tree is sourced entirely from the per-ABI install (above).
    # No package-name-based harvesting needed — `make install` produces the
    # canonical layout that the real perl interpreter expects.

    # Reproducible tar: sort, fixed mtime, fixed owner. Requires GNU tar (gtar on macOS).
    local tar_cmd
    if command -v gtar >/dev/null 2>&1; then tar_cmd=gtar; else tar_cmd=tar; fi

    local out_assets="${OUT_DIR}/assets"
    mkdir -p "${out_assets}"
    (cd "${stage}" && \
        find . -print0 | LC_ALL=C sort -z | \
        "${tar_cmd}" --null --no-recursion --owner=0 --group=0 --numeric-owner \
            --mtime="@${SOURCE_DATE_EPOCH}" \
            -T - -cf "${out_assets}/perl5.tar")

    echo "Built ${out_assets}/perl5.tar ($(shasum -a 256 "${out_assets}/perl5.tar" | awk '{print $1}'))"
}

#############################################
# CLI                                       #
#############################################

target=${1:-all}
case "${target}" in
    arm64-v8a|armeabi-v7a|x86_64|x86) build_perl_for_abi "${target}" ;;
    assets) build_assets ;;
    all)
        for abi in arm64-v8a armeabi-v7a x86_64 x86; do
            build_perl_for_abi "${abi}"
        done
        build_assets
        ;;
    *) echo "usage: $0 {arm64-v8a|armeabi-v7a|x86_64|x86|assets|all}" >&2; exit 2 ;;
esac
