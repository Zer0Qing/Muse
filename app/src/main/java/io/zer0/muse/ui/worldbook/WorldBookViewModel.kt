package io.zer0.muse.ui.worldbook
import android.content.Context
import io.zer0.muse.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.Logger
import io.zer0.muse.worldbook.WorldBookEntryEntity
import io.zer0.muse.worldbook.WorldBookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * P1-2: Worldbook 管理 UI 状态。
 */
data class WorldBookUiState(
    val entries: List<WorldBookEntryEntity> = emptyList(),
    val importMessage: String? = null,
)

/**
 * P1-2: Worldbook 管理 ViewModel。
 *
 * 负责条目的 CRUD + SillyTavern World Info JSON 导入导出。
 * 列表通过 [WorldBookRepository.observeAll] 暴露为 StateFlow,UI 直接收集。
 */
class WorldBookViewModel(
    private val repository: WorldBookRepository,
    private val context: Context,
) : ViewModel() {

    private val _importMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<WorldBookUiState> = combine(repository.observeAll(), _importMessage) { entries, msg ->
        WorldBookUiState(entries = entries, importMessage = msg)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WorldBookUiState(),
    )

    fun save(entry: WorldBookEntryEntity) {
        viewModelScope.launch {
            runCatching {
                repository.upsert(entry.copy(updatedAt = System.currentTimeMillis()))
            }.onFailure { Logger.w(TAG, "save 失败: ${it.message}", it) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onFailure { Logger.w(TAG, "delete 失败: ${it.message}", it) }
        }
    }

    fun importSillyTavern(jsonText: String) {
        viewModelScope.launch {
            val count = runCatching { repository.importSillyTavernJson(jsonText) }
                .getOrElse { e ->
                    Logger.w(TAG, "importSillyTavern 失败: ${e.message}", e)
                    _importMessage.value = context.getString(R.string.worldbook_import_failed, e.message ?: "")
                    return@launch
                }
            _importMessage.value = if (count > 0) context.getString(R.string.worldbook_import_success, count) else context.getString(R.string.worldbook_import_empty)
        }
    }

    fun exportSillyTavern(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val json = runCatching { repository.exportSillyTavernJson() }
                .getOrElse { e ->
                    Logger.w(TAG, "exportSillyTavern 失败: ${e.message}", e)
                    _importMessage.value = context.getString(R.string.worldbook_export_failed, e.message ?: "")
                    return@launch
                }
            onResult(json)
        }
    }

    fun clearImportMessage() {
        _importMessage.value = null
    }

    companion object {
        private const val TAG = "WorldBookViewModel"
    }
}
