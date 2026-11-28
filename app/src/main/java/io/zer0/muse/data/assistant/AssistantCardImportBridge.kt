package io.zer0.muse.data.assistant

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v2.x: 外部角色包导入桥。
 *
 * 从 VIEW Intent 收到的 .muse-assistant URI(应用外点开角色包文件)经此传递到
 * 助手页,由导入预览对话框消费后清空。
 *
 * 单进程单 Activity 架构下用 [StateFlow]:无论"收到时不在助手页"还是
 * "已在助手页再收到"两种时序,助手页的收集端都能消费到。
 */
object AssistantCardImportBridge {

    private val _pendingUri = MutableStateFlow<Uri?>(null)

    /** 待导入的角色包 URI(null = 无待处理)。 */
    val pendingUri: StateFlow<Uri?> = _pendingUri

    /** 记录待导入 URI(由 MainActivity 的 Intent 处理调用)。 */
    fun offer(uri: Uri) {
        _pendingUri.value = uri
    }

    /** 消费并清空(由 AssistantScreen 调用)。 */
    fun consume() {
        _pendingUri.value = null
    }
}
