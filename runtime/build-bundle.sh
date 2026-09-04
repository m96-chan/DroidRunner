#!/usr/bin/env bash
#
# Builds the DroidRunner runtime bundle (droidrunner-runtime-arm64.tar.gz):
# an Ubuntu ARM64 glibc rootfs with the runner's dependencies preinstalled,
# plus the official GitHub Actions runner under home/runner. The bundle is
# data only — proot itself ships inside the APK (see build-proot.sh).
#
# Must run as root on an ARM64 Linux host (CI: ubuntu-24.04-arm) because the
# dependency install chroots into the rootfs.
#
# Environment:
#   RUNNER_VERSION   actions/runner version, without "v" (default: latest)
#   UBUNTU_VERSION   ubuntu-base version (default: 24.04.3)
#   MANIFEST_URL     download URL written into the manifest (default:
#                    GitHub release asset URL derived from GITHUB_REPOSITORY
#                    and RELEASE_TAG)
#   OUT_DIR          output directory (default: runtime/out)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/out}"
WORK_DIR="$OUT_DIR/bundle"
UBUNTU_VERSION="${UBUNTU_VERSION:-24.04.3}"
UBUNTU_SERIES="${UBUNTU_VERSION%.*}"
BUNDLE_NAME="droidrunner-runtime-arm64.tar.gz"
# Written offer plus the package list; see SOURCE-OFFER.txt below.
OFFER_NAME="SOURCE-OFFER.txt"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

[ "$(uname -m)" = "aarch64" ] || die "Must run on an ARM64 host (found $(uname -m))"
[ "$(id -u)" = "0" ] || die "Must run as root (chroot dependency install)"

command -v curl >/dev/null || die "curl is required"

