package io.zer0.muse.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.space.MemorySpaceRepository
import io.zer0.memory.space.MemorySpaceWithCount
import io.zer0.memory.space.MemorySpaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.0.52 P2-2: 记忆空间管理 ViewModel。
 *
 * 职责:
 *  - Space 列表展示(含事实数量统计)
 *  - 创建/重命名/删除 Space
 *  - 调整 Space 排序
 *
 * 与 [io.zer0.muse.ui.MemoryViewModel] 的分工:
 *  - 本 ViewModel 负责 Space 元数据 CRUD
 *  - MemoryViewModel 负责按当前选中 Space 过滤事实列表
 *  - 两者通过 SettingsRepository.currentSpaceId 共享当前 Space(由 app 层注入)
 */
class MemorySpaceViewModel(
    application: Application,
    private val spaceRepository: MemorySpaceRepository,
) : AndroidViewModel(application) {

    /**
     * Space 列表(含事实数量),响应式更新。
     * UI 通过此 Flow 自动刷新列表。
     */
    val spacesWithCount: StateFlow<List<MemorySpaceWithCount>> =
        spaceRepository.observeSpacesWithCount()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Space 列表(轻量,不含事实数量)。
     * 用于切换器下拉,避免每次都做 LEFT JOIN COUNT。
     */
    val spaces: StateFlow<List<MemorySpaceEntity>> =
        spaceRepository.observeSpaces()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 当前操作状态文案(创建/删除/重命名结果反馈)。 */
    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    /** 创建新 Space。返回新建 id(失败返回 null)。 */
    fun createSpace(name: String, icon: String? = null, description: String = "") {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                resultOf { spaceRepository.createSpace(name, icon, description) }
                    .onError { msg, t ->
                        _operationMessage.value = "创建 Space 失败: $msg"
                        Logger.w("MemorySpaceViewModel", "createSpace 失败: $msg", t)
                    }
                    .getOrNull()
            }
            if (id != null) {
                _operationMessage.value = "已创建空间: $name"
            }
        }
    }

    /** 重命名 Space。 */
    fun renameSpace(id: String, newName: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                resultOf { spaceRepository.renameSpace(id, newName) }
                    .onError { msg, t ->
                        _operationMessage.value = "重命名失败: $msg"
                        Logger.w("MemorySpaceViewModel", "renameSpace 失败: $msg", t)
                    }
                    .getOrNull() ?: false
            }
            if (ok) _operationMessage.value = "已重命名"
        }
    }

    /** 更新 Space 描述。 */
    fun updateDescription(id: String, description: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { spaceRepository.updateDescription(id, description) }
                    .onError { msg, t -> Logger.w("MemorySpaceViewModel", "updateDescription 失败: $msg", t) }
            }
        }
    }

    /** 更新 Space 图标。 */
    fun updateIcon(id: String, icon: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { spaceRepository.updateIcon(id, icon) }
                    .onError { msg, t -> Logger.w("MemorySpaceViewModel", "updateIcon 失败: $msg", t) }
            }
        }
    }

    /**
     * 删除 Space。
     * 默认 Space 不可删除,会返回失败提示。
     * 删除前会将其中的事实迁移到默认 Space,避免数据丢失。
     */
    fun deleteSpace(id: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                resultOf { spaceRepository.deleteSpace(id) }
                    .onError { msg, t ->
                        _operationMessage.value = "删除失败: $msg"
                        Logger.w("MemorySpaceViewModel", "deleteSpace 失败: $msg", t)
                    }
                    .getOrNull() ?: false
            }
            _operationMessage.value = if (ok) "已删除空间,事实已迁回默认空间" else "默认空间不可删除"
        }
    }

    /** 调整 Space 排序。 */
    fun reorderSpaces(orderedIds: List<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { spaceRepository.reorderSpaces(orderedIds) }
                    .onError { msg, t -> Logger.w("MemorySpaceViewModel", "reorderSpaces 失败: $msg", t) }
            }
        }
    }

    /** 清空操作消息(用户已看到提示后由 UI 调用)。 */
    fun clearOperationMessage() {
        _operationMessage.value = null
    }
}
