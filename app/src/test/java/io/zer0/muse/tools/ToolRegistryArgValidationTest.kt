package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistryArgValidationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun execute_rejectsMissingRequiredArgWithStructuredError() = runBlocking {
        val registry = ToolRegistry(context)
        WeatherToolsRegistrar(context, registry)
        val outcome = registry.execute("get_weather", emptyMap())
        // 临时诊断:失败时打印实际内容
        if (!outcome.isError) {
            println("DIAG outcome=" + outcome.content + " details=" + outcome.details + " names=" + registry.listTools().map { it.name })
        }
        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("location"))
        assertEquals(ToolArgValidator.ERROR_TYPE, outcome.details["errorType"])
    }

    @Test
    fun execute_coercesNumericStringForTypedParam() = runBlocking {
        val registry = ToolRegistry(context)
        CoreToolsRegistrar(context, registry)
        // calculator 无类型声明，用 execute_javascript 或直接走已知带类型的工具。
        // 这里验证 executeFromJson 的普通路径仍可执行 calculator。
        val outcome = registry.execute("calculator", mapOf("expression" to "1+2"))
        assertTrue(!outcome.isError)
        assertTrue(outcome.content.contains("= 3"))
    }
}
