#!/usr/bin/env bash
#
# 构建 Muse 的「运行环境」包：Ubuntu base + Node + Python，通过 Termux proot 运行。
#
# 为什么这么做：Node/Python 的 native 库原本打进 APK（arm64 单架构就有 98MB 是 Node 加 ICU），
# 而且 Android 10+ 不允许执行应用可写目录里的二进制。proot 自己加载 ELF，绕开这条限制，
# 所以整套环境可以放在可下载的包里，主包只留几百 KB 的 proot。
#
# 用法：
#   runtime/build.sh arm64      # 产出 arm64-v8a
#   runtime/build.sh x86_64     # 产出 x86_64（模拟器验证用）
#
# 产物（runtime/out/<abi>/）：
#   rootfs.tar.gz      解压后即 rootfs
#   bin/proot          Termux proot 二进制
#   bin/loader         proot 的 ptrace 引导程序（缺了会报 execve ENOENT）
#   bin/libtalloc.so.2 / libandroid-shmem.so   proot 的运行时依赖
#   manifest.json      版本、体积、sha256、下载地址
#
# 全部使用预编译包拼装，不需要 debootstrap、不需要 qemu，x86_64 主机上可直接产出 arm64。
set -euo pipefail

ARCH="${1:-arm64}"
case "$ARCH" in
  arm64)   UBUNTU_ARCH=arm64; NODE_ARCH=arm64; TERMUX_ARCH=aarch64; ABI=arm64-v8a ;;
  x86_64)  UBUNTU_ARCH=amd64; NODE_ARCH=x64;   TERMUX_ARCH=x86_64;  ABI=x86_64 ;;
  *) echo "未知架构: $ARCH（支持 arm64 / x86_64）" >&2; exit 1 ;;
esac

# ── 版本固定 ──────────────────────────────────────────────────────────
RUNTIME_REVISION="${RUNTIME_REVISION:-r2}"
UBUNTU_VERSION="${UBUNTU_VERSION:-24.04.5}"
NODE_VERSION="${NODE_VERSION:-24.22.1}"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1767225600}"   # 2026-01-01T00:00:00Z，固定 tar mtime 以复现
RUNTIME_RELEASE_URL="https://github.com/Zer0Qing/Muse/releases/download/runtime-${RUNTIME_REVISION}/rootfs-${ABI}.tar.gz"

# ── 镜像（国内直连）────────────────────────────────────────────────────
TUNA_UBUNTU="https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"
TUNA_TERMUX="${MUSE_RT_TERMUX_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main}"
NODE_MIRROR="https://npmmirror.com/mirrors/node"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 工作目录必须放在 Linux 原生盘上。
# 放在 /mnt/e（Windows 盘，走 9p）时，十几万个小文件的解包与打包会慢一个数量级，
# 实测从两分钟变成十几分钟还没结束。产物最后再用 --copy-out 拷回仓库。
ROOT="${MUSE_RT_ROOT:-$HOME/muse-rt}"
CACHE="$ROOT/cache"
WORK="$ROOT/work-$ARCH"
OUT="$ROOT/out/$ABI"
mkdir -p "$CACHE" "$WORK" "$OUT/bin"

