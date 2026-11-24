package io.zer0.muse.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 液态玻璃 RenderNode 自引用递归防回归守卫。
 *
 * `Modifier.layerBackdrop(b)` / `Modifier.liquefiable(s)` 录制子树时，若内部任意玻璃组件
 * 反向采样同一个 `b` / `s`（或嵌套第二层真玻璃录制），会在 Android HWUI RenderThread
 * 触发原生 SIGSEGV（无 Java 崩溃堆栈、不进 SafeMode）。
 *
 * 本测试钉死四处页面级录制点的隔离契约，避免后续改 UI 时把玻璃采样源再次泄露进录制子树。
 */
class BackdropRecursionGuardTest {

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件: $relativePath (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    @Test
    fun `HomeScreen isolates HorizontalPager subtree from outer backdrop and records only active style`() {
        val src = readSource("io/zer0/muse/ui/screen/HomeScreen.kt")
        assertTrue(
            "HomeScreen 的 HorizontalPager 内部必须将 LocalLayerBackdrop 置 null，防止子 Tab 自引用崩溃",
            src.contains("LocalLayerBackdrop provides null"),
        )
        assertTrue(
            "HomeScreen 的 HorizontalPager 内部必须将 LocalWaterGlassState 置 null",
            src.contains("LocalWaterGlassState provides null"),
        )
        assertTrue(
            "HomeScreen 必须向 HorizontalPager 内部传递 LocalInsideBackdropRecorder 防止嵌套双层录制",
            src.contains("LocalInsideBackdropRecorder provides"),
        )
        assertTrue(
            "HomeScreen 应按 GlassStyle.FROST / WATER 分流创建 backdrop",
            src.contains("GlassStyle.FROST") && src.contains("GlassStyle.WATER"),
        )
    }

    @Test
    fun `AgentBotScreen respects LocalInsideBackdropRecorder and isolates inner content subtree`() {
        val src = readSource("io/zer0/muse/ui/agentbot/AgentBotScreen.kt")
        assertTrue(
            "AgentBotScreen 必须检查 !LocalInsideBackdropRecorder.current，嵌入 HomeScreen 时不嵌套第二层真玻璃录制",
            src.contains("LocalInsideBackdropRecorder.current"),
        )
        assertTrue(
            "AgentBotScreen 内容层 Box 内部必须清空 LocalLayerBackdrop，防止 AgentInlineIdentityBar 自引用崩溃",
            src.contains("LocalLayerBackdrop provides null"),
        )
        assertTrue(
            "AgentBotScreen 应按 GlassStyle.FROST / WATER 分流创建 backdrop",
            src.contains("GlassStyle.FROST") && src.contains("GlassStyle.WATER"),
        )
    }

    @Test
    fun `ChatScreen and GroupChatDetailScreen guard against nested backdrop recording`() {
        val chatSrc = readSource("io/zer0/muse/ui/chat/ChatScreen.kt")
        val groupSrc = readSource("io/zer0/muse/ui/groupchat/GroupChatDetailScreen.kt")
        assertTrue(
            "ChatScreen 必须检查 LocalInsideBackdropRecorder.current 并按风格按需创建",
            chatSrc.contains("LocalInsideBackdropRecorder.current") &&
                chatSrc.contains("GlassStyle.FROST") &&
                chatSrc.contains("GlassStyle.WATER"),
        )
        assertTrue(
            "GroupChatDetailScreen 必须检查 LocalInsideBackdropRecorder.current 并按风格按需创建",
            groupSrc.contains("LocalInsideBackdropRecorder.current") &&
                groupSrc.contains("GlassStyle.FROST") &&
                groupSrc.contains("GlassStyle.WATER"),
        )
    }
}
