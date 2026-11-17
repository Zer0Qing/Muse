package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import java.io.File

/**
 * Node 子进程的统一启动器。
 *
 * 上层只描述「要跑什么参数」，这里决定这次用外置运行环境（proot）还是包内 Node。
 * 这样插件、MCP、脚本工具就不会各自复制一份路径判断，也不会在移除包内 Node 后
 * 出现某条路径悄悄失效。
 *
 * 启动顺序：
 *  1. 外置运行环境已安装且当前 ABI 有 proot → 用 rootfs 里的 /usr/local/bin/node；
 *  2. 外置未安装但 APK 里还有 libmuse_node.so → 用包内 Node（兼容旧设备与降级）；
 *  3. 两者都没有 → [Missing]，调用方返回 runtime_missing 结构化错误。
 */
object NodeLauncher {

    /** 启动结果。 */
    sealed interface Launch {
        /** 已配置好的进程构造器；调用方可继续设置重定向、目录与启动。 */
        data class Ready(val builder: ProcessBuilder, val external: Boolean) : Launch

        /** 没有可用的 Node；[errorJson] 可直接作为 runtime_missing 回执。 */
        data class Missing(val errorJson: String = RuntimeMissing.toJson()) : Launch
    }

    /** 是否有任一可用的 Node（外置或包内）。 */
    fun isAvailable(context: Context): Boolean {
        if (RuntimeBridge.isReady(context) && ProotRuntime(context, RuntimeStore(context)).isAvailable()) return true
        return MuseRuntime.nodeBinary(context).isFile
    }

    /**
     * 准备一个执行 [args] 的 Node 进程。
     *
     * @param args 追加在 node 之后的参数；其中的宿主 filesDir 路径会自动翻译成 guest 路径
     * @param workdir 工作目录（宿主路径）；为 null 时使用 rootfs 的 /
     * @param env 额外环境变量；值里的宿主 filesDir 路径同样会翻译
     */
    fun prepareNode(
        context: Context,
        args: List<String>,
        workdir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): Launch {
        val store = RuntimeStore(context)
        val proot = ProotRuntime(context, store)
        if (RuntimeBridge.isReady(context) && proot.isAvailable()) {
            val guestArgs = args.map { RuntimeBridge.toGuestPath(context, it) }
            val guestEnv = env.mapValues { (_, value) -> RuntimeBridge.toGuestPath(context, value) }
            val builder = proot.processBuilder(
                id = RuntimeId.UBUNTU_NODE,
                argv = listOf(GUEST_NODE_BIN) + guestArgs,
                binds = listOf(bindFiles(context)),
                env = guestEnv,
                workdirGuest = workdir?.let { RuntimeBridge.toGuestPath(context, it.absolutePath) } ?: "/",
            )
            if (builder != null) return Launch.Ready(builder, external = true)
            Logger.w(TAG, "外置运行环境已装但 proot 不可用，尝试回退包内 Node")
        }

        val node = MuseRuntime.nodeBinary(context)
        if (!node.isFile) return Launch.Missing()
        val builder = ProcessBuilder(listOf(node.absolutePath) + args).apply {
            workdir?.let {
                it.mkdirs()
                directory(it)
            }
            environment().clear()
            environment().putAll(MuseRuntime.buildEnv(context, env))
        }
        return Launch.Ready(builder, external = false)
    }

    /** filesDir 在 guest 里的绑定参数。 */
    fun bindFiles(context: Context): String =
        "${context.filesDir.absolutePath}:${RuntimeBridge.GUEST_FILES}"

    private const val GUEST_NODE_BIN = "/usr/local/bin/node"
    private const val TAG = "NodeLauncher"
}
