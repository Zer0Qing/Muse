package io.zer0.muse.data.promptinjection

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import io.zer0.muse.R

/**
 * PromptInjection 仓库 — Phase 8.5。
 *
 * 模式注入: 用户在会话中切换"模式"(creative/coding/roleplay/...)时,
 * 对应 mode 的注入条目立即生效,无需关键词触发。
 */
class PromptInjectionRepository(
    private val dao: PromptInjectionDao,
    private val context: Context,
) {
    // L-PID5: 加 distinctUntilChanged,避免上游无意义重复发射触发 UI 重绘。
    fun observeAll(): Flow<List<PromptInjectionEntity>> = dao.observeAll().distinctUntilChanged()

    suspend fun getEnabledByMode(mode: String): List<PromptInjectionEntity> =
        dao.getEnabledByMode(mode)

    suspend fun getByIdsEnabled(ids: List<String>): List<PromptInjectionEntity> =
        if (ids.isEmpty()) emptyList() else dao.getByIdsEnabled(ids)

    suspend fun getById(id: String): PromptInjectionEntity? = dao.getById(id)

    /**
     * 新增或更新。L-PID7: 校正 insertionPosition 为合法值,非法值回落到默认 after_system
     * (PromptInjectionTransformer 仅支持 before_system/after_system,不支持 before_last)。
     */
    suspend fun upsert(entity: PromptInjectionEntity) {
        val normalized = if (entity.insertionPosition in PromptInjectionEntity.VALID_INSERTION_POSITIONS) {
            entity
        } else {
            entity.copy(insertionPosition = PromptInjectionEntity.INSERTION_AFTER_SYSTEM)
        }
        dao.upsert(normalized)
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    /** 预置模式列表(本地化),供 UI 选择器使用。mode 允许自定义,不强校验。 */
    val presetModes: List<Pair<String, String>> = listOf(
        "default" to context.getString(R.string.prompt_injection_mode_default),
        "creative" to context.getString(R.string.prompt_injection_mode_creative),
        "coding" to context.getString(R.string.prompt_injection_mode_coding),
        "roleplay" to context.getString(R.string.prompt_injection_mode_roleplay),
        "translation" to context.getString(R.string.prompt_injection_mode_translation),
        "analysis" to context.getString(R.string.prompt_injection_mode_analysis),
    )
}
