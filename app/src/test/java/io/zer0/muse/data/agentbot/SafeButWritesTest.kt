package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 风险等级不能当副作用等级的代理。

 * 审查 P2-11：`classifySideEffect` 把 `ToolRiskLevel.SAFE` 一律判成可重放。
 * 但风险等级衡量的是"要不要用户批准"，不是"重放会不会产生新副作用"。
 *
 * `generate_qr_code` 是 SAFE，但它会生成图片并挂到消息上 —— 重放会多一张图。
 * 而它在中心任务里既不过审批、又被当可重放，两个判断基于同一个错误前提。
 */
class SafeButWritesTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    @Test
    fun `writing SAFE tools are listed explicitly`() {
        assertTrue(
            "必须有显式清单收住 SAFE 但会写盘的工具",
            scheduler.contains("SAFE_BUT_WRITES"),
        )
        assertTrue(
            "generate_qr_code 会生成图片，重放会多一张",
            scheduler.contains("generate_qr_code"),
        )
    }

    @Test
    fun `the list is checked before the risk level`() {
        // 顺序很重要：先查清单，再按等级判。反过来的话 SAFE 会先被判成可重放。
        val at = scheduler.indexOf("private fun classifySideEffect(")
        assertTrue(at > 0)
        val body = scheduler.substring(at, minOf(at + 1400, scheduler.length))
        val listCheck = body.indexOf("in SAFE_BUT_WRITES")
        val levelCheck = body.indexOf("ToolRiskLevel.SAFE ->")
        assertTrue("两处都要有", listCheck > 0 && levelCheck > 0)
        assertTrue(
            "清单判断必须在等级判断之前，否则 SAFE 会先被判成可重放",
            listCheck < levelCheck,
        )
    }

    @Test
    fun `unknown tools stay non replayable`() {
        val at = scheduler.indexOf("private fun classifySideEffect(")
        val body = scheduler.substring(at, minOf(at + 1400, scheduler.length))
        assertTrue("拿不到等级要按不可重放处理", body.contains("null -> StepSideEffect.NO_BLIND_REPLAY"))
    }
}
