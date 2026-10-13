#!/usr/bin/env bash
# =============================================================================
# Muse Python Runtime Pack 构建脚本（在 WSL / Linux 环境运行）
# -----------------------------------------------------------------------------
# 作用：从 python.org 下载官方 **Android embeddable package**，处理后输出：
#
#   app/src/main/jniLibs/<abi>/libpython3.14.so   ← 解释器（可执行部分，随 APK）
#   app/src/main/jniLibs/<abi>/libpython3.so
#   app/src/main/jniLibs/<abi>/libcrypto_python.so
#   app/src/main/jniLibs/<abi>/libssl_python.so
#   app/src/main/jniLibs/<abi>/libsqlite3_python.so
#   app/src/main/jniLibs/<abi>/_*.cpython-314-<abi>-*.so  ← lib-dynload C 扩展（需 dlopen）
#   app/src/main/assets/muse-python-assets.zip     ← stdlib 纯 Python 数据（解压到 filesDir）
#
# 用法：
#   bash tools/python-pack/build-python-pack.sh both      # arm64 + x86_64（默认）
#   bash tools/python-pack/build-python-pack.sh arm64
#
# 环境依赖（WSL Ubuntu 示例）：
#   sudo apt-get install -y curl ca-certificates
#
# ## 分发拆分：为什么 C 扩展进 jniLibs 而不是 assets
#
# Android 10+（targetSdk 29+）的 W^X 限制禁止应用从可写目录 execve；而 C 扩展
# 需要被 dlopen（映射为可执行内存），最可靠的来源是 APK 安装时解压出的**只读**
# nativeLibraryDir（jniLibs 规范目录，所有 app 都从这里 dlopen）。
#
# 另一个关键理由：**架构隔离**。扩展 .so 是 ABI 相关的（aarch64 / x86_64 各一份），
# 而 stdlib 是纯 Python（跨架构通用）。扩展放 jniLibs/<abi>/ 天然按 ABI 分开；
# 若放共享的 assets 包，两个 ABI 会因同名不同架构而互相覆盖。
#
# 实测：AGP 会把非 lib 前缀的 .so（_ssl.cpython-314-....so）一并打包进
# APK 的 lib/<abi>/，安装时解压到 nativeLibraryDir（见 .gitignore 同源的
# jniLibs 约定）。
#
# ## 为什么不需要 patchelf 改名（与 Node 运行时的关键差异）
#
# python.org 官方包**已经**把与 Node 运行时重名的库做了区分：
#   libssl.so / libcrypto.so / libsqlite3.so 的 SONAME 已是 *_python.so，
#   且全部 67 个 lib-dynload 扩展的 DT_NEEDED 都指向 *_python 变体。
# 因此与 Node 的 libssl.so / libcrypto.so / libsqlite3.so 同名文件**不冲突**，
# 无需 patchelf 二次处理（避免引入 Node 那套 strip 陷阱）。
#
# ## 升级流程（换 Python 版本时逐项同步）
#   1. 修改下方 PY_VER
#   2. 同步 app/src/main/java/io/zer0/muse/runtime/PythonRuntime.kt 的
#      PYTHON_VERSION / PYTHON_DATA_VERSION
#   3. app/src/main/jni/muse_python.c 的库名解析（若主版本号变化）
#   4. 重新构建后跑 PythonRuntimeSelfCheck（connected 测试）验证
#
# ⚠️ 页对齐：Android 15+ 要求 16KB 页对齐（0x4000）。官方包已满足，
#    本脚本会校验，不合格直接失败。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK="${MUSE_PY_WORK:-/tmp/muse-python-pack}"

# ─── 版本锁定 ───────────────────────────────────────────────────────────────
PY_VER="3.14.8"
PY_SERIES="3.14"                 # 用于 libpython3.14.so / python3.14 目录名

# python.org Android embeddable package 命名：python-<ver>-<arch>-linux-android.tar.gz
declare -A ARCH_MAP=(
  [arm64]="aarch64"
  [x86_64]="x86_64"
)
# jniLibs 目录名
declare -A ABI_DIR=(
  [arm64]="arm64-v8a"
  [x86_64]="x86_64"
)

