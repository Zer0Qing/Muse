package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 网页快照合同（对比清单 P2-01「网页冻结正文」）。
 *
 * 浏览器工具返回的是**某一刻**抓到的正文。没有时间戳时模型会把它当"当前页面"，
 * 于是在用户已经翻页或内容更新之后仍然基于旧正文作答 —— 这类错误很难被察觉。
 */
class WebSnapshotContractTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-11-02T13:05:33Z */
    private val fetchedAt = 1793624733000L

    @Test
    fun `the header carries url and fetch time`() {
        val header = WebSnapshotContract.header("https://example.com/a", fetchedAt, utc)

        assertTrue("要写清来源: $header", header.contains("url=https://example.com/a"))
        assertTrue("要写清时刻: $header", header.contains("2026-11-02 13:05:33"))
    }

    @Test
    fun `the header states that this is a snapshot`() {
        val header = WebSnapshotContract.header("https://example.com", fetchedAt, utc)

        assertTrue("必须说明是快照: $header", header.contains("网页快照"))
        assertTrue("要提示页面可能已变化: $header", header.contains("可能已变化"))
        assertTrue("要给出补救办法: $header", header.contains("重新抓取"))
    }

    @Test
    fun `a missing url is reported as unknown instead of being invented`() {
        val header = WebSnapshotContract.header(null, fetchedAt, utc)

        assertTrue("url 未知就说未知: $header", header.contains("url=未知"))
        assertFalse("不能凭空造地址: $header", header.contains("http"))
    }

    @Test
    fun `a blank url is treated like a missing one`() {
        val header = WebSnapshotContract.header("   ", fetchedAt, utc)

        assertTrue(header.contains("url=未知"))
    }

    @Test
    fun `render puts the snapshot header before the body`() {
        val rendered = WebSnapshotContract.render("https://example.com", fetchedAt, "正文内容", utc)

        assertTrue(rendered.startsWith("--- 网页快照"))
        assertTrue(rendered.endsWith("正文内容"))
        assertEquals(2, rendered.lines().size)
    }

    @Test
    fun `the timestamp follows the provided zone`() {
        val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

        val header = WebSnapshotContract.header("https://example.com", fetchedAt, shanghai)

        assertTrue("同一时刻在东八区应显示 21:05:33: $header", header.contains("2026-11-02 21:05:33"))
    }
}
