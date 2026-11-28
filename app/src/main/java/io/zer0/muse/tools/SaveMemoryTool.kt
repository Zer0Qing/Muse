package io.zer0.muse.tools

object SaveMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "save_memory",
        description = "把一条事实存入用户长期记忆。当用户明确要求你记住某事,或陈述了重要的长期事实" +
            "(偏好、身份、目标、健康、重要日期)时使用。每次一句话;" +
            "闲聊与一次性任务细节不要存。",
        parameters = mapOf(
            "content" to "Required. The fact to remember — concise, one sentence, user's language.",
            "tags" to "Optional. Comma-separated keywords for later retrieval.",
            "importance" to "Optional. 0=normal, 1=important, 2=critical (health/safety). Default 0.",
            "category" to "Optional. preference/identity/event/relationship/goal/medical/other.",
        ),
        required = setOf("content"),
        category = "built-in",
        parameterTypes = mapOf("importance" to "integer"),
        parameterEnums = mapOf(
            "category" to listOf("preference", "identity", "event", "relationship", "goal", "medical", "other"),
        ),
        riskLevel = ToolRiskLevel.HIGH,
    )
}
