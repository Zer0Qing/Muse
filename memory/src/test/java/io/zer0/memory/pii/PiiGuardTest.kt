package io.zer0.memory.pii

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiGuardTest {

    @Test
    fun scrubMasksChineseNameAndAddress() {
        val result = PiiGuard.scrub("我叫张明，住成都市武侯区")
        assertFalse(result.cleaned.contains("张明"))
        assertFalse(result.cleaned.contains("成都市武侯区"))
        assertTrue(result.cleaned.contains("[REDACTED]"))
        assertTrue("name" in result.detected)
        assertTrue("detected=${result.detected} cleaned=${result.cleaned}", "address" in result.detected)
    }

    @Test
    fun scrubKeepsWhitelistedCommonWords() {
        val result = PiiGuard.scrub("我需要明白这个问题")
        assertTrue(result.cleaned.contains("明白"))
        assertTrue(result.cleaned.contains("问题"))
    }

    @Test
    fun scrubMasksPhoneEmailAndIdCardTableDriven() {
        val cases = listOf(
            PiiCase("联系 13800138000", listOf("13800138000"), listOf("phone")),
            PiiCase("联系 138 0013 8000", listOf("138 0013 8000"), listOf("phone")),
            PiiCase("邮箱 zhang@example.com", listOf("zhang@example.com"), listOf("email")),
            PiiCase("身份证 11010119900307751X", listOf("11010119900307751X"), listOf("id_card")),
            PiiCase("身份证 110101900307751", listOf("110101900307751"), listOf("id_card")),
        )
        for (case in cases) {
            val result = PiiGuard.scrub(case.input)
            assertTrue("input=${case.input} detected=${result.detected}", case.expectedTypes.all { it in result.detected })
            for (secret in case.secrets) {
                assertFalse("input=${case.input} cleaned=${result.cleaned}", result.cleaned.contains(secret))
            }
            assertTrue("input=${case.input} cleaned=${result.cleaned}", result.cleaned.contains("[REDACTED]"))
        }
    }

    private data class PiiCase(
        val input: String,
        val secrets: List<String>,
        val expectedTypes: List<String>,
    )

    // ---- A-18: 姓名规则改为「姓氏+称谓」与「称谓上下文」后,常见词不应被误抹 ----

    @Test
    fun scrubKeepsCommonWordsStartingWithSurnameChar() {
        // 文/王/李 等姓氏字开头的常见词不得被当姓名抹掉。
        val inputs = listOf(
            "中国传统文化", "这篇文章写得很好", "王国时代", "我看过一些文学作品",
            "需要掌握正确的方法", "关于这个地方的位置", "治疗需要时间", "程序运行正常",
            "经理负责管理团队", "逻辑很清晰", "精神饱满", "领域很广",
        )
        for (input in inputs) {
            val result = PiiGuard.scrub(input)
            assertTrue(
                "A-18 误伤: input=$input cleaned=${result.cleaned}",
                !result.cleaned.contains("[REDACTED]"),
            )
        }
    }

    @Test
    fun scrubKeepsWhitelistedA18Words() {
        // 白名单双保险:即使置于称谓上下文,命中白名单的词也应保留原文。
        val result = PiiGuard.scrub("我要管理这里的文化工作")
        assertTrue(result.cleaned.contains("管理"))
        assertTrue(result.cleaned.contains("文化"))
        assertTrue(result.cleaned.contains("工作"))
    }

    @Test
    fun scrubMasksSurnamePlusTitleAsName() {
        // 姓氏 + 称谓应被识别为姓名并抹除。
        val inputs = listOf(
            "王老师", "李医生", "张同学", "刘经理", "陈教授", "赵老板", "孙律师",
        )
        for (input in inputs) {
            val result = PiiGuard.scrub("我认识$input")
            assertFalse(
                "A-18 未抹除称谓姓名: input=$input cleaned=${result.cleaned}",
                result.cleaned.contains(input),
            )
            assertTrue(
                "A-18 detected 缺失 name: input=$input detected=${result.detected}",
                "name" in result.detected,
            )
        }
    }

    @Test
    fun scrubMasksPlainNameWithContextMarker() {
        // 依赖上下文: 叫/姓/名叫 后跟姓氏+名,应被识别为姓名并抹除。
        val inputs = listOf(
            "我叫张明，住成都市武侯区", "他姓李伟", "老师名叫王芳",
        )
        for (input in inputs) {
            val result = PiiGuard.scrub(input)
            assertTrue(
                "A-18 上下文姓名未抹除: input=$input cleaned=${result.cleaned}",
                result.cleaned.contains("[REDACTED]"),
            )
            assertTrue(
                "A-18 detected 缺失 name: input=$input detected=${result.detected}",
                "name" in result.detected,
            )
        }
    }

    // ---- A-20: CREDIT_CARD 对齐 app 版,覆盖 16-19 位数字卡号 ----

    @Test
    fun scrubMasksBankCard16To19Digits() {
        val cases = listOf(
            "卡号 6222021234567890",      // 16 位
            "卡号 62220212345678901",     // 17 位
            "卡号 622202123456789012",    // 18 位
            "卡号 6222021234567890123",   // 19 位
            "卡号 6222 0212 3456 7890",   // 4-4-4-4 空格分隔(16 位)
            "卡号 6222-0212-3456-7890123",// 4-4-4-分隔 + 尾段 7 位(19 位)
        )
        for (input in cases) {
            val result = PiiGuard.scrub(input)
            val card = input.removePrefix("卡号 ")
            assertFalse(
                "A-20 未抹除卡号: input=$input cleaned=${result.cleaned}",
                result.cleaned.contains(card),
            )
            assertTrue(
                "A-20 detected 缺失 credit_card: input=$input detected=${result.detected}",
                "credit_card" in result.detected,
            )
        }
    }

    @Test
    fun scrubKeepsNonCardDigitRuns() {
        // 15 位或 20+ 位连续数字不应被 CREDIT_CARD 误抹(边界保护)。
        val input = "编号 123456789012345(15位),另一个 12345678901234567890(20位)"
        val result = PiiGuard.scrub(input)
        // 15/20 位不能命中 CREDIT_CARD,因此不应出现 [REDACTED]。
        assertFalse(
            "A-20 边界误伤: input=$input cleaned=${result.cleaned}",
            result.cleaned.contains("[REDACTED]"),
        )
    }

    // ── 审查修复 (2.0 B-08): mask/unmask 占位符残留 ──────────────────────

    @Test
    fun unmaskRestoresKnownTokens() {
        val masked = PiiGuard.mask("我的电话是 13800138000,邮箱是 a@b.com")
        // 规则按 RuleKind.entries 顺序执行,EMAIL 先于 PHONE,故 phone 占位编号为 2;
        // 断言只校验占位符形态与还原结果,不依赖具体编号
        assertTrue("masked=${masked.masked}", masked.masked.contains("[PHONE_"))
        assertTrue("masked=${masked.masked}", masked.masked.contains("[EMAIL_1]"))

        val restored = PiiGuard.unmask(masked.masked, masked.map)
        assertTrue("restored=$restored", restored.contains("13800138000"))
        assertTrue("restored=$restored", restored.contains("a@b.com"))
        assertFalse("restored=$restored", restored.contains("[PHONE_"))
    }

    @Test
    fun unmaskStripsResidualTokensWhenLlmDropsOriginal() {
        // 两种残留场景:
        // 1) LLM 幻觉出映射中不存在的 token(如 [PHONE_2])→ 剥离;
        // 2) 映射中的 token 正常还原为原文(不受剥离影响)。
        val masked = PiiGuard.mask("电话 13800138000")
        val llmOutput = "用户说他改号了,[PHONE_1]已停用,新号见[PHONE_2]"
        val restored = PiiGuard.unmask(llmOutput, masked.map)
        assertTrue("B-08: 映射内 token 正常还原, restored=$restored", restored.contains("13800138000"))
        assertFalse("B-08: 幻觉残留占位符必须剥离, restored=$restored", restored.contains("[PHONE_2]"))
        assertFalse("B-08: 还原后不应残留占位符, restored=$restored", restored.contains("[PHONE_1]"))
    }

    @Test
    fun unmaskStripsUnexplainedTokensAcrossLabels() {
        // 无映射(或映射不含某 label)时,任何 mask 形态的占位符都应被剥离。
        val llmOutput = "联系方式 [EMAIL_2] 和 [ID_CARD_1] 都已失效"
        val restored = PiiGuard.unmask(llmOutput, emptyMap())
        assertFalse("B-08: 无映射时残留占位符同样剥离, restored=$restored", restored.contains("[EMAIL_2]"))
        assertFalse("B-08: 无映射时残留占位符同样剥离, restored=$restored", restored.contains("[ID_CARD_1]"))
    }
}
