package io.zer0.muse.ui.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端合成失败后的三种出口。
 *
 * 真起一次系统 TTS 引擎拿不到这些分支（异步回调 + 设备差异），所以判定逻辑抽成
 * [resolveCloudSynthOutcome]，这里按「云成功 / 回落可用 / 无处可落」逐一钉住。
 */
class TtsSynthOutcomeTest {

    @Test
    fun `云成功时是云成功态且不调系统 TTS`() = runBlocking {
        var systemCalls = 0

        val result = resolveCloudSynthOutcome(
            cloudOk = true,
            failureReason = "不该用到的原因",
            systemTtsReady = true,
        ) {
            systemCalls++
            true
        }

        assertEquals(TtsSynthResult.Success, result)
        assertEquals("云成功不该再花一次系统合成", 0, systemCalls)
    }

    @Test
    fun `云失败但系统可用时回落并带上原因`() = runBlocking {
        val result = resolveCloudSynthOutcome(
            cloudOk = false,
            failureReason = "API Key 为空",
            systemTtsReady = true,
        ) { true }

        assertTrue("云端失败、系统可用必须是回落态，实际: $result", result is TtsSynthResult.SystemFallback)
        assertEquals("API Key 为空", (result as TtsSynthResult.SystemFallback).reason)
    }

    @Test
    fun `系统不可用时是彻底失败且不调系统合成`() = runBlocking {
        var systemCalls = 0

        val result = resolveCloudSynthOutcome(
            cloudOk = false,
            failureReason = "API Key 为空",
            systemTtsReady = false,
        ) {
            systemCalls++
            true
        }

        assertTrue("系统 TTS 都没就绪只能是失败态，实际: $result", result is TtsSynthResult.Failure)
        assertTrue(
            "原因里要能看出云端与系统都不可用: $result",
            (result as TtsSynthResult.Failure).reason.contains("API Key 为空"),
        )
        assertEquals(0, systemCalls)
    }

    @Test
    fun `系统合成也失败时是彻底失败`() = runBlocking {
        val result = resolveCloudSynthOutcome(
            cloudOk = false,
            failureReason = "云端 TTS 合成失败",
            systemTtsReady = true,
        ) { false }

        assertTrue("系统合成失败也是彻底失败，实际: $result", result is TtsSynthResult.Failure)
    }
}
