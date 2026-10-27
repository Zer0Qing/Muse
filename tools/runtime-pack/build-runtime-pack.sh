#!/usr/bin/env bash
# =============================================================================
# Muse Runtime Pack 构建脚本（在 WSL / Linux 环境运行）
# -----------------------------------------------------------------------------
# 作用：从 Termux 官方仓库下载 Node.js 运行时（node 主程序 + 依赖共享库 + npm），
#       经 patchelf 规范化（改名 / SONAME / DT_NEEDED / RPATH=$ORIGIN）后输出：
#
#   app/src/main/jniLibs/<abi>/          ← 可执行部分（node + 9 个依赖库）
#   app/src/main/assets/muse-runtime-assets.zip ← 数据部分（npm，纯 JS）
#
# 用法：
#   bash tools/runtime-pack/build-runtime-pack.sh both     # arm64 + x86_64（默认）
#   bash tools/runtime-pack/build-runtime-pack.sh arm64
#
# 环境依赖（WSL Ubuntu 示例）：
#   sudo apt-get install -y curl ca-certificates patchelf
#
# 升级流程（换 Node / 依赖版本时逐项同步）：
#   1. 修改下方「版本锁定」常量
#   2. 更新 app/.../runtime/MuseRuntime.kt 的 RUNTIME_DATA_VERSION（npm 版本变化时）
#   3. 若新增依赖库，补充 app/build.gradle.kts 的 jniLibs.keepDebugSymbols 列表
#   4. 重新构建后，跑 RuntimeSelfCheckTest（connected 测试）验证
#
# ⚠️ 重要陷阱（血泪教训，勿改）：
#   AGP 的 stripDebugDebugSymbols 任务（llvm-strip）对 patchelf 处理过的 ELF 会
#   重排段布局、破坏程序头与内容映射，导致运行时 SIGSEGV。因此所有运行时库都
#   必须列入 build.gradle.kts 的 keepDebugSymbols 以跳过 strip。
#   验证方法：构建后对比 app/build/intermediates/stripped_native_libs/.../lib*.so
#   与 app/src/main/jniLibs/.../lib*.so 的 md5，必须一致。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK="${MUSE_RUNTIME_WORK:-/tmp/muse-runtime-pack}"

TERMUX_BASE="https://packages.termux.dev/apt/termux-main/pool/main"

# ─── 版本锁定 ───────────────────────────────────────────────────────────────
NODE_VER="26.12.1"
LIBCXX_VER="30"
OPENSSL_VER="1%3A3.6.5"   # URL 中 ':' 需转义为 %3A
CARES_VER="1.34.9"
ICU_VER="78.3"
SQLITE_VER="3.54.1"
ZLIB_VER="1.3.2"
NPM_VER="11.21.1"
LIBFFI_VER="3.8.0"

# ─── 文件映射：源实体文件名 -> 输出名（jniLibs 只接受 lib*.so 命名） ────────
declare -A FILEMAP=(
  ["libc++_shared.so"]="libc++_shared.so"
  ["libssl.so.3"]="libssl.so"
  ["libcrypto.so.3"]="libcrypto.so"
  ["libcares.so"]="libcares.so"
  ["libicui18n.so.78.3"]="libicui18n.so"
  ["libicuuc.so.78.3"]="libicuuc.so"
  ["libicudata.so.78.3"]="libicudata.so"
  ["libffi.so"]="libffi.so"
  ["libsqlite3.54.1.so"]="libsqlite3.so"
  ["libz.so.1.3.2"]="libz.so"
)

# ─── DT_NEEDED 映射：原字符串 -> 新名（含链接名与实体名两种形态） ───────────
declare -A NEEDMAP=(
  ["libz.so.1"]="libz.so"
  ["libz.so.1.3.2"]="libz.so"
  ["libcrypto.so.3"]="libcrypto.so"
  ["libssl.so.3"]="libssl.so"
  ["libicui18n.so.78"]="libicui18n.so"
  ["libicui18n.so.78.3"]="libicui18n.so"
  ["libicuuc.so.78"]="libicuuc.so"
  ["libicuuc.so.78.3"]="libicuuc.so"
  ["libicudata.so.78"]="libicudata.so"
  ["libicudata.so.78.3"]="libicudata.so"
  ["libsqlite3.so.3.53.4"]="libsqlite3.so"
)

