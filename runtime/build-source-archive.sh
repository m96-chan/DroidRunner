#!/usr/bin/env bash
#
# Builds the corresponding source archive for the GPL binaries inside the APK
# (issue #116).
#
# GPL-2.0 §3 lets a distributor satisfy the source obligation by offering the
# source "from the same place" as the binary. A GitHub release is such a place,
# so this produces an archive to attach beside the APK. Pointing at an upstream
# repository is not the same place: it is somebody else's server, and it can
# move.
#
# What goes in is what §3 calls complete corresponding source — "all the source
# code for all modules it contains, plus any associated interface definition
# files, plus the scripts used to control compilation and installation". For a
# cross-compiled, patched proot that means upstream at the pinned commit, our
# patches, and the script that applies them.
#
# Usage: runtime/build-source-archive.sh [tag]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TAG="${1:-${GITHUB_REF_NAME:-dev}}"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/out}"
STAGE="$OUT_DIR/source-$TAG"
ARCHIVE="$OUT_DIR/droidrunner-$TAG-source.tar.gz"

die() { echo "ERROR: $*" >&2; exit 1; }

# The single source of truth for what was built is the build script itself, so
# the pins are read from it rather than repeated here — a second copy would
# eventually disagree with the first, and the archive would then describe
# something that was never shipped.
read_pin() { sed -n "s/^$1=\"\${$1:-\([^}]*\)}\"/\1/p" "$SCRIPT_DIR/build-proot.sh"; }

TALLOC_VERSION="$(read_pin TALLOC_VERSION)"
TALLOC_SHA256="$(read_pin TALLOC_SHA256)"
PROOT_REPO="$(read_pin PROOT_REPO)"
PROOT_COMMIT="$(read_pin PROOT_COMMIT)"

[ -n "$TALLOC_VERSION" ] || die "cannot read TALLOC_VERSION from build-proot.sh"
[ -n "$PROOT_COMMIT" ] || die "cannot read PROOT_COMMIT from build-proot.sh"

rm -rf "$STAGE"
mkdir -p "$STAGE/proot" "$STAGE/talloc" "$STAGE/droidrunner"

echo "proot $PROOT_COMMIT" >&2
git clone --quiet "$PROOT_REPO" "$STAGE/proot/proot" || die "cannot clone proot"
git -C "$STAGE/proot/proot" checkout --quiet "$PROOT_COMMIT" \
    || die "cannot check out $PROOT_COMMIT"
# The archive is source, not a repository: .git would double its size and is
# not what §3 asks for.
rm -rf "$STAGE/proot/proot/.git"

echo "talloc $TALLOC_VERSION" >&2
talloc_archive="$STAGE/talloc/talloc-$TALLOC_VERSION.tar.gz"
wget -qO "$talloc_archive" "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" \
    || die "cannot download talloc"
echo "$TALLOC_SHA256  $talloc_archive" | sha256sum -c - >/dev/null \
    || die "talloc archive does not match its pinned SHA-256"

# talloc is statically linked into libproot.so, so LGPL-3.0 §4 wants the
# recipient able to relink against a modified talloc. Shipping its source and
# the script that builds it is what makes that possible.
cp -r "$SCRIPT_DIR/patches" "$STAGE/droidrunner/patches"
cp "$SCRIPT_DIR/build-proot.sh" "$STAGE/droidrunner/"
cp "$SCRIPT_DIR/README.md" "$STAGE/droidrunner/" 2>/dev/null || true

cat > "$STAGE/README" <<EOF
Corresponding source for the GPL and LGPL binaries in droidrunner-$TAG.apk.

  proot/proot/     termux/proot at $PROOT_COMMIT
                   $PROOT_REPO
                   GPL-2.0. Built into libproot.so, libproot-loader.so and
                   libproot-loader32.so.

  talloc/          talloc $TALLOC_VERSION, from samba.org
                   sha256 $TALLOC_SHA256
                   LGPL-3.0. Statically linked into libproot.so. Rebuild it and
                   re-run build-proot.sh to relink.

  droidrunner/     The scripts and patches used to produce those binaries:
                   build-proot.sh applies patches/ to proot and cross-compiles
                   both with the Android NDK.

To rebuild:

  ANDROID_NDK_ROOT=/path/to/ndk droidrunner/build-proot.sh

DroidRunner itself is GPL-2.0-only; its own source is at
https://github.com/m96-chan/DroidRunner at tag $TAG.

Two files there carry an additional permission under GPL-2 section 3 allowing
them to be combined with Qualcomm's QNN libraries; the notice is in each file.
Those Qualcomm libraries are not distributed with this app — the device fetches
them at first use, with the user's consent — so no part of them is in this
archive or in the APK.
EOF

cat > "$STAGE/MANIFEST.txt" <<EOF
component	version	licence	role
proot	$PROOT_COMMIT	GPL-2.0	libproot.so and ELF loaders, patched
talloc	$TALLOC_VERSION	LGPL-3.0	statically linked into libproot.so
DroidRunner	$TAG	GPL-2.0-only	the application
EOF

# An archive that is missing a piece is worse than no archive, because it
# looks like compliance. A silent cp or a moved upstream path would produce
# exactly that, so what must be there is checked before it is sealed.
for required in \
    "proot/proot/src/GNUmakefile" \
    "talloc/talloc-$TALLOC_VERSION.tar.gz" \
    "droidrunner/build-proot.sh" \
    "droidrunner/patches/string-header.patch" \
    "README" \
    "MANIFEST.txt"
do
    [ -e "$STAGE/$required" ] || die "the source archive is missing $required"
done

tar -czf "$ARCHIVE" -C "$OUT_DIR" "source-$TAG"
rm -rf "$STAGE"
echo "$ARCHIVE"
