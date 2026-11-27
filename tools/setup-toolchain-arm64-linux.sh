#!/usr/bin/env bash
# How the launcher was built on an ARM64 (aarch64) Ubuntu VM, where Google's Linux build-tools (aapt2, zipalign) are
# x86_64-only. Everything lands in ~/tcltv-toolchain: nothing is installed system-wide, no sudo.
#   - aapt2 + zipalign: Ubuntu's own arm64 builds of AOSP (packages aapt, zipalign and their android-lib* deps),
#     fetched with `apt download` and unpacked with `dpkg -x`, run through small wrappers that set LD_LIBRARY_PATH;
#   - d8, apksigner, core-lambda-stubs.jar: Google's build-tools 34 (Java, so they run anywhere);
#   - android.jar: Google's platform android-34;
#   - JDK 17: Eclipse Temurin for aarch64.
# On a Mac (or x86_64 Linux) you don't need this: see launcher/UPSTREAM_README.md "The manual way" (brew / sdkmanager).
# Afterwards:  . ~/tcltv-toolchain/env.sh && tools/build-release.sh
set -euo pipefail
T="$HOME/tcltv-toolchain"
mkdir -p "$T/debs" "$T/dl" "$T/root" "$T/sdk/build-tools" "$T/sdk/platforms" "$T/jdk"
cd "$T/debs"
apt download aapt android-libaapt zipalign android-libandroidfw android-libbase android-liblog android-libutils \
  android-libziparchive android-libbacktrace android-libcutils libzopfli1 7zip \
  "$(apt-cache search --names-only '^libprotobuf3[0-9]' | awk '{print $1}' | grep -v lite | head -1)"
for f in ./*.deb; do dpkg -x "$f" "$T/root"; done

cd "$T/dl"
# SHA-1s from https://dl.google.com/android/repository/repository2-3.xml
curl -sSL -o bt.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip
curl -sSL -o plat.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip
echo "d6d58e0c6925a9e4d9a541e84cd1f405c2f9d2a9  bt.zip" | sha1sum -c -
echo "1f2e9478d6a7601425ceaa553311dc43191f103d  plat.zip" | sha1sum -c -
curl -sSL -o jdk.tgz 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse'
tar -xzf jdk.tgz -C "$T/jdk" --strip-components=1
python3 -I -m zipfile -e bt.zip "$T/sdk/build-tools"      # no unzip needed
python3 -I -m zipfile -e plat.zip "$T/sdk/platforms"
mv "$T/sdk/build-tools/android-14" "$T/sdk/build-tools/34.0.0"
mv "$T/sdk/platforms/"android-* "$T/sdk/platforms/android-34" 2>/dev/null || true
BT="$T/sdk/build-tools/34.0.0"
chmod +x "$BT/d8" "$BT/apksigner"
for t in aapt2 zipalign aapt; do
  mv "$BT/$t" "$BT/$t.x86_64"
  cat > "$BT/$t" <<EOF
#!/usr/bin/env bash
R="\$HOME/tcltv-toolchain/root"
export LD_LIBRARY_PATH="\$R/usr/lib/aarch64-linux-gnu/android:\$R/usr/lib/aarch64-linux-gnu:\$R/usr/lib:\$R/usr/lib/7zip\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
exec "\$R/usr/bin/$t" "\$@"
EOF
  chmod +x "$BT/$t"
done
cat > "$T/env.sh" <<'EOF'
export JAVA_HOME="$HOME/tcltv-toolchain/jdk"
export ANDROID_HOME="$HOME/tcltv-toolchain/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"
EOF
# shellcheck source=/dev/null   # generated just above
. "$T/env.sh"
aapt2 version && d8 --version | head -1 && apksigner --version && java -version 2>&1 | head -1
