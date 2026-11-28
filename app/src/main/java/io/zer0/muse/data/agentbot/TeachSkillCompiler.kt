package io.zer0.muse.data.agentbot

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.tools.SkillImporter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 把一次已经结束的示范录制提炼成可复用的提示词技能。
 *
 * 录制轨迹本身只保留脱敏后的动作描述，因此这里不再尝试还原输入内容；
 * 模型只负责命名、概述与指出需要人工确认的步骤编号，步骤正文始终使用录制原样，
 * 避免模型改写用户真正演示过的路径。
 */
class TeachSkillCompiler(
    private val refiner: TeachSkillRefiner? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val idFactory: (String) -> String = ::defaultId,
) {

    /**
     * 提炼后的结构化结果。
     *
     * [skill] 可直接写入技能库；[steps] 保留风险判定，供界面或后续编排读取。
     */
    data class CompiledSkill(
        val skill: SkillEntity,
        val title: String,
        val summary: String,
        val executionPrompt: String,
        val steps: List<CompiledStep>,
        val confirmationPoints: List<ConfirmationPoint>,
    )

    data class CompiledStep(
        val index: Int,
        val action: String,
        val targetApp: String,
        val riskReason: String? = null,
        val targetPackage: String = "",
        val targetText: String = "",
        val contentDesc: String = "",
        val resourceIdHint: String = "",
        val actionKind: String = "",
    ) {
        val requiresConfirmation: Boolean get() = riskReason != null
    }

    data class ConfirmationPoint(
        val stepIndex: Int,
        val action: String,
        val reason: String,
    )

    /** 只返回技能实体，便于调用方直接入库。 */
    suspend fun compile(draft: AgentTeachRecorder.FinishedDraft): SkillEntity = compileDetailed(draft).skill

    /**
     * 执行完整提炼。模型不可用、超时或返回非 JSON 时自动退回规则编译，
     * 不把录制结果卡在“等待模型”状态。
     */
    suspend fun compileDetailed(draft: AgentTeachRecorder.FinishedDraft): CompiledSkill {
        val recordedSteps = extractSteps(draft)
        val fallbackTitle = draft.title.trim().ifBlank { defaultTitle(recordedSteps) }
        val refinement = refineWithModel(draft, recordedSteps)
        val title = refinement?.name?.trim()?.take(MAX_TITLE_LENGTH)?.ifBlank { fallbackTitle } ?: fallbackTitle
        val summary = refinement?.description?.trim()?.take(MAX_SUMMARY_LENGTH)
            ?.ifBlank { defaultSummary(recordedSteps.size) }
            ?: defaultSummary(recordedSteps.size)

        val modelConfirmIndexes = refinement?.confirmStepNumbers.orEmpty().toSet()
        val compiledSteps = recordedSteps.mapIndexed { position, step ->
            val index = position + 1
            val reason = classifyRisk(step.action, step.targetApp)
                ?: if (index in modelConfirmIndexes) MODEL_RISK_REASON else null
            CompiledStep(
                index = index,
                action = step.action,
                targetApp = step.targetApp,
                riskReason = reason,
                targetPackage = step.targetPackage,
                targetText = step.targetText,
                contentDesc = step.contentDesc,
                resourceIdHint = step.resourceIdHint,
                actionKind = step.actionKind,
            )
        }
        val confirmationPoints = compiledSteps.mapNotNull { step ->
            step.riskReason?.let { reason ->
                ConfirmationPoint(stepIndex = step.index, action = step.action, reason = reason)
            }
        }
        val promptText = buildPromptText(
            title = title,
            steps = compiledSteps,
            confirmationPoints = confirmationPoints,
        )
        val storedPromptText = promptText.take(SkillImporter.MAX_PROMPT_CHARS)
        val timestamp = now()
        val skill = SkillEntity(
            id = idFactory(title),
            name = title,
            description = summary,
            parametersJson = SkillImporter.EMPTY_PARAMETERS_JSON,
            requiredJson = "[]",
            implementationKotlin = SkillImporter.encodePromptText(storedPromptText),
            enabled = true,
            category = "user",
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        return CompiledSkill(
            skill = skill,
            title = title,
            summary = summary,
            executionPrompt = storedPromptText,
            steps = compiledSteps,
            confirmationPoints = confirmationPoints,
        )
    }

    private suspend fun refineWithModel(draft: AgentTeachRecorder.FinishedDraft, steps: List<RawStep>): ModelRefinement? {
        val provider = refiner ?: return null
        val request = buildRefinementRequest(draft, steps)
        return try {
            val raw = provider.refine(request)
            parseRefinement(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Logger.w(TAG, "示范技能提炼模型调用失败，使用规则编译: ${e.message}", e)
            null
        }
    }

    private fun buildRefinementRequest(draft: AgentTeachRecorder.FinishedDraft, steps: List<RawStep>): String = buildString {
        appendLine("你是技能编译器。下面是一次已脱敏的手机操作示范，请提炼成可复用技能。")
        appendLine("只输出一个 JSON 对象，不要 markdown，不要解释。格式：")
        appendLine("""{"name":"不超过20字的技能名","description":"一句话说明用途和触发场景","confirmStepNumbers":[需要人工确认的步骤编号]}""")
        appendLine("步骤编号从 1 开始。只有不可逆、涉及资金/账户/隐私、会对外发送内容或修改系统设置的步骤才列入 confirmStepNumbers。")
        appendLine("不要改写步骤内容，不要新增步骤。")
        appendLine("录制标题：${draft.title}")
        appendLine("录制摘要：${draft.stepsSummary}")
        appendLine("步骤：")
        steps.forEach { step ->
            val target = if (step.targetApp.isBlank()) "" else "（${step.targetApp}）"
            appendLine("${step.index}. $target${step.action}")
        }
    }

    private fun parseRefinement(raw: String): ModelRefinement? {
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null
        return runCatching {
            AppJson.decodeFromString(
                ModelRefinement.serializer(),
                raw.substring(jsonStart, jsonEnd + 1),
            )
        }.onFailure { error ->
            Logger.w(TAG, "示范技能提炼返回无法解析: ${error.message}")
        }.getOrNull()
    }

    private fun extractSteps(draft: AgentTeachRecorder.FinishedDraft): List<RawStep> {
        val promptLines = draft.goalPrompt.lineSequence()
            .map(String::trim)
            .filter { it.matches(STEP_LINE_REGEX) }
            .mapNotNull { line ->
                val withoutIndex = line.substringAfter('.', "").trim()
                if (withoutIndex.isBlank()) null else parseStepTarget(withoutIndex)
            }
            .mapIndexed { index, step -> step.copy(index = index + 1) }
            .toList()
        if (promptLines.isNotEmpty()) return promptLines
        return draft.stepsSummary.split(" → ", "→")
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(::parseStepTarget)
            .mapIndexed { index, step -> step.copy(index = index + 1) }
    }

    private fun parseStepTarget(text: String): RawStep {
        val metaMatch = META_SUFFIX_REGEX.find(text)
        val body = if (metaMatch != null) text.removeRange(metaMatch.range).trim() else text
        val metaPairs = metaMatch?.groupValues?.getOrNull(1)
            ?.split(";")
            ?.mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0) null else entry.substring(0, idx).trim() to entry.substring(idx + 1).trim()
            }
            ?.toMap()
            .orEmpty()
        val target = TARGET_PREFIX.matchEntire(body) ?: BRACKET_TARGET_PREFIX.matchEntire(body)
        val (actionText, appText) = if (target != null) {
            target.groupValues[2].trim() to target.groupValues[1].trim()
        } else {
            body to ""
        }
        return RawStep(
            index = 0,
            action = actionText,
            targetApp = appText,
            targetPackage = metaPairs["pkg"].orEmpty(),
            targetText = metaPairs["text"].orEmpty(),
            contentDesc = metaPairs["desc"].orEmpty(),
            resourceIdHint = metaPairs["id"].orEmpty(),
            actionKind = metaPairs["kind"].orEmpty(),
        )
    }

    private fun buildPromptText(title: String, steps: List<CompiledStep>, confirmationPoints: List<ConfirmationPoint>): String =
        buildString {
            appendLine("执行技能「$title」时，严格按下面录制路径操作，不擅自跳步或改道：")
            steps.forEach { step ->
                val target = if (step.targetApp.isBlank()) "" else "（${step.targetApp}）"
                val hints = buildList {
                    if (step.targetPackage.isNotBlank()) add("包名=${step.targetPackage}")
                    if (step.actionKind.isNotBlank()) add("动作=${step.actionKind}")
                    if (step.targetText.isNotBlank()) add("文本=${step.targetText}")
                    if (step.contentDesc.isNotBlank()) add("描述=${step.contentDesc}")
                    if (step.resourceIdHint.isNotBlank()) add("控件=${step.resourceIdHint}")
                }
                val hintSuffix = if (hints.isEmpty()) "" else " 【控件线索: ${hints.joinToString("，")}】"
                appendLine("${step.index}. $target${step.action}$hintSuffix")
            }
            if (confirmationPoints.isNotEmpty()) {
                appendLine()
                appendLine("人工确认点：")
                confirmationPoints.forEach { point ->
                    appendLine("- 第 ${point.stepIndex} 步「${point.action}」执行前必须暂停，向用户说明风险（${point.reason}），得到明确确认后才能继续。")
                }
            }
            appendLine()
            append("遇到登录、验证码、支付、权限授权或任何未在录制中出现的页面变化时，暂停并让用户接管。")
        }

    companion object {
        private const val TAG = "TeachSkillCompiler"
        private const val MAX_TITLE_LENGTH = 40
        private const val MAX_SUMMARY_LENGTH = 160
        private const val MODEL_RISK_REASON = "模型判定该步骤涉及不可逆或对外生效操作"

        private val STEP_LINE_REGEX = Regex("""^\d+\.\s+.+""")
        private val TARGET_PREFIX = Regex("""^（([^）]+)）\s*(.+)$""")
        private val BRACKET_TARGET_PREFIX = Regex("""^\[([^\]]+)]\s*(.+)$""")
        private val META_SUFFIX_REGEX = Regex("""\s*\[meta:\s*([^\]]+)\]\s*$""")

        /** 高风险动作词：命中即插入人工确认点。 */
        private val HIGH_RISK_WORDS = listOf(
            "支付", "付款", "转账", "汇款", "提现", "充值", "下单", "购买", "结账", "退款",
            "红包", "收款", "余额", "银行卡", "信用卡", "密码", "验证码", "实名", "登录", "注册",
            "授权", "绑定", "解绑", "注销", "删除", "清空", "卸载", "格式化", "重置",
            "发送", "发布", "群发", "拨号", "通话", "短信", "邮件", "分享",
            "修改设置", "开启权限", "关闭权限", "定位", "录音", "拍照",
            "pay", "payment", "transfer", "checkout", "purchase", "delete", "remove",
            "uninstall", "reset", "format", "send", "publish", "login", "password",
            "verification code", "authorize", "bind", "unbind", "call", "sms", "email",
        )

        /** 高敏目标应用的包名/名称片段。 */
        private val HIGH_RISK_TARGETS = listOf(
            "bank",
            "pay",
            "wallet",
            "finance",
            "alipay",
            "支付宝",
            "微信支付",
            "银行",
        )

        fun classifyRisk(action: String, targetApp: String): String? {
            val haystack = "$action $targetApp".lowercase()
            val matchedWord = HIGH_RISK_WORDS.firstOrNull { haystack.contains(it) }
            if (matchedWord != null) return "包含高风险动作「$matchedWord」"
            val matchedTarget = HIGH_RISK_TARGETS.firstOrNull { haystack.contains(it) }
            return matchedTarget?.let { "目标应用属于高风险类型「$it」" }
        }

        private fun defaultTitle(steps: List<RawStep>): String = steps.firstOrNull()?.action?.take(24)?.takeIf(String::isNotBlank) ?: "示范技能"

        private fun defaultSummary(stepCount: Int): String = "由示范录制编译的可复用技能，共 $stepCount 步。"

        private fun defaultId(title: String): String {
            val slug = title.lowercase()
                .map { ch -> if (ch in 'a'..'z' || ch in '0'..'9') ch else '_' }
                .joinToString("")
                .trim('_')
                .take(24)
                .ifBlank { "workflow" }
            val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
            return "teach_${slug}_$suffix"
        }
    }

    @Serializable
    private data class ModelRefinement(
        val name: String = "",
        val description: String = "",
        val confirmStepNumbers: List<Int> = emptyList(),
    )

    private data class RawStep(
        val index: Int,
        val action: String,
        val targetApp: String,
        val targetPackage: String = "",
        val targetText: String = "",
        val contentDesc: String = "",
        val resourceIdHint: String = "",
        val actionKind: String = "",
    ) {
        constructor(action: String, targetApp: String) : this(0, action, targetApp)
    }
}

/** 可注入的模型提炼入口；无实现时编译器直接走规则路径。 */
fun interface TeachSkillRefiner {
    suspend fun refine(request: String): String
}

/** 复用应用统一 ChatService 的一次性非流式提炼。 */
class ChatTeachSkillRefiner(
    private val chatService: ChatService,
) : TeachSkillRefiner {
    override suspend fun refine(request: String): String {
        val completion = chatService.completeText(
            messages = listOf(UIMessage(role = MessageRole.USER, content = request)),
            temperature = 0.2f,
            maxTokens = 800,
        )
        return completion.text
    }
}
