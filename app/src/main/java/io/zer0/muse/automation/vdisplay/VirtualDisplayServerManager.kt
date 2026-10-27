package io.zer0.muse.automation.vdisplay

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.executors.ShellExecutor
import io.zer0.muse.vdproto.IVirtualDisplayService
import io.zer0.muse.vdproto.VdContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * v2.2.1 虚拟屏:服务端生命周期管理(部署 / 启动 / 等待 binder / 复用)。
 *
 * 启动链路(参考拆解笔记 v1 最小集):
 *  1. assets 里的 vd-server.jar 分块 base64 写入 /data/local/tmp(shell/root 身份,保属主);
 *  2. pkill 旧实例 → `CLASSPATH=<jar> app_process / io.zer0.muse.vdserver.Main <hostPkg> &`;
 *  3. 轮询注册表等 binder 手递手(10s);服务端 15s 空闲自杀,后续调用自动重启。
 *
 * 权限门(v2.2.1 起双档):复用自动化通道 [AutomationManager.execTiered] —— Shizuku 优先、
 * Root 兜底。设备实证:shell uid 与 root(adb) 两种身份下,建屏/截图/屏内启动全链均通过。
 */
class VirtualDisplayServerManager(
    private val context: Context,
    private val shell: AutomationManager,
) {
    private val startupMutex = Mutex()

    /** 最近一次 ensure 成功的 displayId(工具跨调用复用)。 */
    @Volatile
    var lastDisplayId: Int = -1

    @Volatile
    private var handoffToken: String? = null

    /** 权限通道状态:返回 "ready(Shizuku)" / "ready(Root)" / 不可用原因。 */
    suspend fun channelState(): String {
        var state = shell.permissionState.value
        if (!state.shellEnabled && !state.rootEnabled) {
            state = shell.refreshPermissions()
        }
        return when {
            state.shellEnabled -> "ready(Shizuku)"
            state.rootEnabled -> "ready(Root)"
            else -> "需要 Shizuku 或 Root 权限通道(设置→权限向导授权)"
        }
    }

    /** 确保服务端在线,返回代理;失败携带可读原因。 */
    suspend fun ensureStarted(): Result<IVirtualDisplayService> = withContext(Dispatchers.IO) {
        existingLiveProxy()?.let { return@withContext Result.success(it) }

        startupMutex.withLock {
            existingLiveProxy()?.let { return@withLock Result.success(it) }

            var state = shell.permissionState.value
            if (!state.shellEnabled && !state.rootEnabled) {
                // 缓存可能过期(刚授权/撤销),探一次再决定
                state = shell.refreshPermissions()
            }
            if (!state.shellEnabled && !state.rootEnabled) {
                return@withLock Result.failure(
                    IllegalStateException("虚拟屏需要 Shizuku 或 Root 权限通道(设置→权限向导授权)"),
                )
            }
            if (!deployJar()) {
                return@withLock Result.failure(IllegalStateException("虚拟屏服务端部署失败(assets 缺失或写入被拒)"))
            }

            val token = UUID.randomUUID().toString()
            handoffToken = token
            VirtualDisplayBinderRegistry.setExpectedHandoffToken(token)
            VirtualDisplayBinderRegistry.update(null)
            exec("pkill -f ${VdContract.SERVER_KILL_PATTERN}")
            val launch = exec(launchCommand(token))
            if (launch.exitCode != 0) {
                Logger.w(TAG, "服务端启动命令退出码 ${launch.exitCode}: ${launch.output.take(200)}")
            }

            val proxy = awaitBinder()
                ?: run {
                    handoffToken = null
                    VirtualDisplayBinderRegistry.clearHandoffToken()
                    return@withLock Result.failure(
                        IllegalStateException(
                            "虚拟屏服务端未在 ${AWAIT_TIMEOUT_MS / 1000}s 内就绪" +
                                "(详见 /data/local/tmp/muse-vd-server.log)",
                        ),
                    )
                }
            Logger.i(TAG, "虚拟屏服务端已就绪")
            Result.success(proxy)
        }
    }

    /** 清除失效 binder 并返回当前可用代理(不触发启动)。 */
    fun existingLiveProxy(): IVirtualDisplayService? {
        val binder =
            VirtualDisplayBinderRegistry.current()
                ?: run {
                    clearCachedDisplayId()
                    return null
                }
        val proxy = IVirtualDisplayService.Stub.asInterface(binder)
        return try {
            if (proxy != null && proxy.isAlive) {
                proxy
            } else {
                VirtualDisplayBinderRegistry.update(null)
                clearCachedDisplayId()
                null
            }
        } catch (e: Exception) {
            Logger.w(TAG, "服务端探活失败: ${e.message}")
            VirtualDisplayBinderRegistry.update(null)
            clearCachedDisplayId()
            null
        }
    }

    private fun clearCachedDisplayId() {
        if (lastDisplayId >= 0) {
            lastDisplayId = -1
        }
        handoffToken = null
        VirtualDisplayBinderRegistry.clearHandoffToken()
    }

    /** 以当前可用档位(Shizuku 优先、Root 兜底)执行一条命令(部署/启动共用)。 */
    suspend fun exec(command: String): ShellExecutor.ExecDetail = shell.execTiered(command)?.second
        ?: ShellExecutor.ExecDetail(-1, "无可用的 shell 权限通道")

    // ── 内部 ────────────────────────────────────────────────────────────────

    private suspend fun deployJar(): Boolean {
        val bytes =
            runCatching {
                context.assets.open(ASSET_JAR_PATH).use { it.readBytes() }
            }.getOrElse {
                Logger.w(TAG, "内置 vd-server.jar 缺失: ${it.message}")
                return false
            }

        // 已部署且大小一致 → 跳过重复写入
        val stat = exec("stat -c %s ${VdContract.SERVER_JAR_PATH}")
        if (stat.exitCode == 0 && stat.output.trim() == bytes.size.toString()) {
            return true
        }

        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        var offset = 0
        var append = false
        while (offset < encoded.length) {
            val chunk = encoded.substring(offset, minOf(offset + DEPLOY_CHUNK_CHARS, encoded.length))
            val redirect = if (append) ">>" else ">"
            val result = exec("echo -n $chunk | base64 -d $redirect ${VdContract.SERVER_JAR_PATH}")
            if (result.exitCode != 0) {
                Logger.w(TAG, "jar 分块写入失败: ${result.output.take(200)}")
                return false
            }
            append = true
            offset += DEPLOY_CHUNK_CHARS
        }
        val verify = exec("stat -c %s ${VdContract.SERVER_JAR_PATH}")
        val ok = verify.exitCode == 0 && verify.output.trim() == bytes.size.toString()
        if (!ok) {
            Logger.w(TAG, "jar 写入校验失败: ${verify.output.trim()} != ${bytes.size}")
        }
        return ok
    }

    private fun launchCommand(token: String): String {
        val host = context.packageName
        return "CLASSPATH=${VdContract.SERVER_JAR_PATH} app_process / ${VdContract.SERVER_MAIN_CLASS} " +
            "$host $token > /data/local/tmp/muse-vd-server.log 2>&1 &"
    }

    private suspend fun awaitBinder(): IVirtualDisplayService? {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            existingLiveProxy()?.let { return it }
            delay(AWAIT_POLL_MS)
        }
        return null
    }

    companion object {
        private const val TAG = "VdServerManager"

        /** 内置服务端 jar 在 assets 中的路径(构建期由 syncVdServerJar 拷贝)。 */
        private const val ASSET_JAR_PATH = "vd/vd-server.jar"

        private const val AWAIT_TIMEOUT_MS = 10_000L
        private const val AWAIT_POLL_MS = 100L
        private const val DEPLOY_CHUNK_CHARS = 60_000
    }
}
