#!/usr/bin/env bash
# Builds both launcher flavors into release/ with checksums:
#   release/TCLMinimal.apk          jarvis flavor: the classic minimalist launcher + the JARVIS layer behind its toggle
#   release/TCLMinimal-classic.apk  classic flavor: no JARVIS code, permission or components at all
# Needs the Android toolchain (JDK 17, build-tools 34, platform android-34): see launcher/UPSTREAM_README.md
# "The manual way", or tools/setup-toolchain-arm64-linux.sh on an ARM64 Linux box.
# Built --generic (no config.properties): no keys inside, the city comes from the TV's time zone.
# Signed with launcher/freemytv.keystore (created on first build, git-ignored): keep it, updates need the same key.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/release"
for flavor in jarvis classic; do
  (cd "$ROOT/launcher" && ./build.sh --generic --flavor "$flavor" | grep -v '^Note:')
  out="$ROOT/release/TCLMinimal.apk"; [ "$flavor" = classic ] && out="$ROOT/release/TCLMinimal-classic.apk"
  cp "$ROOT/launcher/FreeMyTV.apk" "$out"
done
cd "$ROOT/release"
if command -v sha256sum >/dev/null; then sha256sum TCLMinimal.apk TCLMinimal-classic.apk > SHA256SUMS
else shasum -a 256 TCLMinimal.apk TCLMinimal-classic.apk > SHA256SUMS; fi
cat SHA256SUMS
