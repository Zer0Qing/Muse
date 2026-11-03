package io.zer0.muse.miniphone.ui.shell

import io.zer0.muse.miniphone.api.MpCharacter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通讯录字母跳转的索引（小手机 P2b 第四批）。
 *
 * 列表是"标题行 + 若干人"混排，索引算错的表现很直观：
 * 点右侧字母 A 跳到 B 的人，或者某个空分组（只有标题）让后面全组的偏移集体错位。
 */
class ContactsIndexTest {

    private fun person(id: String, name: String) = MpCharacter(id = id, name = name)

    private fun section(key: String, vararg names: String) = MiniPhoneContactSection(
        key = key,
        contacts = names.mapIndexed { index, name -> person("$key-$index", name) },
    )

    @Test
    fun `标题行自己占一行 所以每组偏移要加一`() {
        val sections = listOf(
            section("A", "阿远", "小满"),
            section("B", "大白"),
        )

        val indices = contactHeaderIndices(sections)

        assertEquals(mapOf("A" to 0, "B" to 3), indices)
        // 0=标题A, 1=阿远, 2=小满, 3=标题B, 4=大白
        assertEquals(5, 1 + 2 + 1 + 1)
    }

    @Test
    fun `空分组仍然占一个标题行 不让后面的组集体错位`() {
        val sections = listOf(
            section("A"),
            section("B", "冰冰"),
        )

        val indices = contactHeaderIndices(sections)

        assertEquals("空组只有一个标题行", mapOf("A" to 0, "B" to 1), indices)
    }

    @Test
    fun `没有分组时索引表是空的`() {
        assertTrue(contactHeaderIndices(emptyList()).isEmpty())
    }

    @Test
    fun `最后一组的索引加它的行数正好等于总行数`() {
        val sections = listOf(
            section("A", "甲", "乙"),
            section("C"),
            section("D", "丁"),
        )

        val indices = contactHeaderIndices(sections)
        val lastKey = sections.last().key
        val totalRows = sections.sumOf { 1 + it.contacts.size }

        assertEquals("最后一个标题行 + 该行人数", totalRows, indices.getValue(lastKey) + 1 + sections.last().contacts.size)
        assertEquals(3, indices.size)
    }
}
