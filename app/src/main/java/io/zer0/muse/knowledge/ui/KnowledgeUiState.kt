package io.zer0.muse.knowledge.ui

import io.zer0.muse.data.knowledge.KnowledgeJobItemEntity
import io.zer0.muse.knowledge.domain.KnowledgeJobStage
import io.zer0.muse.knowledge.domain.KnowledgeJobState

/**
 * R8 管理界面状态 — 计划 R8「首页只显示目录与任务摘要；处理页列出逐项状态、
 * 部分成功和修复动作，取消有终态」「导入状态和能力不混淆」。
 *
 * 这是**纯数据模型 + 映射**,不依赖 Compose,可在 JVM 单测验证;
 * Compose 页面只负责渲染与提交用户意图,不直接读 DAO、不解析文件。
 */
data class KnowledgeJobUiItem(
    val itemId: String,
    val docId: String,
    val stateLabel: String,
    val stageLabel: String,
    /** 用户可理解的可用性文案(不暴露 topK/向量维度等内部概念)。 */
    val availabilityLabel: String,
    val canCancel: Boolean,
    val hasError: Boolean,
    val errorText: String,
)

data class KnowledgeJobsUiState(
    val jobId: String,
    val items: List<KnowledgeJobUiItem>,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val cancelled: Int,
    val running: Int,
    val allTerminal: Boolean,
) {
    val summary: String
        get() = when {
            total == 0 -> "没有可处理的文件"
            running > 0 -> "处理中 $succeeded/$total"
            failed > 0 && succeeded > 0 -> "部分成功:成功 $succeeded,失败 $failed"
            failed > 0 -> "全部失败:共 $failed"
            cancelled > 0 && succeeded == 0 -> "已取消"
            else -> "处理完成:共 $succeeded"
        }
}

object KnowledgeJobUiMapper {

    /** 用户可理解的状态标签 —— 导入状态与能力状态分开,不混淆。 */
    fun stateLabel(state: KnowledgeJobState): String = when (state) {
        KnowledgeJobState.QUEUED -> "排队中"
        KnowledgeJobState.RUNNING -> "处理中"
        KnowledgeJobState.WAITING_FOR_INPUT -> "等待确认"
        KnowledgeJobState.SUCCEEDED -> "已完成"
        KnowledgeJobState.SUCCEEDED_WITH_WARNINGS -> "完成(有提醒)"
        KnowledgeJobState.FAILED -> "失败"
        KnowledgeJobState.CANCEL_REQUESTED -> "正在取消"
        KnowledgeJobState.CANCELLED -> "已取消"
    }

    fun stageLabel(stage: KnowledgeJobStage): String = when (stage) {
        KnowledgeJobStage.ACQUIRE -> "读取原件"
        KnowledgeJobStage.PARSE -> "解析正文"
        KnowledgeJobStage.OCR -> "文字识别"
        KnowledgeJobStage.CHUNK -> "分块"
        KnowledgeJobStage.LEXICAL -> "关键词索引"
        KnowledgeJobStage.EMBED -> "语义索引"
        KnowledgeJobStage.VALIDATE -> "校验"
        KnowledgeJobStage.PUBLISH -> "发布"
        KnowledgeJobStage.CLEANUP -> "收尾"
    }

    fun availabilityLabel(item: KnowledgeJobItemEntity): String = when (item.state) {
        KnowledgeJobState.SUCCEEDED.name -> "可检索"
        KnowledgeJobState.SUCCEEDED_WITH_WARNINGS.name -> "可检索(部分内容受限)"
        KnowledgeJobState.FAILED.name, KnowledgeJobState.CANCELLED.name -> "未完成"
        else -> "处理中"
    }

    fun toUiItem(item: KnowledgeJobItemEntity): KnowledgeJobUiItem {
        val state = runCatching { KnowledgeJobState.valueOf(item.state) }.getOrElse { KnowledgeJobState.FAILED }
        val stage = runCatching { KnowledgeJobStage.valueOf(item.stage) }.getOrElse { KnowledgeJobStage.ACQUIRE }
        return KnowledgeJobUiItem(
            itemId = item.jobItemId,
            docId = item.docId,
            stateLabel = stateLabel(state),
            stageLabel = stageLabel(stage),
            availabilityLabel = availabilityLabel(item),
            canCancel = !state.isTerminal && state != KnowledgeJobState.CANCEL_REQUESTED,
            hasError = state == KnowledgeJobState.FAILED,
            errorText = item.resultReason,
        )
    }