# ─── Termux 包 URL 解析 ─────────────────────────────────────────────────────
pkg_url() { # $1=包 key  $2=abi(arm64|x86_64|all)
  local key="$1" abi="$2"
  # 本脚本内部叫 arm64，Termux 仓库叫 aarch64；x86_64 两边一致。
  [ "$abi" = "arm64" ] && abi="aarch64"
  case "$key" in
    nodejs)    echo "$TERMUX_BASE/n/nodejs/nodejs_${NODE_VER}_${abi}.deb" ;;
    libffi)     echo "$TERMUX_BASE/libf/libffi/libffi_${LIBFFI_VER}_${abi}.deb" ;;
    libcxx)     echo "$TERMUX_BASE/libc/libc%2B%2B/libc%2B%2B_${LIBCXX_VER}_${abi}.deb" ;;
    openssl)    echo "$TERMUX_BASE/o/openssl/openssl_${OPENSSL_VER}_${abi}.deb" ;;
    cares)      echo "$TERMUX_BASE/c/c-ares/c-ares_${CARES_VER}_${abi}.deb" ;;
    libicu)     echo "$TERMUX_BASE/libi/libicu/libicu_${ICU_VER}_${abi}.deb" ;;
    libsqlite)  echo "$TERMUX_BASE/libs/libsqlite/libsqlite_${SQLITE_VER}_${abi}.deb" ;;
    zlib)       echo "$TERMUX_BASE/z/zlib/zlib_${ZLIB_VER}_${abi}.deb" ;;
    npm)        echo "$TERMUX_BASE/n/npm/npm_${NPM_VER}_all.deb" ;;
    *) echo "!! 未知包: $key" >&2; exit 1 ;;
  esac
}

fetch_deb() { # $1=包 key $2=abi $3=目标文件
  if [ ! -f "$3" ]; then
    echo "  下载 $1 ($2) ..."
    curl -sSL --retry 3 --connect-timeout 20 -o "$3" "$(pkg_url "$1" "$2")"
  fi
}

unpack_deb() { # $1=deb 文件 $2=输出目录
  local deb="$1" out="$2"
  rm -rf "$out"; mkdir -p "$out"
  (cd "$out" && ar x "$deb")
  local dt
  dt=$(cd "$out" && ls data.tar.* 2>/dev/null | head -1)
  [ -n "$dt" ] || { echo "!! $deb 无 data.tar"; exit 1; }
  (cd "$out" && mkdir -p data && tar xf "$dt" -C data)
}

