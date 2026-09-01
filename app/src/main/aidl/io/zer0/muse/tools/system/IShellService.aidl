// IShellService.aidl
package io.zer0.muse.tools.system;

/**
 * P3-3: Shizuku UserService 的 Shell 执行接口。
 *
 * 该接口由 [ShellService] 实现,通过 Shizuku.bindUserService() 在 shell 权限进程中运行。
 * 调用方为 [ShizukuAuthorizer],通过 AIDL IPC 跨进程调用。
 *
 * 返回值格式: "<exitCode>\u0000<stdout>\u0000<stderr>"
 *  - 使用 \u0000 (null char) 作为分隔符,shell 输出中不会出现
 *  - exitCode: 命令退出码(0=成功,非0=失败,-1=执行异常)
 *  - stdout: 命令标准输出
 *  - stderr: 命令标准错误
 */
interface IShellService {
    String execute(String command) = 2;

    // Shizuku UserService reserved transaction. The implementation must
    // terminate the privileged process when Shizuku removes the service.
    void destroy() = 16777114;

    // Optional application-defined transaction used by the demo pattern.
    void exit() = 1;
}
