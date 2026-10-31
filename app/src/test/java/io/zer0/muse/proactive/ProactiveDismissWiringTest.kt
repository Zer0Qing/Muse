package io.zer0.muse.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主动消息四条闭环的接线钉板。
 *
 * 这些路径几乎都靠「状态在库里怎么变」和「某个按钮点下去会调到哪」成立，
 * 单测能覆盖前者，但「接收器到底注册了没有 / 通知上到底有没有挂按钮 /
 * 巡检有没有真的调过期收尾」这类事只能从源码上看。这类遗漏编译得过、
 * 单测也全绿，只有在真机上点了没反应才发现，所以用扫描钉住。
 */
class ProactiveDismissWiringTest {

    @Test
    fun `只有本协议的动作才会被认领`() {
        assertEquals(
            "o1",
            ProactiveDismissProtocol.dismissedOutcomeId(ProactiveDismissProtocol.ACTION_DISMISS, "o1"),
        )
        assertNull(
            "别的 action 不该被认领",
            ProactiveDismissProtocol.dismissedOutcomeId("io.zer0.muse.action.OTHER", "o1"),
        )
        assertNull(
            "没带 action 的广播不算",
            ProactiveDismissProtocol.dismissedOutcomeId(null, "o1"),
        )
        assertNull(
            "带空 id 的广播没有可标记的对象",
            ProactiveDismissProtocol.dismissedOutcomeId(ProactiveDismissProtocol.ACTION_DISMISS, "  "),
        )
    }

    @Test
    fun `通知动作接收器用不导出标志注册`() {
        val app = source("io/zer0/muse/MuseApp.kt")
        val call = callSite(app, "ContextCompat.registerReceiver(")
        assertTrue("注册的必须是主动消息动作接收器：$call", call.contains("ProactiveDismissReceiver()"))
        assertTrue("过滤条件必须是本协议的动作：$call", call.contains("ProactiveDismissProtocol.ACTION_DISMISS"))
        assertTrue(
            "必须显式声明 RECEIVER_NOT_EXPORTED：这条广播只该由本应用自己发出：$call",
            call.contains("ContextCompat.RECEIVER_NOT_EXPORTED"),
        )
        assertTrue(
            "不导出就足够，不该顺手改成 RECEIVER_EXPORTED：$call",
            !call.contains("ContextCompat.RECEIVER_EXPORTED"),
        )
    }

    @Test
    fun `主动消息通知挂上了这次不用按钮`() {
        val notifier = source("io/zer0/muse/notification/MuseNotificationManager.kt")
        assertTrue(
            "通知上要有动作按钮，否则用户没有「这次不用」的入口",
            notifier.contains("builder.addAction("),
        )
        assertTrue(
            "按钮的点击目标必须是同一个动作广播",
            notifier.contains("buildDismissIntent(") &&
                callSite(notifier, "private fun buildDismissIntent(").contains("ProactiveDismissReceiver"),
        )
        assertTrue(
            "按钮文案用独立的字符串资源，不要和标题复用",
            notifier.contains("R.string.notif_proactive_action_dismiss"),
        )
        assertTrue(
            "广播 PendingIntent 必须不可变，Android 12+ 上可变 PendingIntent 由系统发出时有被改写的风险",
            callSite(notifier, "private fun buildDismissIntent(").contains("PendingIntent.FLAG_IMMUTABLE"),
        )
    }

    @Test
    fun `巡检里做了过期收尾与候选过滤`() {
        val runner = source("io/zer0/muse/proactive/ProactiveCycleRunner.kt")
        assertTrue("过期收尾要挂在巡检里，别再起一个定时器", runner.contains("expireOverdue(now)"))
        assertTrue("候选过滤要用学到的结论", runner.contains("unpopularKinds("))
        assertTrue(
            "结果账 id 要透传到通知，否则按钮不知道点的是哪条",
            runner.contains("outcomeId = outcomeId"),
        )
    }

    @Test
    fun `切换会话时标记主动消息已读`() {
        val controller = source("io/zer0/muse/ui/chat/ChatSessionController.kt")
        assertTrue(
            "通知深链、小手机、主聊天都收敛到会话切换，就得在这儿标记已读",
            controller.contains(".onSessionOpened(sessionId)"),
        )
    }

    // ══════════════════════════════════════════════════════════════

    /** 取某个调用点之后一小段源码，用来限定断言范围，避免整文件里别处的同名字符串蒙对。 */
    private fun callSite(text: String, marker: String): String {
        val start = text.indexOf(marker)
        if (start < 0) fail("源码里找不到 $marker")
        return text.substring(start, minOf(text.length, start + WINDOW))
    }

    private fun source(relative: String): String {
        val roots = listOf("app/src/main/java", "src/main/java", "../app/src/main/java")
        val file = roots.map { File(it, relative) }.firstOrNull { it.isFile }
            ?: fail("找不到源码 $relative（工作目录 ${File(".").absolutePath}）")
        return file.readText()
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private companion object {
        /** 从调用点往后看多远；够包住一次多行调用的实参表。 */
        const val WINDOW = 700
    }
}
