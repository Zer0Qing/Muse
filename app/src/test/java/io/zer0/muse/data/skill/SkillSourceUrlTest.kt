package io.zer0.muse.data.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能来源地址解析（对比清单 P1-09）。
 *
 * 只认 github.com 不是偷懒：让应用去下载任意 URL 等于把它变成内网探测器。
 */
class SkillSourceUrlTest {

    @Test
    fun `a plain repository url resolves to its default branch archive`() {
        val parsed = SkillSourceUrl.parse("https://github.com/owner/repo") as SkillSourceUrl.Parsed.GitHub

        assertEquals("owner", parsed.owner)
        assertEquals("repo", parsed.repo)
        assertEquals(SkillSourceUrl.DEFAULT_REF, parsed.ref)
        assertEquals("https://github.com/owner/repo/archive/HEAD.zip", parsed.archiveUrl)
    }

    @Test
    fun `trailing slash and dot git suffix are tolerated`() {
        val withSlash = SkillSourceUrl.parse("https://github.com/owner/repo/") as SkillSourceUrl.Parsed.GitHub
        val withGit = SkillSourceUrl.parse("https://github.com/owner/repo.git") as SkillSourceUrl.Parsed.GitHub

        assertEquals("repo", withSlash.repo)
        assertEquals("repo", withGit.repo)
    }

    @Test
    fun `a tree url pins the branch`() {
        val parsed = SkillSourceUrl.parse("https://github.com/owner/repo/tree/dev") as SkillSourceUrl.Parsed.GitHub

        assertEquals("dev", parsed.ref)
        assertEquals("https://github.com/owner/repo/archive/dev.zip", parsed.archiveUrl)
    }

    @Test
    fun `a branch with a slash is preserved`() {
        val parsed = SkillSourceUrl.parse("https://github.com/owner/repo/tree/feature/x") as SkillSourceUrl.Parsed.GitHub

        assertEquals("feature/x", parsed.ref)
    }

    @Test
    fun `non https urls are refused`() {
        listOf("http://github.com/owner/repo", "ftp://github.com/owner/repo", "github.com/owner/repo").forEach { raw ->
            assertTrue("必须拒绝: $raw", SkillSourceUrl.parse(raw) is SkillSourceUrl.Parsed.Invalid)
        }
    }

    @Test
    fun `other hosts are refused with a readable reason`() {
        val parsed = SkillSourceUrl.parse("https://example.com/owner/repo") as SkillSourceUrl.Parsed.Invalid

        assertTrue(parsed.reason.contains("github.com"))
    }

    @Test
    fun `a url without a repository name is refused`() {
        assertTrue(SkillSourceUrl.parse("https://github.com/owner") is SkillSourceUrl.Parsed.Invalid)
    }

    @Test
    fun `illegal characters in owner or repo are refused`() {
        listOf(
            "https://github.com/ow ner/repo",
            "https://github.com/owner/re po",
            "https://github.com/owner/repo/../../etc",
        ).forEach { raw ->
            assertTrue("必须拒绝: $raw", SkillSourceUrl.parse(raw) is SkillSourceUrl.Parsed.Invalid)
        }
    }

    @Test
    fun `a ref containing dot dot is refused`() {
        assertTrue(
            SkillSourceUrl.parse("https://github.com/owner/repo/tree/../evil") is SkillSourceUrl.Parsed.Invalid,
        )
    }
}
