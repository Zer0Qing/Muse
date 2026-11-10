package io.zer0.muse.asr.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地模型可用性的唯一查询入口 + Compose 可订阅信号。
 *
 * 两件事分开，各司其职：
 *  - [isReady] 永远现场读文件系统（[LocalModelStore.isReady] 是唯一判据），
 *    给「现在能不能识别」这类一次性判断用 —— 缓存值在文件被外部删除时会骗人。
 *  - [readyFlow] 只是给 Compose 的重组信号：文件系统状态变化点（下载完成/失败/取消、
 *    删除、导入、应用启动）主动 [refresh] 一次，输入栏据此在「发送键 ↔ 语音键」间切换。
 *
 * 之所以需要信号而不是让 UI 每次组合都读文件：读盘是系统调用，滚动/输入时每次重组
 * 都碰一遍没必要；而状态变化点少且明确，推一次就够。
 */
internal object LocalModelStoreBridge {

    @Volatile
    private var appContext: Context? = null

    private val _readyFlow = MutableStateFlow(false)

    /** 本地模型是否可用（输入栏换键的信号源）。 */
    val readyFlow: StateFlow<Boolean> = _readyFlow.asStateFlow()

    /** Application onCreate 时注入一次，并立刻对齐一次文件系统事实。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    /** 现场读文件系统。未注入 Context 时按不可用处理。 */
    fun isReady(): Boolean {
        val ctx = appContext ?: return false
        return LocalModelStore.isReady(ctx)
    }

    /** 文件系统发生可改变可用性的事件后调用，把事实推给 [readyFlow]。 */
    fun refresh() {
        _readyFlow.value = isReady()
    }

    /** 供本地整段转写取 Context；未初始化返回 null（调用方按不可用处理）。 */
    fun contextOrNull(): Context? = appContext
}
