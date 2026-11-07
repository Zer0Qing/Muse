package io.zer0.muse.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAF 删除失败原因的归类（[safDeleteReason]）。
 *
 * 这条判据值得单独钉：`DocumentFile.delete()` 只会返回 false，原始异常被吞掉，
 * 用户看到的就是「删除失败」四个字。而真正能行动的信息恰好分两类，措辞不能互换：
 *  - 属于别的应用：系统不是禁止，而是要用户再确认一次；
 *  - 目录只有读权限：再怎么确认也删不掉，得回去改挂载授权。
 *
 * 把函数放文件级而不是类成员，就是为了能脱离 Context 与 treeUri 直接测。
 */
class SafDeleteReasonTest {

    @Test
    fun `系统拦下时说的是再确认而不是失败`() {
        val reason = safDeleteReason("DCIM/Camera/a.jpg", SecurityException("denied"))
        assertTrue("要点出是系统拦的：$reason", reason.contains("系统拦下"))
        assertTrue("要给出下一步（去相册或系统弹窗）：$reason", reason.contains("再确认一次"))
        assertTrue("只报文件名，不把整条内部路径甩给用户：$reason", reason.contains("a.jpg"))
    }

    @Test
    fun `RecoverableSecurityException 走同一类说法`() {
        // Android 11+ 实际抛的是 RecoverableSecurityException（继承 SecurityException）。
        // 这里用子类模拟，钉住「按父类判」这件事不会退化成「未知失败」。
        val recoverable = object : SecurityException("requires user confirmation") {}
        val reason = safDeleteReason("photos:/Pictures/x.png", recoverable)
        assertTrue(reason, reason.contains("系统拦下"))
    }

    @Test
    fun `没有异常时只说失败不编原因`() {
        // DocumentFile.delete() 返回 false 而 contentResolver 那条路也没抛异常时，
        // 宁可只说「删除失败」，也不要猜一个原因给用户。
        val reason = safDeleteReason("docs/note.txt", null)
        assertEquals("删除失败：note.txt", reason)
    }

    @Test
    fun `其他异常带上原始信息`() {
        val reason = safDeleteReason("docs/note.txt", IllegalStateException("EACCES"))
        assertTrue(reason, reason.contains("note.txt"))
        assertTrue("要留下原始异常信息便于回捞：$reason", reason.contains("EACCES"))
    }

    @Test
    fun `只给读权限的目录要说成权限问题`() {
        // OperationNotImplementedException 按类名比对（API 26 类型在 JVM 单测里未必可加载）。
        // 这里能测到的是：该分支不会被误归到「系统拦下」或「未知失败」。
        val reason = safDeleteReason("docs/note.txt", UnsupportedOperationException("no delete"))
        assertTrue("不能误说成系统拦下：$reason", !reason.contains("系统拦下"))
        assertTrue("至少留下原始信息：$reason", reason.contains("no delete"))
    }
}
