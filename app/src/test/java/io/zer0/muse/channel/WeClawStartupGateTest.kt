package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClawBot 长轮询的启动门禁 + 跟随渠道变化的起停判定。
 *
 * 老实现进程启动就无条件 restart(),没有渠道时循环 30 秒一条地空转写日志。修过之后
 * 必须两件事同时成立:没渠道时一次都不起,而且「停用再启用」仍旧收得到消息 ——
 * 后者靠的是 [weClawPollAction] 只在状态真正转移时动作(而不是一次性 decision)。
 */
class WeClawStartupGateTest {

    private fun weClaw(id: String, enabled: Boolean = true, secret: String = "secret") = ChannelConfig(
        id = id,
        platform = ChannelPlatform.WECLAW,
        enabled = enabled,
        appSecret = secret,
    )

    @Test
    fun `no channel means the poll loop is not started`() {
        assertFalse(weClawChannelAvailable(emptyList()))
        assertEquals(WeClawPollAction.NONE, weClawPollAction(available = false, running = false))
    }

    @Test
    fun `enabled weclaw channel starts the poll loop`() {
        assertTrue(weClawChannelAvailable(listOf(weClaw("c1"))))
        assertEquals(WeClawPollAction.START, weClawPollAction(available = true, running = false))
    }

    @Test
    fun `disabled channel or channel without secret does not count`() {
        assertFalse(weClawChannelAvailable(listOf(weClaw("c1", enabled = false))))
        assertFalse(weClawChannelAvailable(listOf(weClaw("c1", secret = "  "))))
        assertFalse(
            weClawChannelAvailable(
                listOf(ChannelConfig(id = "c2", platform = ChannelPlatform.TELEGRAM, enabled = true)),
            ),
        )
        // 同一条配置里其它平台的渠道不能把 WECLAW 循环带起来
        assertTrue(
            weClawChannelAvailable(
                listOf(
                    ChannelConfig(id = "c2", platform = ChannelPlatform.TELEGRAM, enabled = true),
                    weClaw("c1"),
                ),
            ),
        )
    }

    @Test
    fun `same availability never triggers anything twice`() {
        // 正在跑且仍有渠道:不能重启(否则每次配置读一遍就重建一次长轮询)
        assertEquals(WeClawPollAction.NONE, weClawPollAction(available = true, running = true))
        // 已经停且仍然没渠道:不能因为自己的订阅再把循环拉起来(防自触发)
        assertEquals(WeClawPollAction.NONE, weClawPollAction(available = false, running = false))
    }

    @Test
    fun `disabling stops and re-enabling starts again`() {
        // 停用 → 停
        assertEquals(WeClawPollAction.STOP, weClawPollAction(available = false, running = true))
        // 重新启用 → 起(停用再启用必须还能收到消息)
        assertEquals(WeClawPollAction.START, weClawPollAction(available = true, running = false))
    }
}
