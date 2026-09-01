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

die() {
    echo "ERROR: $*" >&2
    exit 1
}

[ "$(uname -m)" = "aarch64" ] || die "Must run on an ARM64 host (found $(uname -m))"
[ "$(id -u)" = "0" ] || die "Must run as root (chroot dependency install)"

command -v curl >/dev/null || die "curl is required"

if [ -z "${RUNNER_VERSION:-}" ]; then
    RUNNER_VERSION="$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest \
        | grep -om1 '"tag_name": *"v[^"]*"' | sed 's/.*"v\([^"]*\)".*/\1/')"
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
cleanup
trap - EXIT

chmod 1777 "$WORK_DIR/rootfs/tmp"

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

echo "==> Packaging $BUNDLE_NAME"
tar -C "$WORK_DIR" --hard-dereference --numeric-owner --owner=0 --group=0 \
    --exclude='./rootfs/dev/*' --exclude='./rootfs/proc/*' --exclude='./rootfs/sys/*' \
    -czf "$OUT_DIR/$BUNDLE_NAME" rootfs home

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
