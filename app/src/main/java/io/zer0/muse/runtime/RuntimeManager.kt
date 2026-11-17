package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 运行环境的唯一入口。
 *
 * 上层（设置页、安装卡、工具层）都只跟它打交道，好处是：
 *  - 同一时刻只有一次安装在跑（[installMutex]），重复点安装不会下两遍；
 *  - 状态只有一份（[stateFlow]），界面与工具层不会各说各话；
 *  - 安装失败不需要调用方处理回滚，[RuntimeInstaller] 已经把目录清干净。
 */
class RuntimeManager(
    private val context: Context,
    private val store: RuntimeStore = RuntimeStore(context),
    private val installer: RuntimeInstaller,
    private val manifestSource: RuntimeManifestSource = RuntimeManifestSource(context),
) {

    private val installMutex = Mutex()

    private val _progress = MutableStateFlow<RuntimeInstaller.Progress?>(null)

    /** 正在进行的安装进度；null 表示没有安装任务。 */
    val progress: StateFlow<RuntimeInstaller.Progress?> = _progress.asStateFlow()

    /** 已安装状态（由指针推导，不缓存，避免界面与磁盘不一致）。 */
    fun state(id: RuntimeId = RuntimeId.UBUNTU_NODE): RuntimeState = store.state(id)

    /** 当前 ABI 的清单（远程优先、内置兜底）；没有对应包时返回 null。 */
    suspend fun manifest(id: RuntimeId = RuntimeId.UBUNTU_NODE): RuntimeManifest? = manifestSource.load(id)

    /**
     * 安装（幂等）。返回 Result 里的错误消息可直接展示给用户。
     */
    suspend fun install(id: RuntimeId = RuntimeId.UBUNTU_NODE): Result<Unit> = installMutex.withLock {
        val manifest = manifestSource.load(id)
            ?: return@withLock Result.failure(IllegalStateException("这个设备架构没有对应的运行环境包"))
        Logger.i(TAG, "开始安装 ${id.manifestKey} ${manifest.revision}（${manifest.archive.sizeBytes / 1024 / 1024} MB）")
        RuntimeInstallStatus.begin()
        // 卡片与通知都要知道「正在装哪一版」，清单拿到即上报，不等安装结束。
        RuntimeInstallStatus.setRevision(manifest.revision)
        val result = try {
            installer.install(id, manifest) { progress ->
                _progress.value = progress
                RuntimeInstallStatus.updateProgress(progress)
            }
        } finally {
            _progress.value = null
            RuntimeInstallStatus.updateProgress(null)
            RuntimeInstallStatus.refresh(context)
        }
        result.onFailure { RuntimeInstallStatus.fail(it.message) }
        result
    }

    /**
     * 交给前台服务在后台安装（界面与工具层的推荐入口）。
     *
     * 不直接在当前协程里下 76MB：用户可能随时退出应用，前台服务能把它跑完。
     */
    fun startInstall(id: RuntimeId = RuntimeId.UBUNTU_NODE) {
        RuntimeInstallService.start(context, id)
    }

    /** 卸载并释放空间。 */
    suspend fun uninstall(id: RuntimeId = RuntimeId.UBUNTU_NODE) {
        store.uninstall(id)
        Logger.i(TAG, "已卸载 ${id.manifestKey}")
    }

    /** 已安装时占用的字节数（用于设置页展示与空间统计）。 */
    fun installedBytes(id: RuntimeId = RuntimeId.UBUNTU_NODE): Long =
        (store.state(id) as? RuntimeState.Ready)?.installedBytes ?: 0L

    private companion object {
        const val TAG = "RuntimeManager"
    }
}