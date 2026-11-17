package io.zer0.muse.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行环境安装状态的进程内单一事实源。
 *
 * 前台服务负责真正下载，UI 只读这里的状态；两者不共享同一个 RuntimeManager 实例，
 * 但共享这一组 Flow，所以卡片不会出现「服务在装、界面显示未安装」。
 * 磁盘上的最终事实仍以 [RuntimeStore] 的 current.json 为准。
 */
object RuntimeInstallStatus {

    private val _progress = MutableStateFlow<RuntimeInstaller.Progress?>(null)

    /** 当前安装进度；null 表示没有安装任务在跑。 */
    val progress: StateFlow<RuntimeInstaller.Progress?> = _progress.asStateFlow()

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotInstalled)

    /** 最近一次刷新得到的安装状态。 */
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** 最近一次安装失败原因；新一次安装开始时清空。 */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _revision = MutableStateFlow("")

    /** 当前（或本次要安装的）包版本；安装卡落库时要记下它。 */
    val revision: StateFlow<String> = _revision.asStateFlow()

    fun refresh(context: Context) {
        val current = RuntimeStore(context).state(RuntimeId.UBUNTU_NODE)
        _state.value = current
        // 已就绪时以磁盘指针为准：指针才是"这台机器现在装的是哪一版"的真源。
        (current as? RuntimeState.Ready)?.revision?.let { _revision.value = it }
    }

    fun setRevision(revision: String) {
        if (revision.isNotBlank()) _revision.value = revision
    }

    fun updateProgress(progress: RuntimeInstaller.Progress?) {
        _progress.value = progress
    }

    fun begin() {
        _lastError.value = null
    }

    fun fail(message: String?) {
        _lastError.value = message
    }
}
