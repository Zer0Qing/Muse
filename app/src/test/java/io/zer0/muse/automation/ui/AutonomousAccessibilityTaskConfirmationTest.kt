package io.zer0.muse.automation.ui

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousAccessibilityTaskConfirmationTest {
    @Test
    fun `grant is derived from the existing user task rather than page copy`() {
        val grant =
            buildAutonomousGrant(
                sessionId = "s1",
                latestUserMessage = "打开 com.xingin.xhs 浏览小红书",
                packageName = "com.xingin.xhs",
                nowMillis = 123,
            )
        val unrelated =
            buildAutonomousGrant(
                sessionId = "s1",
                latestUserMessage = "帮我记住这个包名 com.xingin.xhs",
                packageName = "com.xingin.xhs",
                nowMillis = 123,
            )

        assertTrue(grant != null && grant.taskFingerprint.startsWith("task:"))
        assertNull(unrelated)
    }

    @Test
    fun `different tasks produce different grant fingerprints`() {
        val first =
            buildAutonomousGrant("s1", "打开 com.xingin.xhs 浏览小红书", "com.xingin.xhs", 1)
        val second =
            buildAutonomousGrant("s1", "打开 com.xingin.xhs 点击购买", "com.xingin.xhs", 1)

        assertNotEquals(first?.taskFingerprint, second?.taskFingerprint)
    }
}
