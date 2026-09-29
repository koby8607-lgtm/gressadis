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
rm -rf "$WORK"; mkdir -p "$WORK" "$OUT"; test -x "$CC"
tarball="${WORK}/ppp-${VERSION}.tar.gz"
curl --fail --show-error --location --retry 10 --retry-all-errors --retry-delay 3 "https://github.com/ppp-project/ppp/archive/refs/tags/${TAG}.tar.gz" -o "$tarball"
test -s "$tarball"; tar -xzf "$tarball" -C "$WORK"; mv "${WORK}/ppp-${VERSION}" "$SRC"; cd "$SRC"
if [ ! -x ./configure ]; then test -x ./autogen.sh; ./autogen.sh; fi
test -x ./configure
python3 - <<'PYFIX'
from pathlib import Path
import re
p=Path('pppd/sys-linux.c'); s=p.read_text()
if '#ifdef __ANDROID__\n#include <net/route.h>' not in s:
    s=s.replace('#include <unistd.h>\n', '#include <unistd.h>\n#ifdef __ANDROID__\n#include <net/route.h>\n#endif\n', 1)
s=s.replace('#ifndef _LINUX_IN6_H\n/*\n* This is in linux/include/net/ipv6.h.\n*/\nstruct in6_ifreq {\nstruct in6_addr ifr6_addr;\n__u32 ifr6_prefixlen;\nunsigned int ifr6_ifindex;\n};\n#endif', '#if !defined(__ANDROID__) && !defined(_LINUX_IN6_H) && !defined(_UAPI_LINUX_IN6_H)\nstruct in6_ifreq {\nstruct in6_addr ifr6_addr;\n__u32 ifr6_prefixlen;\nunsigned int ifr6_ifindex;\n};\n#endif')
if '#ifndef N_TTY' not in s:
    s=s.replace('static int tty_disc = N_TTY;', '#ifndef N_TTY\n#define N_TTY 0\n#endif\n#ifndef N_PPP\n#define N_PPP 3\n#endif\n#ifndef N_SYNC_PPP\n#define N_SYNC_PPP 14\n#endif\n\nstatic int tty_disc = N_TTY;',1)
p.write_text(s)
PYFIX
export CC AR RANLIB
export CFLAGS="-O2 -fPIE -fstack-protector-strong -DANDROID -D__ANDROID__ -D__ANDROID_API__=30 -DINET6=1 -DHAVE_LOGWTMP=1 -Wno-unused-parameter -Wno-empty-body -Wno-missing-field-initializers -Wno-attributes -Wno-sign-compare -Wno-pointer-sign"
export CPPFLAGS="$CFLAGS"; export LDFLAGS="-pie"; export LIBS=""
./configure --build="$(gcc -dumpmachine)" --host=aarch64-linux-android --prefix=/data/local/tmp/usb0-ppp --sysconfdir=/data/local/tmp/usb0-ppp/etc --disable-plugins --disable-eaptls --disable-peap --disable-multilink --disable-systemd --without-openssl --without-pam --without-pcap --without-atm
set +e
make -j1 V=1 CFLAGS="$CFLAGS" CPPFLAGS="$CPPFLAGS" LDFLAGS="$LDFLAGS" 2>&1 | tee "$WORK/ppp-make.log"
rc=${PIPESTATUS[0]}; set -e
if [ "$rc" -ne 0 ]; then echo '=== PPP BUILD FAILURE ===' >&2; grep -n -E 'error:|fatal error:|undefined reference|conflicting types|redefinition' "$WORK/ppp-make.log" | tail -80 >&2 || true; exit "$rc"; fi
test -s pppd/pppd; test -s chat/chat
cp pppd/pppd "$OUT/pppd"; cp chat/chat "$OUT/chat"; "$STRIP" "$OUT/pppd" || true; "$STRIP" "$OUT/chat" || true; chmod 755 "$OUT/pppd" "$OUT/chat"
file "$OUT/pppd"; file "$OUT/chat"; file "$OUT/pppd" | grep -Eiq 'ELF 64-bit.*ARM aarch64'; file "$OUT/chat" | grep -Eiq 'ELF 64-bit.*ARM aarch64'