# ─── 需要随包的 .so（来自 prefix/lib/） ─────────────────────────────────────
LIB_SO_FILES=(
  "libpython${PY_SERIES}.so"
  "libpython3.so"
  "libcrypto_python.so"
  "libssl_python.so"
  "libsqlite3_python.so"
)

# ─── stdlib 裁剪：删除移动端无用/超大的子目录 ───────────────────────────────
# test 37MB（CPython 自测套件）、idlelib 2MB（桌面 IDE）、turtledemo 112KB、
# tkinter（Android 无 Tk）。这些不影响常规 import。
PRUNE_DIRS=(
  "test"
  "idlelib"
  "turtledemo"
  "tkinter"
  "lib2to3"
)
# 裁剪 lib-dynload 中对应的 C 扩展
PRUNE_DYNLOAD_GLOBS=(
  "_tkinter.*"
  "_test*.*"
)

fetch_pkg() { # $1=arch(aarch64|x86_64) $2=目标文件
  local arch="$1" out="$2"
  if [ ! -f "$out" ]; then
    local url="https://www.python.org/ftp/python/${PY_VER}/python-${PY_VER}-${arch}-linux-android.tar.gz"
    echo "  下载 $url"
    curl -sSL --retry 3 --connect-timeout 20 -o "$out" "$url"
  fi
}

# 校验一个 ELF 是否 16KB 页对齐
check_align() { # $1=文件
  local al
  al=$(readelf -lW "$1" 2>/dev/null | awk '/LOAD/{print $NF; exit}')
  [ "$al" = "0x4000" ]
}

