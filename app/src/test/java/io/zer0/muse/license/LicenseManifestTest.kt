package io.zer0.muse.license

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13: LicenseManifest 反序列化单元测试。
 *
 * 验证:
 *  1. manifest.json 能被 kotlinx.serialization 正确解析
 *  2. 字段映射正确(schemaVersion / appName / dependencies)
 *  3. 依赖条目按协议分组(groupedByLicense)工作
 *  4. licenseId 标签渲染(toLicenseLabel)产出可读协议名
 *  5. 协议全文路径非空(确保 assets 引用正确)
 *
 * 不依赖 Android Context(纯 JVM),所以 [LicenseRepository.loadManifest] 不在此测,
 * 这里直接用 raw JSON 字符串(与实际 manifest 等价)做反序列化测试。
 */
class LicenseManifestTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val sampleJson = """
        {
          "schemaVersion": 1,
          "generatedAt": "2026-07-05",
          "appName": "Muse",
          "appVersion": "0.22",
          "licenseTexts": {
            "apache-2.0": "licenses/apache-2.0.txt",
            "mit": "licenses/mit.txt",
            "epl-1.0": "licenses/epl-1.0.txt"
          },
          "dependencies": [
            { "groupId": "org.jetbrains.kotlin", "name": "kotlin-stdlib", "version": "2.4.0",
              "licenseId": "apache-2.0", "copyright": "Copyright JetBrains." },
            { "groupId": "com.auth0", "name": "java-jwt", "version": "4.4.0",
              "licenseId": "mit", "copyright": "Copyright Auth0, Inc." },
            { "groupId": "junit", "name": "junit", "version": "4.13.2",
              "licenseId": "epl-1.0", "copyright": "Copyright JUnit contributors." }
          ]
        }
    """.trimIndent()

    @Test
    fun manifest_parses_with_all_top_level_fields() {
        val manifest = json.decodeFromString(LicenseManifest.serializer(), sampleJson)
        assertEquals(1, manifest.schemaVersion)
        assertEquals("Muse", manifest.appName)
        assertEquals("0.22", manifest.appVersion)
        assertEquals("2026-07-05", manifest.generatedAt)
    }

    @Test
    fun dependencies_are_parsed_with_correct_field_count() {
        val manifest = json.decodeFromString(LicenseManifest.serializer(), sampleJson)
        assertEquals(3, manifest.dependencies.size)
        assertEquals(3, manifest.totalCount)
        val kotlin = manifest.dependencies[0]
        assertEquals("org.jetbrains.kotlin", kotlin.groupId)
        assertEquals("kotlin-stdlib", kotlin.name)
        assertEquals("2.4.0", kotlin.version)
        assertEquals("apache-2.0", kotlin.licenseId)
    }

    @Test
    fun groupedByLicense_splits_entries_by_licenseId_sorted() {
        val manifest = json.decodeFromString(LicenseManifest.serializer(), sampleJson)
        val grouped = manifest.groupedByLicense()
        assertEquals(listOf("apache-2.0", "epl-1.0", "mit"), grouped.keys.toList())
        assertEquals(1, grouped["apache-2.0"]?.size)
        assertEquals(1, grouped["mit"]?.size)
        assertEquals(1, grouped["epl-1.0"]?.size)
    }

    @Test
    fun licenseId_renders_to_human_label() {
        assertEquals("Apache 2.0", "apache-2.0".toLicenseLabel())
        assertEquals("MIT", "mit".toLicenseLabel())
        assertEquals("BSD 3-Clause", "bsd-3-clause".toLicenseLabel())
        assertEquals("BSD 2-Clause", "bsd-2-clause".toLicenseLabel())
        assertEquals("EPL 1.0", "epl-1.0".toLicenseLabel())
        // 未知协议直接显示原 id(graceful degradation)
        assertEquals("weird-protocol-v9", "weird-protocol-v9".toLicenseLabel())
    }

    @Test
    fun license_texts_map_is_preserved() {
        val manifest = json.decodeFromString(LicenseManifest.serializer(), sampleJson)
        assertEquals(3, manifest.licenseTexts.size)
        assertEquals("licenses/apache-2.0.txt", manifest.licenseTexts["apache-2.0"])
        assertNotNull(manifest.licenseTexts["mit"])
        assertTrue(manifest.licenseTexts.containsKey("epl-1.0"))
    }

    @Test
    fun empty_manifest_does_not_throw_and_reports_zero_count() {
        val emptyJson = """
            { "schemaVersion": 0, "generatedAt": "", "appName": "", "appVersion": "",
              "licenseTexts": {}, "dependencies": [] }
        """.trimIndent()
        val manifest = json.decodeFromString(LicenseManifest.serializer(), emptyJson)
        assertEquals(0, manifest.totalCount)
        assertTrue(manifest.dependencies.isEmpty())
        assertTrue(manifest.groupedByLicense().isEmpty())
    }
}
