package io.zer0.muse.data.agentbot

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachEventSanitizerTest {

    private val startedAt = 1_000_000L

    @Test
    fun `password flags are blocked before any text is captured`() {
        val decision = TeachEventSanitizer.decide(
            event = rawEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                text = "hunter2",
                isPassword = true,
            ),
            recordedSteps = 0,
            startedAt = startedAt,
            now = startedAt + 1_000L,
        )

        assertEquals(
            TeachEventSanitizer.Decision.Blocked(TeachEventSanitizer.BlockReason.PASSWORD_FIELD),
            decision,
        )
    }

    @Test
    fun `password view id is blocked even without the platform flag`() {
        val decision = TeachEventSanitizer.decide(
            event = rawEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                viewId = "com.example.app:id/et_password",
                text = "secret-value",
            ),
            recordedSteps = 0,
            startedAt = startedAt,
            now = startedAt + 1_000L,
        )

        assertEquals(
            TeachEventSanitizer.Decision.Blocked(TeachEventSanitizer.BlockReason.PASSWORD_FIELD),
            decision,
        )
    }

    @Test
    fun `text input keeps the field label and length but never the raw text`() {
        val rawText = "13800138000"
        val record = recordOf(
            TeachEventSanitizer.decide(
                event = rawEvent(
                    eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                    viewId = "com.example.app:id/search_input",
                    text = rawText,
                ),
                recordedSteps = 0,
                startedAt = startedAt,
                now = startedAt + 1_000L,
            ),
        )

        assertTrue(record.action.contains("search_input"))
        assertTrue(record.action.contains("11 字符"))
        assertFalse(record.action.contains(rawText))
        assertEquals("com.example.app", record.targetApp)
        assertEquals("com.example.app", record.targetPackage)
        assertEquals("text_changed", record.actionKind)
        assertEquals("", record.targetText)
        assertFalse(record.contentDesc.contains(rawText))
    }

    @Test
    fun `empty text input is still recorded as a redacted action`() {
        val record = recordOf(
            TeachEventSanitizer.decide(
                event = rawEvent(eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED),
                recordedSteps = 0,
                startedAt = startedAt,
                now = startedAt + 1_000L,
            ),
        )

        assertTrue(record.action.contains("内容已脱敏"))
        assertFalse(record.action.contains("null"))
    }

    @Test
    fun `click event records a human readable label`() {
        val record = recordOf(
            TeachEventSanitizer.decide(
                event = rawEvent(
                    eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                    viewId = "com.example.app:id/search_button",
                    contentDescription = "搜索",
                ),
                recordedSteps = 0,
                startedAt = startedAt,
                now = startedAt + 1_000L,
            ),
        )

        assertEquals("点击「搜索」", record.action)
        assertEquals("com.example.app", record.targetApp)
        assertEquals("com.example.app", record.targetPackage)
        assertEquals("click", record.actionKind)
        assertEquals("搜索", record.contentDesc)
        assertEquals("search_button", record.resourceIdHint)
    }

    @Test
    fun `click text supplies a bounded static target label but editable text stays out`() {
        val button = recordOf(
            TeachEventSanitizer.decide(
                event = rawEvent(
                    eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                    className = "android.widget.Button",
                    text = "继续",
                ),
                recordedSteps = 0,
                startedAt = startedAt,
            ),
        )
        val editable = recordOf(
            TeachEventSanitizer.decide(
                event = rawEvent(
                    eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                    className = "com.example.CustomField",
                    text = "private input",
                    isEditable = true,
                ),
                recordedSteps = 0,
                startedAt = startedAt,
            ),
        )

        assertEquals("继续", button.targetText)
        assertEquals("", editable.targetText)
        assertFalse(editable.action.contains("private input"))
    }

    @Test
    fun `window state change records the page name`() {
        val record = recordOf(
            TeachEventSanitizer.decide(
                event = rawEvent(
                    eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    className = "com.example.app.MainActivity",
                ),
                recordedSteps = 0,
                startedAt = startedAt,
                now = startedAt + 1_000L,
            ),
        )

        assertEquals("切换到「MainActivity」", record.action)
    }

    @Test
    fun `payment and banking packages are rejected`() {
        val sensitivePackages = listOf(
            "com.icbc.mobile",
            "com.eg.android.AlipayGphone",
            "com.paypal.android.p2pmobile",
            "com.example.wallet",
            "com.cmbchina.ccb",
        )
        sensitivePackages.forEach { packageName ->
            assertTrue(packageName, TeachEventSanitizer.isSensitivePackage(packageName))
            assertEquals(
                packageName,
                TeachEventSanitizer.Decision.Blocked(TeachEventSanitizer.BlockReason.SENSITIVE_PACKAGE),
                TeachEventSanitizer.decide(
                    event = rawEvent(
                        eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                        packageName = packageName,
                        contentDescription = "确认支付",
                    ),
                    recordedSteps = 0,
                    startedAt = startedAt,
                    now = startedAt + 1_000L,
                ),
            )
        }
    }

    @Test
    fun `ordinary chat and browser packages stay recordable`() {
        assertFalse(TeachEventSanitizer.isSensitivePackage("com.tencent.mobileqq"))
        assertFalse(TeachEventSanitizer.isSensitivePackage("com.android.chrome"))
        assertFalse(TeachEventSanitizer.isSensitivePackage(""))
        assertTrue(
            TeachEventSanitizer.decide(
                event = rawEvent(
                    eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                    packageName = "com.android.chrome",
                    contentDescription = "地址栏",
                ),
                recordedSteps = 0,
                startedAt = startedAt,
                now = startedAt + 1_000L,
            ) is TeachEventSanitizer.Decision.Record,
        )
    }

    @Test
    fun `sixty step budget blocks the next event`() {
        val event = rawEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            contentDescription = "下一步",
            timestamp = startedAt + 1_000L,
        )

        assertTrue(
            TeachEventSanitizer.decide(event, recordedSteps = 59, startedAt = startedAt) is
                TeachEventSanitizer.Decision.Record,
        )
        assertEquals(
            TeachEventSanitizer.Decision.Blocked(TeachEventSanitizer.BlockReason.STEP_LIMIT),
            TeachEventSanitizer.decide(event, recordedSteps = TeachEventSanitizer.MAX_STEPS, startedAt = startedAt),
        )
    }

    @Test
    fun `ten minute budget blocks events at the boundary`() {
        val insideWindow = rawEvent(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            contentDescription = "下一步",
            timestamp = startedAt + TeachEventSanitizer.MAX_DURATION_MS - 1L,
        )
        val atBoundary = insideWindow.copy(timestamp = startedAt + TeachEventSanitizer.MAX_DURATION_MS)

        assertTrue(
            TeachEventSanitizer.decide(insideWindow, recordedSteps = 0, startedAt = startedAt) is
                TeachEventSanitizer.Decision.Record,
        )
        assertEquals(
            TeachEventSanitizer.Decision.Blocked(TeachEventSanitizer.BlockReason.TIME_LIMIT),
            TeachEventSanitizer.decide(atBoundary, recordedSteps = 0, startedAt = startedAt),
        )
    }

    @Test
    fun `unrelated accessibility events are ignored without producing steps`() {
        assertEquals(
            TeachEventSanitizer.Decision.Ignored,
            TeachEventSanitizer.decide(
                event = rawEvent(eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED),
                recordedSteps = 0,
                startedAt = startedAt,
                now = startedAt + 1_000L,
            ),
        )
    }

    private fun rawEvent(
        eventType: Int,
        packageName: String = "com.example.app",
        className: String = "com.example.app.MainActivity",
        viewId: String = "",
        text: String? = null,
        contentDescription: String? = null,
        isPassword: Boolean = false,
        isEditable: Boolean = false,
        timestamp: Long = startedAt + 1_000L,
    ): TeachEventSanitizer.RawEvent = TeachEventSanitizer.RawEvent(
        eventType = eventType,
        packageName = packageName,
        className = className,
        viewId = viewId,
        text = text,
        contentDescription = contentDescription,
        isEditable = isEditable,
        isPassword = isPassword,
        timestamp = timestamp,
    )

    private fun recordOf(decision: TeachEventSanitizer.Decision): TeachEventSanitizer.Decision.Record {
        return when (decision) {
            is TeachEventSanitizer.Decision.Record -> decision
            is TeachEventSanitizer.Decision.Blocked -> throw AssertionError("预期记录步骤，实际被拦截: ${decision.reason}")
            TeachEventSanitizer.Decision.Ignored -> throw AssertionError("预期记录步骤，实际被忽略")
        }
    }
}
