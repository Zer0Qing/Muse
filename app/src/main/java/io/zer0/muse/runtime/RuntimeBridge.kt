package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import java.io.File

/**
 * 内置 Node 与「外置运行时」之间的桥。
 *
 * 目标是让上层不用关心这次到底跑在哪：装了运行环境就走 proot 里的 Node，
 * 没装就退回 APK 自带的 Node（过渡期还在包里），两样都没有才报缺。
 *
 * 路径翻译是这里最要紧的一件事：脚本文件、node_modules 都在**宿主**路径下，
 * 而 guest 里看不到它们。约定把宿主 `filesDir` 整个绑到 `[GUEST_FILES]`，
 * 于是 `filesDir/a/b` 在 guest 里就是 `/muse-files/a/b`，一次字符串替换即可。
 */
object RuntimeBridge {

    /** 宿主 filesDir 在 guest 里的挂载点。 */
    const val GUEST_FILES = "/muse-files"

    /** 运行环境是否可用（装了且自检过，且当前 ABI 确实带 proot）。 */
    fun isReady(context: Context): Boolean {
        val store = RuntimeStore(context)
        if (store.state(RuntimeId.UBUNTU_NODE) !is RuntimeState.Ready) return false
        return ProotRuntime(context, store).isAvailable()
    }

    /** 宿主路径 → guest 路径；不在 filesDir 下就原样返回（调用方负责只传合法路径）。 */
    fun toGuestPath(context: Context, hostPath: String): String =
        toGuestPath(context.filesDir.absolutePath, hostPath)

    internal fun toGuestPath(filesRoot: String, hostPath: String): String {
        val root = filesRoot.trimEnd('/', '\\')
        return if (hostPath == root) {
            GUEST_FILES
        } else if (hostPath.startsWith("$root/")) {
            GUEST_FILES + hostPath.removePrefix(root)
        } else {
            hostPath
        }
    }

    /**
     * 通过 proot 执行内置 Node。
     *
     * @return 运行环境不可用时返回 null，调用方按「回退到包内 Node」处理。
     */
    suspend fun execNode(
        context: Context,
        args: List<String>,
        timeoutMs: Long,
        workdir: File?,
        env: Map<String, String>,
    ): MuseRuntime.ExecResult? {
        if (!isReady(context)) return null
        val proot = ProotRuntime(context, RuntimeStore(context))
        if (!proot.isAvailable()) {
            Logger.w(TAG, "运行环境已装，但这个 ABI 没带 proot，回退包内 Node")
            return null
        }
        val guestFiles = "${context.filesDir.absolutePath}:$GUEST_FILES"
        val guestArgs = args.map { toGuestPath(context, it) }
        val guestEnv = env.mapValues { (_, v) -> toGuestPath(context, v) } +
            mapOf("MUSE_GUEST_FILES" to GUEST_FILES)
        val result = proot.exec(
            id = RuntimeId.UBUNTU_NODE,
            argv = listOf("/usr/local/bin/node") + guestArgs,
            timeoutMs = timeoutMs,
            binds = listOf(guestFiles),
            env = guestEnv,
            workdirGuest = workdir?.let { toGuestPath(context, it.absolutePath) } ?: "/",
        )
        Logger.i(TAG, "guest node 执行完成: exit=${result.exitCode} timeout=${result.timedOut}")
        return MuseRuntime.ExecResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
        )
    }

    private const val TAG = "RuntimeBridge"
}