build_abi() { # $1=arm64|x86_64
  local abi="$1"
  local arch="${ARCH_MAP[$abi]}"
  local outdir="$PROJ_ROOT/app/src/main/jniLibs/${ABI_DIR[$abi]}"
  local base="$WORK/$abi"
  local tgz="$base/python.tar.gz"
  local unpack="$base/unpacked"

  echo "== [$abi] 准备 Python $PY_VER ($arch) =="
  mkdir -p "$base"
  fetch_pkg "$arch" "$tgz"

  if [ ! -d "$unpack/prefix" ]; then
    rm -rf "$unpack"; mkdir -p "$unpack"
    tar xzf "$tgz" -C "$unpack"
  fi
  local prefix="$unpack/prefix"

  echo "== [$abi] 校验页对齐 =="
  local fail=0
  for f in "$prefix/lib"/*.so "$prefix/lib/python${PY_SERIES}/lib-dynload"/*.so; do
    [ -f "$f" ] || continue
    check_align "$f" || { echo "  !! $(basename "$f") 非 16KB 对齐"; fail=1; }
  done
  [ "$fail" = 1 ] && { echo "!! [$abi] 存在非 16KB 对齐文件，终止"; exit 1; }
  echo "  16KB 对齐 ✓"

  echo "== [$abi] 输出核心库 -> $outdir =="
  mkdir -p "$outdir"
  for name in "${LIB_SO_FILES[@]}"; do
    if [ ! -f "$prefix/lib/$name" ]; then
      echo "  !! 缺少 $name"; exit 1
    fi
    cp "$prefix/lib/$name" "$outdir/$name"
  done

  echo "== [$abi] 输出 lib-dynload C 扩展 -> $outdir =="
  local dynload="$prefix/lib/python${PY_SERIES}/lib-dynload"
  local count=0
  for f in "$dynload"/*.so; do
    [ -f "$f" ] || continue
    local bn
    bn="$(basename "$f")"
    # 裁剪无用扩展
    local skip=0
    for g in "${PRUNE_DYNLOAD_GLOBS[@]}"; do
      case "$bn" in $g) skip=1 ;; esac
    done
    [ "$skip" = 1 ] && continue
    cp "$f" "$outdir/$bn"
    count=$((count + 1))
  done
  echo "  已输出 $count 个扩展"

  echo "== [$abi] 完成 =="
  ls -1 "$outdir" | wc -l | xargs echo "  jniLibs/$abi 文件数:"
}

# ─── stdlib 数据包（assets，一次性；纯 Python 跨架构通用）─────────────────
#
# 注意：stdlib 里有一个 **ABI 相关**文件 `_sysconfigdata__android_<abi>.py`
# （pip / sysconfig / 编译扩展时读取）。两个 ABI 各自一个，不能只放一份，
# 否则另一架构上 `import _sysconfigdata` 会 ModuleNotFoundError。
# 处理：把两个 ABI 的该文件都放入 assets，运行时由 PythonRuntime 按实际 ABI
# 建一个 symlink/复制为当前架构期望的名字。
build_stdlib_assets() {
  local stage="$WORK/stdlib-stage"
  local outzip="$PROJ_ROOT/app/src/main/assets/muse-python-assets.zip"

  echo "== [stdlib] 准备 staging =="
  rm -rf "$stage"; mkdir -p "$stage/lib"
  # 以 arm64 的 stdlib 为主（纯 .py 内容两架构一致）
  cp -r "$WORK/arm64/unpacked/prefix/lib/python${PY_SERIES}" "$stage/lib/python${PY_SERIES}"

  echo "== [stdlib] 裁剪 =="
  for d in "${PRUNE_DIRS[@]}"; do
    if [ -d "$stage/lib/python${PY_SERIES}/$d" ]; then
      rm -rf "$stage/lib/python${PY_SERIES}/$d"
      echo "  删除 $d"
    fi
  done

  # lib-dynload 的 .so 是 ABI 相关的（aarch64 / x86_64 各一份），已随 jniLibs/<abi>/
  # 分发。assets 是两 ABI 共享的，不能放扩展（同名不同架构会互相覆盖）。
  # 这里只留一个占位说明，运行时由 PythonRuntime.setupDynloadLinks() 用 symlink
  # 把 lib-dynload 指向 nativeLibraryDir。
  local dl="$stage/lib/python${PY_SERIES}/lib-dynload"
  if [ -d "$dl" ]; then
    rm -rf "$dl"
    mkdir -p "$dl"
    cat > "$dl/README.txt" <<'EOF'
lib-dynload C extensions are shipped per-ABI in jniLibs/<abi>/ and symlinked here at runtime.
See PythonRuntime.setupDynloadLinks().
EOF
  fi

  # 清理 __pycache__（体积 + 架构无关但无用）
  find "$stage" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true

  # v2.7: 补入所有 ABI 的 _sysconfigdata（ABI 相关，见函数头注释）。
  # 当前架构期望的名字由运行时按 ABI 决定，两个都留。
  for _abi in arm64 x86_64; do
    _arch="${ARCH_MAP[$_abi]}"
    _scd="$WORK/$_abi/unpacked/prefix/lib/python${PY_SERIES}/_sysconfigdata__android_${_arch}-linux-android.py"
    if [ -f "$_scd" ]; then
      cp "$_scd" "$stage/lib/python${PY_SERIES}/"
      echo "  补入 _sysconfigdata: $_abi"
    else
      echo "  !! 未找到 $_abi 的 _sysconfigdata: $_scd"
    fi
  done

  echo "Python ${PY_VER} stdlib" > "$stage/VERSION.txt"

  echo "== [stdlib] 打包 -> $outzip =="
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
  both)   TARGET_LIST=(arm64 x86_64) ;;
  arm64)  TARGET_LIST=(arm64) ;;
  x86_64) TARGET_LIST=(x86_64) ;;
  *) echo "用法: $0 [both|arm64|x86_64]"; exit 1 ;;
esac

mkdir -p "$WORK"
for t in "${TARGET_LIST[@]}"; do build_abi "$t"; done
build_stdlib_assets

echo
echo "============================================================"
echo "构建完成。产物："
echo "  app/src/main/jniLibs/{arm64-v8a,x86_64}/  （libpython + 依赖 + lib-dynload 扩展）"
echo "  app/src/main/assets/muse-python-assets.zip （stdlib 数据）"
echo "提醒：跑 :app:connectedDebugAndroidTest -P...class=...PythonRuntimeSelfCheck 做验证。"