    fun toUiState(jobId: String, items: List<KnowledgeJobItemEntity>): KnowledgeJobsUiState {
        val states = items.map { runCatching { KnowledgeJobState.valueOf(it.state) }.getOrElse { KnowledgeJobState.FAILED } }
        return KnowledgeJobsUiState(
            jobId = jobId,
            items = items.map { toUiItem(it) },
            total = items.size,
            succeeded = states.count { it == KnowledgeJobState.SUCCEEDED || it == KnowledgeJobState.SUCCEEDED_WITH_WARNINGS },
            failed = states.count { it == KnowledgeJobState.FAILED },
            cancelled = states.count { it == KnowledgeJobState.CANCELLED },
            running = states.count { it == KnowledgeJobState.RUNNING || it == KnowledgeJobState.QUEUED },
            allTerminal = states.all { it.isTerminal },
        )
    }
}

/**
 * R8 设置页字段模型 — 计划 R8「设置拆成摄取、编码与查询高级项；每字段标明继承来源和修改影响」。
 *
 * 只做展示数据,不直接写配置;每行带 sourceLabel,让用户看到当前值是继承还是显式覆盖。
 */
data class KnowledgePolicyFieldUi(
    val label: String,
    val value: String,
    val sourceLabel: String,
    val impact: String,
)

data class KnowledgeSettingsUiState(
    val ingestFields: List<KnowledgePolicyFieldUi>,
    val embeddingFields: List<KnowledgePolicyFieldUi>,
    val retrievalFields: List<KnowledgePolicyFieldUi>,
)

object KnowledgeSettingsUiMapper {

    fun fromPolicy(policy: io.zer0.muse.knowledge.domain.ResolvedPolicy): KnowledgeSettingsUiState =
        KnowledgeSettingsUiState(
            ingestFields = listOf(
                field("分块大小", policy.ingest.chunkSize.toString(), policy.sourceOf("ingest.chunkSize"), "影响新导入内容,旧内容需重处理"),
                field("分块重叠", policy.ingest.chunkOverlap.toString(), policy.sourceOf("ingest.chunkOverlap"), "影响新导入内容"),
                field("解析器", policy.ingest.parserType, io.zer0.muse.knowledge.domain.PolicyFieldSource.GLOBAL_DEFAULT, "影响新导入内容"),
            ),
            embeddingFields = listOf(
                field("编码来源", policy.embedding.source, policy.sourceOf("embedding.model"), "变更需新建索引空间"),
                field("模型", policy.embedding.model, policy.sourceOf("embedding.model"), "变更需重新编码"),
                field(
                    "已验证维度",
                    policy.embedding.verifiedDimension?.toString() ?: "未验证",
                    io.zer0.muse.knowledge.domain.PolicyFieldSource.GLOBAL_DEFAULT,
                    "未验证维度不可发布",
                ),
            ),
            retrievalFields = listOf(
                field("候选数 topK", policy.retrieval.topK.toString(), policy.sourceOf("retrieval.topK"), "只影响查询,不触发重编码"),
                field("阈值", policy.retrieval.threshold.toString(), policy.sourceOf("retrieval.threshold"), "只影响查询"),
                field(
                    "混合检索",
                    if (policy.retrieval.hybridEnabled) "开" else "关",
                    io.zer0.muse.knowledge.domain.PolicyFieldSource.GLOBAL_DEFAULT,
                    "只影响查询",
                ),
            ),
        )

    private fun field(
        label: String,
        value: String,
        source: io.zer0.muse.knowledge.domain.PolicyFieldSource,
        impact: String,
    ): KnowledgePolicyFieldUi = KnowledgePolicyFieldUi(
        label = label,
        value = value,
        sourceLabel = when (source) {
            io.zer0.muse.knowledge.domain.PolicyFieldSource.GLOBAL_DEFAULT -> "全局默认"
            io.zer0.muse.knowledge.domain.PolicyFieldSource.KB_OVERRIDE -> "知识库覆盖"
            io.zer0.muse.knowledge.domain.PolicyFieldSource.ASSISTANT_OVERRIDE -> "助手覆盖"
            io.zer0.muse.knowledge.domain.PolicyFieldSource.SESSION_OVERRIDE -> "会话覆盖"
            io.zer0.muse.knowledge.domain.PolicyFieldSource.EXPLICIT_REQUEST -> "本轮指定"
            io.zer0.muse.knowledge.domain.PolicyFieldSource.BUILT_IN -> "内置默认"
        },
        impact = impact,
    )
}
