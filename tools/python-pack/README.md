# Muse Python Pack（内置 Python 运行时）

Muse 应用沙盒的「Python 扩展运行时」构建工具。为助手与插件提供**完全内嵌**的
Python 3.14 执行环境，不依赖用户安装 Termux 或任何外部应用。

## 为什么需要它

内置 Node 运行时（`tools/runtime-pack`）解决了 JS 生态；Python 生态（数据处理、
科学计算、脚本自动化、大量第三方库）需要一套对等的运行时。两者架构同构：
**可执行部分随 APK 进 jniLibs，数据部分进 assets 首次使用时解压**，以规避
Android 10+ 的 W^X 限制。

## 架构

```
app/
├── src/main/jniLibs/<abi>/                    ← 可执行部分（随 APK 分发）
│   ├── libmuse_python.so                      ← 自写 PIE 可执行 stub（入口）
│   ├── libpython3.14.so / libpython3.so       ← CPython 解释器
│   ├── libssl_python.so / libcrypto_python.so ← OpenSSL（注意 _python 后缀）
│   ├── libsqlite3_python.so                   ← SQLite
│   └── *.cpython-314-<abi>-linux-android.so   ← 58 个 lib-dynload C 扩展
└── src/main/assets/
    └── muse-python-assets.zip                 ← stdlib（纯 Python）+ 双 ABI sysconfigdata
```

**消费方**：`app/src/main/java/io/zer0/muse/runtime/PythonRuntime.kt`（路径 /
解压 / CA bundle / pip / 子进程环境）。

## 三个关键设计

### 1. 为什么要有 `libmuse_python.so`（自写 stub）

python.org 的 Android embeddable package **只提供 `libpython3.14.so`，没有
`bin/python3` 可执行文件**（Android 没有"系统级安装 Python"的概念，官方文档
明确说只能以嵌入模式使用）。

Muse 采用与 Node 运行时同构的**子进程模型**：需要一个可 exec 的入口。
`app/src/main/jni/muse_python.c` 是几十行的启动器，用 `dlopen` 加载
`libpython3.14.so` 并调用 `Py_BytesMain`。

⚠️ **必须构建为可执行文件**（`add_executable` + `PREFIX/SUFFIX` 覆盖），
不能用 `add_library(SHARED)` —— 后者产出 `e_entry=0x0` 且无 `PT_INTERP`，
被 exec 时跳到地址 0 直接 SIGSEGV（exit=139，已实测）。

用 `dlopen` 而非直接链接：普通 Gradle 构建不要求 Python 包已下载
（二进制不入库），未准备运行时只是 Python 能力不可用，不会让整个工程构建失败。

### 2. 为什么 C 扩展进 jniLibs 而不是 assets

- **W^X**：Android 10+ 禁止从可写目录 `execve`；C 扩展需被 `dlopen`
  （映射为可执行内存），只读的 nativeLibraryDir 是唯一可靠来源。
- **架构隔离**：扩展 `.so` 是 ABI 相关的（aarch64 / x86_64 各一份），
  而 stdlib 是纯 Python（跨架构通用）。扩展放 `jniLibs/<abi>/` 天然按 ABI 分开；
  若放共享 assets 包，两个 ABI 会因同名不同架构互相覆盖。

实测：AGP 会把非 `lib` 前缀的 `.so`（`_ssl.cpython-314-....so`）一并打包进
APK 的 `lib/<abi>/`，安装时解压到 nativeLibraryDir。

### 3. 为什么无需 patchelf 改名（与 Node 运行时的关键差异）

python.org 官方包**已经**把与 Node 运行时重名的库做了区分：
`libssl.so` / `libcrypto.so` / `libsqlite3.so` 的 SONAME 已是 `*_python.so`，
且全部 lib-dynload 扩展的 `DT_NEEDED` 都指向 `*_python` 变体。
因此与 Node 的同名文件**不冲突**，无需 patchelf 二次处理
（也避免了 Node 那套 strip 陷阱）。

## 两个 Android 特有坑（已在代码中处理）

1. **CA 证书**：Android 的 `/system/etc/security/cacerts` 文件名是 OpenSSL
   **old hash**（MD5），而 OpenSSL 3.x 按 **new hash**（SHA1）查找，
   `SSL_CERT_DIR` 指向它 `loaded certs=0` → TLS 报 CERTIFICATE_VERIFY_FAILED。
   `PythonRuntime.ensureCaBundle` 把系统 CA 合并成单个 PEM bundle，
   用 `SSL_CERT_FILE` 指向它。
2. **`_sysconfigdata` 是 ABI 相关文件**：stdlib 里
   `_sysconfigdata__android_<abi>.py` 每个架构一份；stdlib 走共享 assets 时
   必须两 ABI 各放一份，否则另一架构上 `import _sysconfigdata` 报
   ModuleNotFoundError（pip/sysconfig 直接不可用）。

## 重建

在 WSL / Linux 下：

```bash
bash tools/python-pack/build-python-pack.sh both     # arm64 + x86_64（默认）
bash tools/python-pack/build-python-pack.sh arm64
```

产物直接写入 `app/src/main/jniLibs/` 与 `app/src/main/assets/`
（这两个位置已 gitignore，二进制不入库）。

## 验证

```bash
# 模拟器（需先启动 AVD 且 adb devices 在线）
./gradlew :app:connectedDebugAndroidTest -PnoUniversal \
  "-Pandroid.testInstrumentationRunnerArguments.class=io.zer0.muse.runtime.PythonRuntimeSelfCheckTest"
```

14 项自检覆盖：stub 存在 → `python -V` → stdlib 解压 → 基础求值 →
C 扩展（ssl/sqlite3/zlib/hashlib）→ 文件 IO → TLS 网络 →
脚本协议 → 工具层 JSON → 错误传播 → 插件函数调用 → 插件缺函数报错 →
pip 引导 → pip 安装纯 Python 包并 import。

## 版本锁定（2026-10）

| 组件 | 版本 | 来源 |
|---|---|---|
| Python | 3.14.8 | python.org Android embeddable package |
| OpenSSL | 随包 | python.org |
| SQLite | 随包 | python.org |
| pip | 26.2.1 | 随包 ensurepip `_bundled` wheel |

> Python 采用 PSF 许可证；随包的 OpenSSL 采用 Apache-2.0。

## ⚠️ 陷阱清单

1. **stub 必须是可执行文件**：`add_executable` + `PREFIX/SUFFIX`，
   不可用 `add_library(SHARED)`（见上文设计 1）。
2. **升级版本时同步改三处**：本目录 `PY_VER` → `PythonRuntime.PYTHON_VERSION`
   → `PythonRuntime.PYTHON_DATA_VERSION`（后者多一个后缀用于强制重新解压）。
3. **`extractNativeLibs` 必须为 true**（build.gradle 已通过
   `useLegacyPackaging = true` 设置），否则库不落盘、无法 exec。
4. **16KB 页对齐**：Android 15+ 要求；官方包已满足，脚本会校验，
   不合格直接失败。
5. **stdlib 裁剪**：默认删除 `test`（37MB）/`idlelib`/`turtledemo`/`tkinter`，
   不影响常规 import；若某库需要这些，改 `PRUNE_DIRS`。
