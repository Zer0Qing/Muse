/*
 * muse_python.c — Muse 内置 Python 运行时的可执行入口（stub）。
 *
 * ## 为什么需要这个 stub
 *
 * python.org 的 Android embeddable package 只提供 libpython3.14.so，**没有 bin/python3
 * 可执行文件**（Android 上没有"系统级安装 Python"的概念，官方文档明确说只能以嵌入模式使用）。
 * Muse 采用与内置 Node 运行时同构的「子进程模型」：需要一个可 exec 的入口，
 * 因此这里写一个几十行的启动器，由它加载 libpython 并调用 Py_BytesMain。
 *
 * ## 为什么用 dlopen 而不是直接链接 libpython
 *
 * 若在 CMake 里直接链接 libpython3.14.so，则**每次 Gradle 构建都要求 jniLibs 里已存在
 * 该库**——而 Python 包是构建前由 tools/python-pack 下载生成的（二进制不入库，与 Node
 * 运行时同一约定）。用 dlopen 后：普通构建不依赖 Python 包存在，运行时才解析；
 * 未准备运行时只是 Python 能力不可用，不会让整个工程构建失败。
 *
 * ## 库定位策略
 *
 * 通过 /proc/self/exe 找到自身所在目录（即 APK 安装后的 nativeLibraryDir），
 * 再从同目录加载 libpython3.14.so。不依赖 LD_LIBRARY_PATH 或 cwd，
 * 也不受 Android 链接器命名空间搜索路径差异影响。
 *
 * ## 构建
 *
 * 由 app/src/main/jni/CMakeLists.txt 编译为 libmuse_python.so（jniLibs 规范要求
 * 可执行文件以 lib*.so 命名）。需 16KB 页对齐（Android 15+ 要求）。
 */

#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* libpython3.14.so 中的主入口签名（见 CPython Programs/python.c）。 */
typedef int (*py_bytes_main_fn)(int argc, char **argv);

/**
 * 解析自身所在目录（nativeLibraryDir），拼出 libpython 的绝对路径。
 *
 * @param out  输出缓冲
 * @param size 缓冲大小
 * @return 0 成功，-1 失败
 */
static int resolve_libpython_path(char *out, size_t size) {
    char exe_path[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
    if (n <= 0) {
        return -1;
    }
    exe_path[n] = '\0';

    /* 截掉文件名，保留目录（含结尾 '/'）。 */
    char *slash = strrchr(exe_path, '/');
    if (slash == NULL) {
        return -1;
    }
    *(slash + 1) = '\0';

    int written = snprintf(out, size, "%slibpython3.14.so", exe_path);
    if (written <= 0 || (size_t)written >= size) {
        return -1;
    }
    return 0;
}

int main(int argc, char **argv) {
    char lib_path[PATH_MAX];

    if (resolve_libpython_path(lib_path, sizeof(lib_path)) != 0) {
        fprintf(stderr, "muse_python: 无法定位自身目录（/proc/self/exe）\n");
        return 127;
    }

    /* RTLD_NOW: 立刻解析全部符号，尽早暴露缺失依赖；
     * RTLD_GLOBAL: 让 libpython 的符号全局可见，供后续 dlopen 的
     *   lib-dynload/*.so C 扩展（其 DT_NEEDED 指向 libpython3.14.so）复用。 */
    void *handle = dlopen(lib_path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        fprintf(stderr, "muse_python: 加载 %s 失败: %s\n", lib_path, dlerror());
        return 127;
    }

    py_bytes_main_fn py_main = (py_bytes_main_fn)dlsym(handle, "Py_BytesMain");
    if (py_main == NULL) {
        fprintf(stderr, "muse_python: 未找到 Py_BytesMain: %s\n", dlerror());
        return 127;
    }

    /* 交棒给 CPython：它会处理 argv（-c / 脚本路径 / -m / REPL）与退出码。 */
    return py_main(argc, argv);
}
