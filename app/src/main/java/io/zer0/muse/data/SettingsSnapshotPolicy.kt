package io.zer0.muse.data

/**
 * R-TEST-04: 备份设置快照的安全键策略。
 *
 * 备份恢复跨设备,依赖 SecureKeyStore 的敏感值无法解密,因此导出时只允许
 * 显式白名单键;任何包含 apiKey/token/password/secret 等敏感字段名的键一律排除。
 */
object SettingsSnapshotPolicy {

    val safeStringKeys = listOf(
        "active_provider_id", "selected_model_id", "tool_model_id",
        "compress_model_id",
        "theme_mode", "language", "theme_id", "dark_theme_id",
        "theme_schedule_json", "custom_themes_json", "font_size_scale",
        "prompt_templates_json", "user_profile_json", "chat_preferences_json",
        "memory_config_json", "notification_policy", "experiments_json",
        "share_template_json", "media_config_json", "default_search_engine",
        "proactive_message_json", "image_gen_config_json", "video_gen_config_json",
        "multi_agent_config_json", "rag_config_json", "chat_drafts_json",
        "task_routing_config_json", "model_profiles_json",
        "account_user_name", "account_login_method",
        "multi_agent_review_model",
    )

    val safeBooleanKeys = listOf(
        "memory_enabled", "dynamic_color", "onboarding_shown",
        "asr_tip_shown", "sticker_enabled", "experience_enabled",
        "keep_awake", "auto_launch", "biometric_enabled",
        "account_logged_in", "account_guest_mode",
        "pii_guard_enabled",
        "multi_agent_llm_review_enabled",
    )

    val safeIntKeys = listOf(
        "default_home_page", "sticker_send_probability",
    )

    val safeLongKeys = listOf(
        "account_login_at",
    )

    /** 敏感键名片段:即使误加入白名单也会被拒绝进入备份。 */
    private val sensitiveFragments = listOf(
        "api_key", "apikey", "token", "password", "secret", "jwt",
        "web_server", "mcp", "cloud_backup", "webdav", "s3", "asr_config", "pin",
    )

    /** 只有显式白名单键且不命中敏感片段才算安全。 */
    fun isSafeKey(rawKey: String): Boolean {
        val name = rawKey
            .removePrefix("bool:")
            .removePrefix("int:")
            .removePrefix("long:")
        val lower = name.lowercase()
        if (sensitiveFragments.any { lower.contains(it) }) return false
        return name in safeStringKeys || name in safeBooleanKeys || name in safeIntKeys || name in safeLongKeys
    }

    /** 只保留安全键;供测试与导出逻辑共用同一过滤语义。 */
    fun sanitize(raw: Map<String, String>): Map<String, String> = raw.filterKeys(::isSafeKey)
}
