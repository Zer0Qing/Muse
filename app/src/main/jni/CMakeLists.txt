# v2.x 终端二期:自编译 PTY 桥(自写 forkpty 实现,零第三方代码)
cmake_minimum_required(VERSION 3.22.1)
project("pty")

add_library(pty SHARED pty.c)

# Android 15+ 16KB 页支持:显式要求 16KB 对齐
# (4KB 页设备向后兼容;16KB 页设备缺少该对齐会 dlopen 失败)
target_link_options(pty PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

find_library(log-lib log)
target_link_libraries(pty ${log-lib})

# P1-B: exec 垫片(LD_PRELOAD;拦截 execve/execvp/execvpe 做名称映射与 shebang 解析)
add_library(muse_exec SHARED muse_exec.c)
target_link_options(muse_exec PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
find_library(dl-lib dl)
target_link_libraries(muse_exec ${dl-lib})

# v2.7 扩展运行时(Python):内置 Python 可执行入口 stub。
# python.org 的 Android 包只提供 libpython3.14.so,没有 bin/python3;本 stub 用
# dlopen 加载 libpython 并调 Py_BytesMain,产出可被 ProcessBuilder exec 的入口。
#
# ⚠️ 关键:必须构建为 **可执行文件**(带 PT_INTERP + 非零入口点)。
#    add_library(SHARED) 产出的是共享库(e_entry=0x0 且无 PT_INTERP),
#    被 exec 时会跳到地址 0 直接 SIGSEGV(exit=139,已实测)。
#    用 add_executable + PREFIX/SUFFIX 覆盖,产出 libmuse_python.so 命名的真可执行。
# 用 dlopen 而非直接链接 libpython:普通构建不要求 Python 包已下载(二进制不入库)。
# 详见 app/src/main/jni/muse_python.c 头部注释。
add_executable(muse_python muse_python.c)
set_target_properties(muse_python PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    OUTPUT_NAME "muse_python")
target_link_options(muse_python PRIVATE
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
find_library(dl-lib-python dl)
target_link_libraries(muse_python ${dl-lib-python})
