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
