package io.zer0.muse.tools.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityClientTest {

    private val packageName = "io.zer0.muse"
    private val serviceClass = "io.zer0.muse.automation.executors.MuseAccessibilityService"

    @Test
    fun accepts_full_and_relative_component_names() {
        assertTrue(
            AccessibilityClient.containsEnabledService(
                "$packageName/$serviceClass",
                packageName,
                serviceClass,
            ),
        )
        assertTrue(
            AccessibilityClient.containsEnabledService(
                "$packageName/.automation.executors.MuseAccessibilityService",
                packageName,
                serviceClass,
            ),
        )
    }

    @Test
    fun ignores_other_services_and_malformed_entries() {
        val raw = listOf(
            "com.example.other/.OtherService",
            "malformed-entry",
            "$packageName/.automation.executors.OtherService",
        ).joinToString(":")

        assertFalse(
            AccessibilityClient.containsEnabledService(raw, packageName, serviceClass),
        )
    }

    @Test
    fun empty_enabled_service_setting_is_not_enabled() {
        assertFalse(
            AccessibilityClient.containsEnabledService(null, packageName, serviceClass),
        )
        assertFalse(
            AccessibilityClient.containsEnabledService("", packageName, serviceClass),
        )
    }
}
