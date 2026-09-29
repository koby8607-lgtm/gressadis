#!/usr/bin/env bash
set -euo pipefail

VERSION="2.5.4"
TAG="v${VERSION}"
WORK="${RUNNER_TEMP:-/tmp}/usb0-ppp-${VERSION}"
SRC="${WORK}/ppp"
OUT="app/src/main/assets/tools/arm64-v8a"

NDK="${ANDROID_NDK_ROOT:-${ANDROID_HOME}/ndk/27.2.12479018}"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="${TOOLCHAIN}/aarch64-linux-android30-clang"
AR="${TOOLCHAIN}/llvm-ar"
RANLIB="${TOOLCHAIN}/llvm-ranlib"
STRIP="${TOOLCHAIN}/llvm-strip"

rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"

test -x "$CC"

tarball="${WORK}/ppp-${VERSION}.tar.gz"
url="https://github.com/ppp-project/ppp/archive/refs/tags/${TAG}.tar.gz"

echo "Downloading official PPP ${VERSION} source"
curl --fail --show-error --location --retry 10 --retry-all-errors --retry-delay 3 "$url" -o "$tarball"
test -s "$tarball"

tar -xzf "$tarball" -C "$WORK"
mv "${WORK}/ppp-${TAG#v}" "$SRC"
cd "$SRC"

# Android NDK r27c exports struct in6_ifreq from linux/ipv6.h. PPP 2.5.4's
# fallback only checks the old _LINUX_IN6_H guard, which is not the guard used
# by the NDK header. Patch only that guard so IPv6CP stays enabled on Android.
python3 - <<'PYPATCH'
from pathlib import Path
path = Path("pppd/sys-linux.c")
src = path.read_text()
marker = "struct in6_ifreq"
idx = src.find(marker)
if idx < 0:
    raise SystemExit("PPP Android patch: struct in6_ifreq not found")
window_start = max(0, idx - 1200)
window = src[window_start:idx]
old = "#ifndef _LINUX_IN6_H"
if old not in window:
    raise SystemExit("PPP Android patch: legacy _LINUX_IN6_H guard not found before struct in6_ifreq")
guard = "#if !defined(_LINUX_IN6_H) && !defined(_UAPI_LINUX_IN6_H) && !defined(_UAPI_IPV6_H) && !defined(_IPV6_H)"
pos = window_start + window.index(old)
src = src[:pos] + guard + src[pos + len(old):]
path.write_text(src)
check = path.read_text()
assert guard in check
print("Applied Android in6_ifreq compatibility patch")
PYPATCH

if ! grep -Fq "!defined(_UAPI_IPV6_H)" pppd/sys-linux.c; then
  echo "ERROR: Android in6_ifreq patch was not applied"
  exit 1
fi

# GitHub source archives may not include a generated configure script.
if [ ! -x ./configure ]; then
  echo "configure not present; running PPP autotools bootstrap"
  test -x ./autogen.sh
  ./autogen.sh
fi

test -x ./configure

export CC
export AR
export RANLIB
export CFLAGS="-O2 -fPIE -fstack-protector-strong -DANDROID -D__ANDROID_API__=30"
export CPPFLAGS="$CFLAGS"
export LDFLAGS="-pie"
export LIBS=""

./configure \
  --build="$(gcc -dumpmachine)" \
  --host=aarch64-linux-gnu \
  --prefix=/data/local/tmp/usb0-ppp \
  --sysconfdir=/data/local/tmp/usb0-ppp/etc \
  --disable-plugins \
  --disable-eaptls \
  --disable-peap \
  --disable-multilink \
  --disable-systemd \
  --without-openssl \
  --without-pam \
  --without-pcap \
  --without-atm

echo "Building PPP with verbose compiler output (serial build for actionable CI logs)"
set +e
make -j1 V=1 CFLAGS="$CFLAGS" CPPFLAGS="$CPPFLAGS" LDFLAGS="$LDFLAGS" 2>&1 | tee "$WORK/ppp-build.log"
rc=${PIPESTATUS[0]}
set -e
if [ "$rc" -ne 0 ]; then
  echo "=== PPP BUILD FAILURE (last 240 lines) ==="
  tail -240 "$WORK/ppp-build.log" || true
  exit "$rc"
fi

test -s pppd/pppd
test -s chat/chat

cp pppd/pppd "$OUT/pppd"
cp chat/chat "$OUT/chat"

"$STRIP" "$OUT/pppd" || true
"$STRIP" "$OUT/chat" || true

chmod 755 "$OUT/pppd" "$OUT/chat"

file "$OUT/pppd"
file "$OUT/chat"

file "$OUT/pppd" | grep -Eiq 'ELF 64-bit.*ARM aarch64'
file "$OUT/chat" | grep -Eiq 'ELF 64-bit.*ARM aarch64'

printf 'PPP_BUILD=SUCCESS\\n'
printf 'PPPD_BYTES=%s\\n' "$(wc -c < "$OUT/pppd")"
printf 'CHAT_BYTES=%s\\n' "$(wc -c < "$OUT/chat")"