# ─── 单 ABI 组装 ────────────────────────────────────────────────────────────
build_abi() { # $1=arm64|x86_64
  local abi="$1"
  local base="$WORK/$abi"
  local debs="$base/debs"
  local work="$base/pack"
  # Android 的 jniLibs 目录名与 Termux 的 ABI 名不一致
  local outabi="$abi"
  [ "$abi" = "arm64" ] && outabi="arm64-v8a"
  local outdir="$PROJ_ROOT/app/src/main/jniLibs/$outabi"

  echo "== [$abi] 下载 =="
  mkdir -p "$debs"
  for key in nodejs libcxx openssl cares libicu libsqlite zlib libffi; do
    fetch_deb "$key" "$abi" "$debs/$key.deb"
  done

  echo "== [$abi] 解包 =="
  for key in nodejs libcxx openssl cares libicu libsqlite zlib libffi; do
    [ -d "$base/unpacked/$key" ] || unpack_deb "$debs/$key.deb" "$base/unpacked/$key"
  done

  echo "== [$abi] 收集源文件 =="
  rm -rf "$work"; mkdir -p "$work"
  local missing=0
  for f in "${!FILEMAP[@]}"; do
    local src
    src=$(find "$base/unpacked" -name "$f" -type f | head -1 || true)
    if [ -z "$src" ]; then echo "  !! 未找到 $f"; missing=1; continue; fi
    cp "$src" "$work/$f"
  done
  [ "$missing" = 1 ] && { echo "!! [$abi] 依赖缺失，终止"; exit 1; }

  local node_src
  node_src=$(find "$base/unpacked/nodejs" -type f -name node | head -1)
  cp "$node_src" "$work/libmuse_node.so"

  echo "== [$abi] patchelf 处理 =="
  (cd "$work"
    for f in "${!FILEMAP[@]}"; do
      patchelf --set-soname "${FILEMAP[$f]}" "$f"
    done
    for f in *; do
      [ -f "$f" ] || continue
      for old in "${!NEEDMAP[@]}"; do
        new="${NEEDMAP[$old]}"
        [ "$old" = "$new" ] && continue
        patchelf --replace-needed "$old" "$new" "$f" 2>/dev/null || true
      done
      patchelf --set-rpath '$ORIGIN' "$f"
    done
    for f in "${!FILEMAP[@]}"; do
      new="${FILEMAP[$f]}"
      [ "$f" != "$new" ] && mv "$f" "$new"
    done
  )

  echo "== [$abi] 16KB 页对齐校验 =="
  local fail=0
  for f in "$work"/*; do
    local al
    al=$(readelf -lW "$f" | awk '/LOAD/{print $NF; exit}')
    [ "$al" = "0x4000" ] || { echo "  !! $(basename "$f") align=$al"; fail=1; }
  done
  [ "$fail" = 1 ] && { echo "!! [$abi] 存在非 16KB 对齐文件"; exit 1; }
  echo "  全部 16KB 对齐 ✓"

  echo "== [$abi] 输出 -> $outdir =="
  mkdir -p "$outdir"
  cp "$work"/* "$outdir/"
  ls -lh "$outdir"
}

# ─── npm 数据包（assets，一次性） ────────────────────────────────────────────
build_npm_assets() {
  local base="$WORK/npm"
  local stage="$base/stage"
  local outzip="$PROJ_ROOT/app/src/main/assets/muse-runtime-assets.zip"

  echo "== [npm] 下载并解包 =="
  mkdir -p "$base/debs"
  fetch_deb npm all "$base/debs/npm.deb"
  [ -d "$base/unpacked" ] || unpack_deb "$base/debs/npm.deb" "$base/unpacked"

  rm -rf "$stage"; mkdir -p "$stage"
  local npm_dir
  npm_dir=$(find "$base/unpacked" -type d -path '*usr/lib/node_modules/npm' | head -1)
  cp -r "$npm_dir" "$stage/npm"
  echo "npm $(grep -m1 '"version"' "$stage/npm/package.json" | sed 's/.*: *"\([^"]*\)".*/\1/')" > "$stage/VERSION.txt"

  echo "== [npm] 打包 -> $outzip =="
  [ -f "$outzip" ] && rm -f "$outzip"
  python3 - "$stage" "$outzip" <<'PYEOF'
import sys, zipfile, os
root, out = sys.argv[1], sys.argv[2]
count = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for dirpath, dirnames, filenames in os.walk(root):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            z.write(full, os.path.relpath(full, root))
            count += 1
print(f"  zip 完成: {count} 个文件, {os.path.getsize(out)/1e6:.2f}MB")
PYEOF
}

# ─── 主流程 ─────────────────────────────────────────────────────────────────
TARGETS="${1:-both}"
case "$TARGETS" in
  both)  TARGET_LIST=(arm64 x86_64) ;;
  arm64) TARGET_LIST=(arm64) ;;
  x86_64) TARGET_LIST=(x86_64) ;;
  *) echo "用法: $0 [both|arm64|x86_64]"; exit 1 ;;
esac

mkdir -p "$WORK"
for t in "${TARGET_LIST[@]}"; do build_abi "$t"; done
build_npm_assets

echo
echo "============================================================"
echo "构建完成。产物："
echo "  app/src/main/jniLibs/{arm64-v8a,x86_64}/  （可执行部分）"
echo "  app/src/main/assets/muse-runtime-assets.zip （npm 数据）"
echo "提醒：跑 :app:connectedDebugAndroidTest -P...class=...RuntimeSelfCheckTest 做验证。"
