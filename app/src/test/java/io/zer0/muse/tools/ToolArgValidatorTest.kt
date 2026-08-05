package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgValidatorTest {

    private fun def(
        name: String = "test_tool",
        required: Set<String> = emptySet(),
        parameterTypes: Map<String, String> = emptyMap(),
    ) = ToolRegistry.ToolDef(
        name = name,
        description = "test",
        parameters = mapOf(),
        required = required,
        parameterTypes = parameterTypes,
    )

    @Test
    fun missingRequired_failsWithReadableError() {
        val result = ToolArgValidator.validate(
            "test_tool",
            mapOf("other" to "x"),
            def(required = setOf("query")),
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("query") })
        assertEquals(ToolArgValidator.ERROR_TYPE, ToolArgValidator.ERROR_TYPE)
    }

    @Test
    fun integerCoercion_acceptsNumericString() {
        val result = ToolArgValidator.validate(
            "test_tool",
            mapOf("max_results" to "\"5\""),
            def(parameterTypes = mapOf("max_results" to "integer")),
        )
        assertTrue(result.valid)
        assertEquals("5", result.coercedArgs["max_results"])
    }

    @Test
    fun integerCoercion_rejectsNonNumeric() {
        val result = ToolArgValidator.validate(
            "test_tool",
            mapOf("max_results" to "five"),
            def(parameterTypes = mapOf("max_results" to "integer")),
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("max_results") })
    }

    @Test
    fun booleanCoercion_normalizes() {
        val result = ToolArgValidator.validate(
            "test_tool",
            mapOf("enabled" to "1"),
            def(parameterTypes = mapOf("enabled" to "boolean")),
        )
        assertTrue(result.valid)
        assertEquals("true", result.coercedArgs["enabled"])
    }

    @Test
    fun unknownTool_reportsUnknown() {
        val result = ToolArgValidator.validate("nope", emptyMap(), null)
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("nope") })
    }

    @Test
    fun extraArgs_arePreserved() {
        val result = ToolArgValidator.validate(
            "test_tool",
            mapOf("extra" to "kept"),
            def(),
        )
        assertTrue(result.valid)
        assertEquals("kept", result.coercedArgs["extra"])
    }
}
