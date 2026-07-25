package io.zer0.muse.data.skill

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Phase 8.8: Skill 实体(Kotlin 直实现,不用 QuickJS)。
 *
 * Skill 是预定义的 Kotlin 工具函数,封装常用操作(读写文件 / HTTP 请求等)。
 * 每个 Skill 对外暴露为一个 LLM 可调用的工具(参数 JSON Schema + 执行函数)。
 *
 * 与同类方案的差异:同类方案用 QuickJS 执行用户自定义脚本(S2 已 skip),
 * 本项目改为预定义 Kotlin skill 模板 + 参数化执行,保留扩展点(后续可加更多 skill)。
 *
 * @param id 主键(通常用 slug,如 "read_file")
 * @param name 显示名
 * @param description 工具描述(LLM 据此决定是否调用)
 * @param parametersJson 参数 JSON Schema(OpenAI 兼容格式)
 * @param requiredJson 必填参数名数组(JSON 字符串)
 * @param implementationKotlin 实现 key(用于 SkillExecutor 路由,如 "read_file")
 * @param enabled 是否启用
 * @param category 分类:file / http / system / custom
 * @param createdAt 创建时间戳
 * @param updatedAt 更新时间戳
 */
@Serializable
@Entity(
    tableName = "skills",
    // M-SD1: 为 enabled / category 加索引,提升 SkillRepository.listEnabled
    // 和按 category 筛选的查询性能。索引与迁移由主代理统一在 MuseDb 中处理。
    indices = [Index(value = ["enabled"]), Index(value = ["category"])],
)
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    @ColumnInfo(defaultValue = "{}") val parametersJson: String = "{}",
    @ColumnInfo(defaultValue = "[]") val requiredJson: String = "[]",
    // L-SET1: 默认值用 "unknown" 占位,便于在 SkillExecutor 路由时识别未设置实现的情况
    @ColumnInfo(name = "implementationKotlin", defaultValue = "") val implementationKotlin: String = "unknown",
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "custom") val category: String = "custom",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
