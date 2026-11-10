package io.zer0.muse.perf

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.transformer.ExperienceTextCache
import io.zer0.muse.ui.markdown.SanitizedHtmlCache
import io.zer0.muse.util.MessageTokenCache
import io.zer0.muse.util.TokenEstimator
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 性能整改的**微基准**（JVM 侧，只打印不断言）。
 *
 * ## 为什么要有它
 *
 * 这一系列改动的收益大多是"确定性计数"（重载次数、写盘次数、分配次数），
 * 但没人知道这些计数折算成多长时间的相对量级。真机不可用时，至少可以在 JVM 上
 * 把**同一份输入**在"改前写法"与"改后写法"下的耗时并排打出来 —— 它不代表 ART
 * 上的绝对性能，但能回答"这条优化值不值得留着"。
 *
 * ## 纪律
 *
 * - **不做断言**（耗时随机器波动，断言必 flake）；只在 stdout 打印表格。
 * - 每项都先预热，避免把 JIT/类加载算进去。
 * - 数字只用于横向对比（改前 vs 改后、开缓存 vs 关缓存），不能当"真机耗时"引用。
 *
 * 运行：`:app:testDebugUnitTest --tests "io.zer0.muse.perf.PerfMicroBenchmarkTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerfMicroBenchmarkTest {

    private inline fun measureMillis(rounds: Int, block: () -> Unit): Double {
        // 预热
        repeat(3) { block() }
        val start = System.nanoTime()
        repeat(rounds) { block() }
        return (System.nanoTime() - start) / 1_000_000.0 / rounds
    }

    private fun row(name: String, before: Double, after: Double) {
        println(
            "[perf] %-46s 改前 %8.3f ms | 改后 %8.3f ms | 倍率 %5.1fx".format(
                name,
                before,
                after,
                if (after > 0) before / after else 0.0,
            ),
        )
    }

    /** 日志链路的成本分解（P22/P23 前后对比）。 */
    private fun probeLoggingCosts() { // 6) 一条日志的完整代价（PII 脱敏 + 文件追加），见 Logger.d 的实现
        val logDir = java.nio.file.Files.createTempDirectory("muse-log-bench").toFile()
        io.zer0.common.Logger.initFileLog(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
        io.zer0.common.Logger.d("bench", "预热")
        val perLog = measureMillis(50) {
            repeat(100) { index -> io.zer0.common.Logger.d("bench", "第 $index 条日志：一次工具调用完成，耗时 12ms") }
        }
        // 再把 sink（DebugLogStore）接上——生产里 Logger.sink 由 MuseApp 设置，
        // 而 DebugLogStore 每次写日志都会调整环形缓冲（第 21 轮改成自增计数，不再 O(n)）。
        io.zer0.muse.debug.DebugLogStore.clear()
        io.zer0.common.Logger.sink = { level, tag, msg, thr ->
            io.zer0.muse.debug.DebugLogStore.log(level, tag, msg, thr)
        }
        repeat(2_000) { index -> io.zer0.common.Logger.d("bench", "填充第 $index 条日志，用于把缓冲区打满") }
        val perLogWithSink = measureMillis(50) {
            repeat(100) { index -> io.zer0.common.Logger.d("bench", "第 $index 条日志：一次工具调用完成，耗时 12ms") }
        }
        row("单条日志（含 DebugLogStore sink，缓冲已满）", 0.0, perLogWithSink / 100.0)
        io.zer0.common.Logger.sink = null
        io.zer0.muse.debug.DebugLogStore.clear()

        // 只留 PII 脱敏：把文件等级抬到 WARN（D 行不写文件）、sink 置空，隔离出脱敏本身的成本。
        io.zer0.common.Logger.fileLogLevel = io.zer0.common.Logger.Level.WARN
        val sanitizeOnly = measureMillis(50) {
            repeat(100) { index -> io.zer0.common.Logger.d("bench", "第 $index 条日志：一次工具调用完成，耗时 12ms") }
        }
        row("单条日志（仅 PII 脱敏，不写文件）", 0.0, sanitizeOnly / 100.0)
        io.zer0.common.Logger.fileLogLevel = io.zer0.common.Logger.Level.DEBUG
        // 改前基线 0.246ms 是同一台机器上"每行 open + append + close"时的实测值（第 20 轮）。
        row("单条日志（脱敏 + 追加，100 条均值；改前=每行开关文件）", 0.246, perLog / 100.0)
        logDir.deleteRecursively()
    }

    /** 工具循环里每轮一次的历史占用估算：5 轮共用缓存能省多少（第 38 轮）。 */
    private fun probeToolLoopTokens() {
        val base = (0 until 100).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "第 $index 条历史消息，正文长度中等。".repeat(4),
            )
        }
        // 模拟 5 轮：每轮追加 assistant(tool_calls) + tool(result) 两条
        val rounds = (1..5).map { round ->
            listOf(
                UIMessage(role = MessageRole.ASSISTANT, content = "第 $round 轮工具调用前的正文".repeat(10)),
                UIMessage(role = MessageRole.TOOL, content = "第 $round 轮工具输出".repeat(40)),
            )
        }
        val plain = measureMillis(30) {
            var history = base
            rounds.forEach { added ->
                history = history + added
                TokenEstimator.estimate(history)
            }
        }
        val cached = measureMillis(30) {
            val cache = MessageTokenCache()
            var history = base
            rounds.forEach { added ->
                history = history + added
                TokenEstimator.estimate(history, cache = cache)
            }
        }
        row("工具循环 5 轮的历史占用估算（各自重算）", 0.0, plain)
        row("同上，5 轮共用计数缓存（P27）", plain, cached)
    }

    /** 发送前溢出复核：同一份 payload 估两遍，第二遍接缓存能省多少（第 37 轮）。 */
    private fun probePreSendOverflow() {
        val prefix = (0 until 2).map { index ->
            UIMessage(role = MessageRole.SYSTEM, content = "系统前缀 $index ".repeat(400))
        }
        val history = (0 until 200).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "第 $index 条历史消息，正文长度中等，用来模拟接近窗口上限的会话。".repeat(4),
            )
        }
        val compressed = listOf(UIMessage(role = MessageRole.SYSTEM, content = "这一轮的压缩摘要。".repeat(60))) +
            history.takeLast(20)

        val cold = measureMillis(30) {
            TokenEstimator.estimate(prefix + history)
            TokenEstimator.estimate(prefix + compressed)
        }
        val warm = measureMillis(30) {
            val cache = MessageTokenCache()
            TokenEstimator.estimate(prefix + history, cache = cache)
            TokenEstimator.estimate(prefix + compressed, cache = cache)
        }
        row("发送前溢出复核：原始 + 压缩后 两遍估算", 0.0, cold)
        row("同上，两遍共用计数缓存（P26）", cold, warm)
    }

    /** 三项"一直被注释承认为 O(n)、但从没量过"的残余成本（第 36 轮）。 */
    private fun probeResidualCosts() {
        val longHistory = (0 until 200).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "第 $index 条消息正文，长度接近真实对话，用来估量整表复制的成本。".repeat(3),
            )
        }
        // ① 流式每 chunk 的"整表复制 + 换一条"
        val listCopy = measureMillis(200) {
            val copy = longHistory.toMutableList()
            copy[longHistory.size - 1] = longHistory.last().copy(content = longHistory.last().content + "x")
        }
        row("流式每 chunk：200 条消息整表复制换一条", 0.0, listCopy)

        // ② 每轮一次的上文占用全量重估
        val sysPrompt = "系统提示词".repeat(400)
        io.zer0.muse.ui.chat.ContextTokenUsage.estimate(longHistory, sysPrompt)
        val usageCost = measureMillis(50) { io.zer0.muse.ui.chat.ContextTokenUsage.estimate(longHistory, sysPrompt) }
        row("每轮一次：200 条历史 + 系统提示的占用率重估", 0.0, usageCost)

        // ③ 流式每次状态变化都重算的"过程分组"
        val displayMessages = (0 until 100).map { index ->
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = if (index % 3 == 0) "有正文的第 $index 条" else "",
                reasoning = if (index % 3 == 0) null else "思考片段 $index",
            )
        }
        io.zer0.muse.ui.chat.ChatDisplayGrouper.group(displayMessages, { it.content.isBlank() && it.reasoning != null })
        val groupCost = measureMillis(100) {
            io.zer0.muse.ui.chat.ChatDisplayGrouper.group(
                displayMessages,
                { it.content.isBlank() && it.reasoning != null },
            )
        }
        row("每次状态变化：100 条消息的过程分组", 0.0, groupCost)
    }

    @Test
    fun report() {
        println("[perf] ===== JVM 微基准（不代表真机耗时，只做相对比较）=====")

        // 1) 卡片 HTML 清洗：每次装配 vs 命中缓存（P5）
        val svg = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 400\">")
            repeat(200) { index ->
                append("<rect x=\"${index * 4}\" y=\"10\" width=\"3\" height=\"120\" fill=\"#2f8f6b\"/>")
                append("<text x=\"${index * 4}\" y=\"140\" font-size=\"10\">$index</text>")
            }
            append("</svg>")
        }
        val cardCode = svg + "<script>" + "var x=1;".repeat(400) + "</script>"
        val coldSanitize = measureMillis(30) {
            SanitizedHtmlCache.clear()
            SanitizedHtmlCache.get(cardCode)
        }
        val warmSanitize = measureMillis(2_000) { SanitizedHtmlCache.get(cardCode) }
        row("卡片 HTML 清洗（${cardCode.length / 1024}KB）", coldSanitize, warmSanitize)

        // 2) token 估算：50 条历史，逐次全量 vs 共用缓存（P11）
        val history = (0 until 50).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "第 $index 条历史消息，正文长度中等，用来模拟真实对话。".repeat(6),
            )
        }
        TokenEstimator.estimate(history) // 触发 jtokkit 初始化
        val noCache = measureMillis(20) { TokenEstimator.estimate(history.take(25)) }
        val cache = MessageTokenCache()
        TokenEstimator.estimate(history.take(25), cache = cache)
        val withCache = measureMillis(200) { TokenEstimator.estimate(history, cache = cache) }
        row("token 估算（25 条消息，纯 BPE 重复跑）", noCache, withCache)

        // 3) 经验库打分：200 条经验的小写副本，逐次重算 vs 缓存（P13）
        val experiences = (0 until 200).map { index ->
            Triple("exp-$index", 1L, ("经验标题 $index " + "正文内容较长用于模拟真实场景。".repeat(8)))
        }
        val expCache = ExperienceTextCache()
        val expCold = measureMillis(20) {
            expCache.clear()
            experiences.forEach { (id, updatedAt, text) ->
                val key = expCache.key(id, updatedAt)
                expCache.get(key) ?: expCache.put(key, text.lowercase())
            }
        }
        experiences.forEach { (id, updatedAt, text) -> expCache.put(expCache.key(id, updatedAt), text.lowercase()) }
        val expWarm = measureMillis(500) {
            experiences.forEach { (id, updatedAt, _) -> expCache.get(expCache.key(id, updatedAt)) }
        }
        row("经验库打分文本（200 条 lowercase）", expCold, expWarm)

        // 4) 卡片数据落盘编码：整表 vs 单卡分片（P18）
        val cards = (0 until 50).associate { index ->
            "card-$index" to ("[" + "{\"k\":\"v\"},".repeat(400) + "{}]")
        }
        val cardJson = kotlinx.serialization.json.JsonObject(
            cards.map { (key, value) ->
                key to kotlinx.serialization.json.JsonPrimitive(value)
            }.toMap(),
        )
        val fullTable = measureMillis(20) {
            io.zer0.common.AppJson.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                cardJson,
            )
        }
        val oneShard = measureMillis(500) {
            io.zer0.muse.data.card.CardDataFiles.encode("card-7", cards.getValue("card-7"))
        }
        row("卡片数据落盘编码（50 张卡）", fullTable, oneShard)

        // 5) 流式期间每帧的 token 条估算（P21）：单条 5KB 消息的输出估算
        val streamed = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "助手正在生成的正文，包含卡片源码片段。".repeat(180),
        )
        TokenEstimator.estimate(streamed.content)
        val perFrameEstimate = measureMillis(100) { TokenEstimator.estimate(listOf(streamed)) }
        row("单条 5KB 消息的输出 token 估算（流式每帧）", perFrameEstimate, 0.0)

        probeLoggingCosts()

        // 7) 冷启动里的"工具注册"到底多贵（启动期同步做的就是这件事）
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        io.zer0.muse.tools.ToolRegistry(ctx) // 预热
        val registryOnly = measureMillis(20) { io.zer0.muse.tools.ToolRegistry(ctx) }
        val withOneRegistrar = measureMillis(20) {
            val r = io.zer0.muse.tools.ToolRegistry(ctx)
            io.zer0.muse.tools.CoreToolsRegistrar(ctx, r)
        }
        row("ToolRegistry 构造（内置注册）", registryOnly, 0.0)
        row("+1 个 registrar（CoreToolsRegistrar，3 个工具）", withOneRegistrar - registryOnly, 0.0)

        // 8) 冷启动时主线程做的通知历史恢复：SharedPreferences 里那份 JSON 的解析成本
        val records = (0 until 200).map { index ->
            io.zer0.muse.notification.NotificationRecord(
                packageName = "com.example.app$index",
                title = "标题 $index",
                text = "这是一条通知正文，长度接近真实通知，用于估算解析成本。".repeat(2),
                timestamp = 1_800_000_000_000L + index,
            )
        }
        val recordsJson = io.zer0.common.AppJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(io.zer0.muse.notification.NotificationRecord.serializer()),
            records,
        )
        val recordSerializer = kotlinx.serialization.builtins.ListSerializer(
            io.zer0.muse.notification.NotificationRecord.serializer(),
        )
        val restoreCost = measureMillis(30) {
            io.zer0.common.AppJson.decodeFromString(recordSerializer, recordsJson)
        }
        println(
            "[perf] 启动期通知历史恢复（200 条，${recordsJson.length} 字符 JSON）  解析 %8.3f ms".format(restoreCost),
        )

        probeResidualCosts()
        probePreSendOverflow()
        probeToolLoopTokens()

        println("[perf] ===== 说明：以上为 JVM 相对量级；真机耗时需 adb 设备 =====")
        assertTrue("微基准不做断言，仅用于打印", true)
    }
}
