package io.zer0.muse.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMissingParserTest {

    @Test
    fun `recognizes top level structured runtime missing code`() {
        assertTrue(RuntimeMissing.isRuntimeMissingPayload("""{"error":"runtime_missing","message":"install it"}"""))
        assertTrue(RuntimeMissing.isRuntimeMissingPayload("""{"error": "runtime_missing"}"""))
    }

    @Test
    fun `recognizes nested and escaped structured payloads`() {
        val nested = """{"result":{"error":"runtime_missing"}}"""
        val escaped = """{"content":"{\"error\":\"runtime_missing\"}"}"""
        assertTrue(RuntimeMissing.isRuntimeMissingPayload(nested))
        assertTrue(RuntimeMissing.isRuntimeMissingPayload(escaped))
    }

    @Test
    fun `does not classify plain text lookalikes or malformed json`() {
        assertFalse(RuntimeMissing.isRuntimeMissingPayload("the text says runtime_missing"))
        assertFalse(RuntimeMissing.isRuntimeMissingPayload("{\"error\":\"runtime_missing\""))
        assertFalse(RuntimeMissing.isRuntimeMissingPayload("""{"error":"other_error"}"""))
    }
}