say() { printf '\n== [%s] %s\n' "$ARCH" "$*" >&2; }
die() { printf '\n[错误] %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null || die "需要 curl"
command -v sha256sum >/dev/null || die "需要 sha256sum"
command -v python3 >/dev/null || die "需要 python3"

# ── 1. 上游原料 ───────────────────────────────────────────────────────
say "1/6 下载上游包"
UB_BALL="$CACHE/ubuntu-base-$UBUNTU_VERSION-$UBUNTU_ARCH.tar.gz"
NODE_BALL="$CACHE/node-v$NODE_VERSION-linux-$NODE_ARCH.tar.xz"
fetch_once() { # $1=目标文件 $2=url
  local dest="$1" url="$2"
  if [ -f "$dest" ]; then return 0; fi
  [ -f "$dest.part" ] || curl -fL --retry 3 --connect-timeout 20 -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
}

fetch_once "$UB_BALL" "$TUNA_UBUNTU/ubuntu-base-$UBUNTU_VERSION-base-$UBUNTU_ARCH.tar.gz"
fetch_once "$NODE_BALL" "$NODE_MIRROR/v$NODE_VERSION/node-v$NODE_VERSION-linux-$NODE_ARCH.tar.xz"
ls -lh "$UB_BALL" "$NODE_BALL" >&2

# ── 2. proot 三件套 ───────────────────────────────────────────────────
say "2/6 取 proot 与其依赖（版本从索引现查，不硬编码）"
PKG_INDEX="$CACHE/packages-$TERMUX_ARCH.gz"
[ -f "$PKG_INDEX" ] || curl -fsSL "$TUNA_TERMUX/dists/stable/main/binary-$TERMUX_ARCH/Packages.gz" -o "$PKG_INDEX"

pkg_path() { # $1=包名 -> 源内相对路径
  gzip -dc "$PKG_INDEX" | awk -v p="$1" '$0 == "Package: " p { found=1 } found && /^Filename:/ { print $2; exit }'
}

fetch_deb() { # $1=包名 -> 本地 deb 路径
  local name="$1" rel deb
  rel="$(pkg_path "$name")"
  [ -n "$rel" ] || die "Termux 索引里没有 $name"
  deb="$CACHE/$name-$TERMUX_ARCH.deb"
  if [ ! -f "$deb" ]; then
    curl -fL --retry 3 --connect-timeout 20 -sS -o "$deb.part" "$TUNA_TERMUX/$rel"
    mv "$deb.part" "$deb"
  fi
  echo "$deb"
}

PROOT_DEB="$(fetch_deb proot)"
TALLOC_DEB="$(fetch_deb libtalloc)"
SHMEM_DEB="$(fetch_deb libandroid-shmem)"

rm -rf "$WORK/deb"; mkdir -p "$WORK/deb"
for deb in "$PROOT_DEB" "$TALLOC_DEB" "$SHMEM_DEB"; do
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$deb" "$WORK/deb"
  else
    ( cd "$WORK/deb" && ar x "$deb" && tar xf data.tar.* && rm -f data.tar.* control.tar.* debian-binary )
  fi
done

TERMUX_PREFIX="$WORK/deb/data/data/com.termux/files/usr"
cp "$TERMUX_PREFIX/bin/proot"                        "$OUT/bin/proot"
cp "$TERMUX_PREFIX/libexec/proot/loader"             "$OUT/bin/loader"
find "$WORK/deb" -name 'libtalloc.so.*' -type f -exec cp -a {} "$OUT/bin/libtalloc.so.2" \;
find "$WORK/deb" -name 'libandroid-shmem.so' -type f -exec cp -a {} "$OUT/bin/" \;
chmod +x "$OUT/bin/proot" "$OUT/bin/loader"

# ── 3. rootfs ─────────────────────────────────────────────────────────
say "3/6 铺 Ubuntu base"
rm -rf "$WORK/rootfs"; mkdir -p "$WORK/rootfs"
tar xzf "$UB_BALL" -C "$WORK/rootfs"

say "安装目标架构的 Ubuntu Python 包"
command -v apt-get >/dev/null || die "需要 apt-get 下载 Ubuntu 包"
command -v dpkg-deb >/dev/null || die "需要 dpkg-deb 解包 Ubuntu 包"
PY_APT="$CACHE/python-apt-$UBUNTU_ARCH"
mkdir -p "$PY_APT/lists/partial" "$PY_APT/archives/partial" "$PY_APT/cache"
if [ "$UBUNTU_ARCH" = "arm64" ]; then
  APT_MIRROR="https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
else
  APT_MIRROR="https://mirrors.tuna.tsinghua.edu.cn/ubuntu"
fi
printf 'deb [arch=%s] %s noble main universe\n' "$UBUNTU_ARCH" "$APT_MIRROR" > "$PY_APT/sources.list"
APT_OPTIONS=(
  -o "Dir::Etc::sourcelist=$PY_APT/sources.list"
  -o "Dir::Etc::sourceparts=-"
  -o "Dir::State::lists=$PY_APT/lists"
  -o "Dir::State::status=$WORK/rootfs/var/lib/dpkg/status"
  -o "Dir::Cache::archives=$PY_APT/archives"
  -o "Dir::Cache::pkgcache=$PY_APT/cache/pkgcache.bin"
  -o "Dir::Cache::srcpkgcache=$PY_APT/cache/srcpkgcache.bin"
  -o "APT::Architecture=$UBUNTU_ARCH"
  -o "APT::Architectures=$UBUNTU_ARCH"
)
apt-get "${APT_OPTIONS[@]}" update
apt-get "${APT_OPTIONS[@]}" --download-only --yes --no-install-recommends install \
  python3-minimal python3 python3-pip ca-certificates
for deb in "$PY_APT"/archives/*.deb; do
  [ -f "$deb" ] || continue
  dpkg-deb -x "$deb" "$WORK/rootfs"
done
PYTHON_MINOR=3.12
PYTHON_VERSIONED="$WORK/rootfs/usr/bin/python$PYTHON_MINOR"
[ -x "$PYTHON_VERSIONED" ] || die "Ubuntu Python 3.12 二进制缺失"
ln -sfn "python$PYTHON_MINOR" "$WORK/rootfs/usr/bin/python3"
CERT_SOURCE_DIR="$WORK/rootfs/usr/share/ca-certificates"
CERT_BUNDLE="$WORK/rootfs/etc/ssl/certs/ca-certificates.crt"
mkdir -p "$(dirname "$CERT_BUNDLE")" "$WORK/rootfs/usr/lib/ssl"
: > "$CERT_BUNDLE"
while IFS= read -r -d '' cert_file; do
  cat "$cert_file" >> "$CERT_BUNDLE"
  printf '\n' >> "$CERT_BUNDLE"
done < <(find "$CERT_SOURCE_DIR" -type f -name '*.crt' -print0 | sort -z)
[ -s "$CERT_BUNDLE" ] || die "Ubuntu CA 证书 bundle 为空"
ln -sfn /etc/ssl/certs/ca-certificates.crt "$WORK/rootfs/usr/lib/ssl/cert.pem"
rm -rf "$PY_APT/lists" "$PY_APT/cache"
[ -x "$WORK/rootfs/usr/bin/python3" ] || die "Ubuntu Python 3 入口缺失"

# ── 4. Node + 瘦身 ────────────────────────────────────────────────────
say "4/6 装入 Node 并瘦身"
tar xf "$NODE_BALL" -C "$WORK" --no-same-owner
NODE_DIR="$WORK/node-v$NODE_VERSION-linux-$NODE_ARCH"
[ -d "$NODE_DIR" ] || die "Node 解包目录不存在"
mkdir -p "$WORK/rootfs/usr/local"
cp -a "$NODE_DIR"/. "$WORK/rootfs/usr/local/"

# 这些目录不参与运行，只占体积：头文件、文档、man、apt 索引、locale 里用不到的
rm -rf \
  "$WORK/rootfs/usr/local/include" \
  "$WORK/rootfs/usr/local/share/doc" \
  "$WORK/rootfs/usr/local/share/man" \
  "$WORK/rootfs/usr/share/doc" \
  "$WORK/rootfs/usr/share/man" \
  "$WORK/rootfs/usr/share/info" \
  "$WORK/rootfs/var/cache/apt" \
  "$WORK/rootfs/var/lib/apt/lists" \
  "$WORK/rootfs/var/lib/dpkg/info" 2>/dev/null || true

# proot 会话需要 /tmp 可写
mkdir -p "$WORK/rootfs/tmp"
chmod 1777 "$WORK/rootfs/tmp"

echo "  解压后体积: $(du -sh "$WORK/rootfs" | cut -f1)" >&2

# ── 5. 打包 ───────────────────────────────────────────────────────────
say "5/6 打包 rootfs.tar.gz"
# 固定 mtime 与属主，保证同输入同输出；--hard-dereference 避免硬链接（Android 侧解压时会踩 SELinux）
# 注意：不要用 find -exec touch 逐个文件改时间 —— 十几万文件逐个起进程会让构建从两分钟变十几分钟，
# tar 自己的 --mtime 已经能把成员时间归一到固定值。
( cd "$WORK/rootfs" && tar --hard-dereference --numeric-owner --owner=0 --group=0 \
    --mtime="@$SOURCE_DATE_EPOCH" -czf "$OUT/rootfs.tar.gz" . )

# ── 6. 清单 ───────────────────────────────────────────────────────────
say "6/6 生成 manifest.json"
INSTALLED_BYTES="$(du -sb "$WORK/rootfs" | cut -f1)"
ARCHIVE_BYTES="$(stat -c %s "$OUT/rootfs.tar.gz")"
ARCHIVE_SHA="$(sha256sum "$OUT/rootfs.tar.gz" | cut -d' ' -f1)"
PROOT_SHA="$(sha256sum "$OUT/bin/proot" | cut -d' ' -f1)"
LOADER_SHA="$(sha256sum "$OUT/bin/loader" | cut -d' ' -f1)"
TALLOC_SHA="$(sha256sum "$OUT/bin/libtalloc.so.2" | cut -d' ' -f1)"
SHMEM_SHA="$(sha256sum "$OUT/bin/libandroid-shmem.so" | cut -d' ' -f1)"

python3 - "$OUT/manifest.json" <<JSON
import json, sys
release_url = "$RUNTIME_RELEASE_URL"
urls = [
    "https://gh-proxy.org/" + release_url,
    "https://museai.ltd/api/runtime/rootfs-$ABI.tar.gz",
    release_url,
]
manifest = {
    "runtimeId": "ubuntu-node",
    "revision": "$RUNTIME_REVISION",
    "abi": "$ABI",
    "upstream": {"ubuntu": "$UBUNTU_VERSION", "node": "$NODE_VERSION", "python": "$PYTHON_MINOR"},
    "archive": {
        "name": "rootfs.tar.gz",
        "sizeBytes": int("$ARCHIVE_BYTES"),
        "sha256": "$ARCHIVE_SHA",
        "urls": urls,
    },
    "installedBytes": int("$INSTALLED_BYTES"),
    "bins": {
        "proot": "$PROOT_SHA",
        "loader": "$LOADER_SHA",
        "libtalloc.so.2": "$TALLOC_SHA",
        "libandroid-shmem.so": "$SHMEM_SHA",
    },
}
json.dump(manifest, open(sys.argv[1], "w"), indent=2)
JSON

say "完成"
ls -lh "$OUT" "$OUT/bin" >&2
cat "$OUT/manifest.json"

# ── 7. jniLibs 产物 ─────────────────────────────────────────────────
# proot 的 DT_NEEDED 写的是 libtalloc.so.2，而 AGP 只收形如 lib*.so 的文件。
# 不改名就只能把库放到应用私有目录再碰运气（Android 10 后不保证能加载），
# 所以这里把 NEEDED 与 SONAME 一起改成 libtalloc.so，两个文件都规规矩矩进 jniLibs。
say "7/7 生成 jniLibs 产物（patchelf 改 SONAME）"
JNI="$OUT/jniLibs"
mkdir -p "$JNI"
if command -v patchelf >/dev/null 2>&1; then
  cp "$OUT/bin/proot" "$JNI/libmuse_proot.so"
  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$JNI/libmuse_proot.so"
  cp "$OUT/bin/libtalloc.so.2" "$JNI/libtalloc.so"
  patchelf --set-soname libtalloc.so "$JNI/libtalloc.so"
  cp "$OUT/bin/libandroid-shmem.so" "$JNI/libandroid-shmem.so"
  cp "$OUT/bin/loader" "$JNI/libmuse_proot_loader.so"
  chmod +x "$JNI/libmuse_proot.so" "$JNI/libmuse_proot_loader.so"
  echo "  NEEDED(proot): $(readelf -d "$JNI/libmuse_proot.so" | grep -c NEEDED) 项" >&2
else
  echo "  [跳过] 没装 patchelf，无法生成 jniLibs 产物" >&2
fi
ls -lh "$JNI" >&2
if [ -n "${MUSE_RT_COPY_TO:-}" ]; then
  mkdir -p "$MUSE_RT_COPY_TO"
  cp -a "$OUT/." "$MUSE_RT_COPY_TO/"
  echo "已拷到 $MUSE_RT_COPY_TO" >&2
fi