if [ -z "${RUNNER_VERSION:-}" ]; then
    # Unauthenticated API calls are rate limited per source address, and CI
    # runners share theirs — this returned 403 in a pull request that had
    # nothing to do with the runtime. A token when one is available makes the
    # limit per-account instead; without one the call still works locally.
    auth_header=()
    if [ -n "${GITHUB_TOKEN:-}" ]; then
        auth_header=(-H "Authorization: Bearer $GITHUB_TOKEN")
    fi
    release_json="$(curl -fsSL "${auth_header[@]}" https://api.github.com/repos/actions/runner/releases/latest)" \
        || die "Unable to query actions/runner releases"
    RUNNER_VERSION="$(sed -n 's/.*"tag_name": *"v\([^"]*\)".*/\1/p' <<<"$release_json" | head -n1)"
    [ -n "$RUNNER_VERSION" ] || die "Unable to resolve latest actions/runner version"
fi
echo "==> actions/runner v$RUNNER_VERSION, ubuntu-base $UBUNTU_VERSION"

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/rootfs" "$WORK_DIR/home/runner" "$OUT_DIR"

##
## rootfs: Ubuntu base + runner dependencies
##

echo "==> Fetching Ubuntu base rootfs"
curl -fsSL -o "$OUT_DIR/ubuntu-base.tar.gz" \
    "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_SERIES/release/ubuntu-base-$UBUNTU_VERSION-base-arm64.tar.gz" \
    || die "Unable to download ubuntu-base"
tar -xzf "$OUT_DIR/ubuntu-base.tar.gz" -C "$WORK_DIR/rootfs"

echo "==> Installing runner dependencies into the rootfs"
# DNS for the chroot and for jobs running on the device.
cat > "$WORK_DIR/rootfs/etc/resolv.conf" <<'EOF'
nameserver 1.1.1.1
nameserver 8.8.8.8
EOF

for fs in dev proc sys; do
    mount --bind "/$fs" "$WORK_DIR/rootfs/$fs"
done
cleanup() {
    for fs in dev proc sys; do
        umount -l "$WORK_DIR/rootfs/$fs" 2>/dev/null || true
    done
}
trap cleanup EXIT

chroot "$WORK_DIR/rootfs" /usr/bin/env DEBIAN_FRONTEND=noninteractive bash -eu <<'EOF'
apt-get update -qq
# .NET runtime deps used by the actions runner, plus the tools jobs expect.
apt-get install -y -qq --no-install-recommends \
    ca-certificates curl git openssl \
    libicu74 libkrb5-3 zlib1g liblttng-ust1t64
apt-get clean
rm -rf /var/lib/apt/lists/*
EOF
# Every package name and version in the rootfs, so anyone we hand this to can
# fetch the corresponding source with `apt-get source <name>=<version>`.
# Redistributing a Ubuntu rootfs makes us the distributor for the GPL software
# inside it; Canonical's obligation does not travel with the bytes (issue #116).
chroot "$WORK_DIR/rootfs" dpkg-query -W -f='${Package}\t${Version}\t${binary:Package}\n' \
    > "$WORK_DIR/PACKAGES.txt" || die "Unable to list rootfs packages"
echo "==> $(wc -l < "$WORK_DIR/PACKAGES.txt") packages recorded in PACKAGES.txt"

cleanup
trap - EXIT

chmod 1777 "$WORK_DIR/rootfs/tmp"

# Device Agent CLI: lets jobs reach Android-side hardware without knowing the
# loopback/token plumbing.
install -Dm755 "$SCRIPT_DIR/droidrunner-device" "$WORK_DIR/rootfs/usr/local/bin/droidrunner-device"

##
## home/runner: official GitHub Actions runner (linux-arm64)
##

echo "==> Fetching actions/runner v$RUNNER_VERSION (linux-arm64)"
curl -fsSL -o "$OUT_DIR/actions-runner.tar.gz" \
    "https://github.com/actions/runner/releases/download/v$RUNNER_VERSION/actions-runner-linux-arm64-$RUNNER_VERSION.tar.gz" \
    || die "Unable to download actions runner"
tar -xzf "$OUT_DIR/actions-runner.tar.gz" -C "$WORK_DIR/home/runner"
[ -x "$WORK_DIR/home/runner/run.sh" ] || die "Runner archive is missing run.sh"

##
## Package: tar (hard links dereferenced — the APK extractor cannot create
## them), SHA-256, manifest
##

# The offer travels with the bundle, since whoever holds the tarball is who
# the obligation is owed to.
cat > "$WORK_DIR/$OFFER_NAME" <<OFFER
This bundle contains an Ubuntu ARM64 rootfs. Most of the software in it is
covered by the GPL, the LGPL or similar licences, and redistributing it carries
the obligation to make the corresponding source available.

PACKAGES.txt beside this file lists every package and its exact version. For
any of them, the corresponding source is obtainable with:

    apt-get source <package>=<version>

against Ubuntu's archive, which is where these binaries came from unmodified.

If that archive no longer carries a version listed here, write to the address
on https://github.com/m96-chan/DroidRunner and the source for any package in
this bundle will be provided, at no more than the cost of distribution, for at
least three years from the date this bundle was published.

Nothing in this rootfs was modified: it is Ubuntu's own binaries, installed
with apt. The parts DroidRunner builds itself are the app and proot, whose
source ships with the app release as droidrunner-<tag>-source.tar.gz.
OFFER

echo "==> Packaging $BUNDLE_NAME"
tar -C "$WORK_DIR" --hard-dereference --numeric-owner --owner=0 --group=0 \
    --exclude='./rootfs/dev/*' --exclude='./rootfs/proc/*' --exclude='./rootfs/sys/*' \
    -czf "$OUT_DIR/$BUNDLE_NAME" rootfs home "$OFFER_NAME" PACKAGES.txt

SHA256="$(sha256sum "$OUT_DIR/$BUNDLE_NAME" | cut -d' ' -f1)"
VERSION="runner-$RUNNER_VERSION-ubuntu-$UBUNTU_VERSION"

if [ -z "${MANIFEST_URL:-}" ]; then
    MANIFEST_URL="https://github.com/${GITHUB_REPOSITORY:-OWNER/DroidRunner}/releases/download/${RELEASE_TAG:-runtime-latest}/$BUNDLE_NAME"
fi

cat > "$OUT_DIR/runtime-manifest.json" <<EOF
{
  "version": "$VERSION",
  "url": "$MANIFEST_URL",
  "sha256": "$SHA256"
}
EOF

echo "==> Done"
ls -lh "$OUT_DIR/$BUNDLE_NAME"
cat "$OUT_DIR/runtime-manifest.json"
