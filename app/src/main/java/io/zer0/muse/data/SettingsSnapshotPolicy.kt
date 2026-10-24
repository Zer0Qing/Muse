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
        "share_template_json",
        "proactive_message_json", "image_gen_config_json", "video_gen_config_json",
        "multi_agent_config_json", "rag_config_json", "chat_drafts_json",
        "task_routing_config_json", "model_profiles_json",
        "account_user_name", "account_login_method",
        "multi_agent_review_model",
        // v2.6.7 fix (AUDIT F9): 用户偏好（“以后别…”沉淀的硬约束）是用户资产，
        // 不进备份则换机后全丢。该 key 不含敏感字段名。
        "user_preferences",
        // P0-2: media_config_json 已从快照移除 —— MediaConfig 含 ttsApiKey 敏感凭据;
        // 即使 ttsApiKey 已走 SecureKeyStore 加密,设备绑定密钥也无法跨设备解密,
        // 导出会携带无用的密文并造成误用,故整体排除(与其他含密钥配置同策略)。
    )

    val safeBooleanKeys = listOf(
        "memory_enabled", "dynamic_color", "onboarding_shown",
        "asr_tip_shown", "sticker_enabled", "experience_enabled",
        "keep_awake", "auto_launch",
        "account_logged_in", "account_guest_mode",
        "pii_guard_enabled",
        "multi_agent_llm_review_enabled",
    )

    val safeIntKeys = listOf(
        "default_home_page",
        "sticker_send_probability",
    )

    val safeLongKeys = listOf(
        "account_login_at",
    )

    /**
     * v2.6.9 fix (AUDIT §11.3-7): stringSet 类型设置的备份白名单。
     *
     * 此前快照只遍历 string/boolean/int/long 四类,stringSet 一个都没导出,
     * 于是这两个用户的"收藏"永远进不了备份 —— 换机即丢,且没有任何提示。
     *
     * 纳入标准与其它类型一致:内容是用户资产,不含凭据,跨设备可读。
     *  - moment_favorite_ids: 朋友圈收藏的动态 id(UUID);
     *  - mini_album_favorite_image_ids: 相册收藏的图片 id(uri hashCode 的十进制串)。
     *
     * 被刻意排除的 stringSet 见各自字段注释(daily_summary_* 是调度去重状态,
     * miniphone_hidden_apps / mini_album_hidden_image_ids 是"隐藏"这类设备侧视图状态)。
     */
    val safeStringSetKeys = listOf(
        "moment_favorite_ids",
        "mini_album_favorite_image_ids",
    )

    /**
     * stringSet 在快照 Map 里的键前缀。
     *
     * 值必须编码成 String(快照是 Map<String, String>),用 JSON 数组保留
     * "空集合"与"没有这个键"的区别 —— 逗号拼接会把空集合压成空串,
     * 恢复时就分不清该写空集还是该保留原值。
     */
    const val STRING_SET_PREFIX = "stringSet:"

    /** v2.6.0: 含密钥白名单 —— 仅当用户显式勾选「包含供应商密钥」时并入导出。
     *  providers_json 内含各供应商 apiKey / oauth token / customHeaders（可能含账号 ID）等。
     *  注意：这是可直接使用的明文凭据，备份文件务必妥善保管。 */
    val secretStringKeys = listOf(
        "providers_json",
    )

    /** 敏感键名片段:即使误加入白名单也会被拒绝进入备份。 */
    private val sensitiveFragments = listOf(
        "api_key", "apikey", "token", "password", "secret", "jwt",
        "web_server", "mcp", "cloud_backup", "webdav", "s3", "asr_config", "pin",
    )

    /** 快照 Map 里的类型前缀。string 类型不带前缀(历史格式,存量备份兼容)。 */
    private val typePrefixes = listOf("bool:", "int:", "long:", STRING_SET_PREFIX)

    /**
     * 只有显式白名单键且不命中敏感片段才算安全。
     *
     * 判定是**按类型分桶**的:`bool:` 前缀的键只会在 [safeBooleanKeys] 里找,
     * `stringSet:` 只在 [safeStringSetKeys] 里找。若只看键名不看类型,
     * 一份被篡改的备份就能用 `stringSet:theme_mode` 这种组合往 DataStore 里
     * 塞一个“同名的另一种类型”,虽然不影响读取(DataStore 的 key 含类型),
     * 但会让备份产物里出现永远不该存在的条目。
     */
    fun isSafeKey(rawKey: String): Boolean = isSafe(rawKey, allowSecretStringKeys = false)

    private fun isSafe(rawKey: String, allowSecretStringKeys: Boolean): Boolean {
        val prefix = typePrefixes.firstOrNull { rawKey.startsWith(it) } ?: ""
        val name = rawKey.removePrefix(prefix)
        // 用户显式勾选「包含密钥」时 providers_json 直接放行 —— 它是用户已知情的
        // 选择,不应再被敏感片段规则拦下。
        val explicitlyAllowedSecret =
            allowSecretStringKeys && prefix.isEmpty() && name in secretStringKeys
        val whitelisted = when (prefix) {
            "bool:" -> name in safeBooleanKeys
            "int:" -> name in safeIntKeys
            "long:" -> name in safeLongKeys
            STRING_SET_PREFIX -> name in safeStringSetKeys
            else -> name in safeStringKeys
        }
        return name.isNotEmpty() &&
            (explicitlyAllowedSecret || (whitelisted && !isSensitiveName(name)))
    }

    private fun isSensitiveName(name: String): Boolean {
        val lower = name.lowercase()
        return sensitiveFragments.any { lower.contains(it) }
    }

    /**
     * v2.6.0: 含密钥导出侧过滤 —— 基础白名单 + secretStringKeys（providers_json）。
     * 与 [isSafeKey] 不同：providers_json 命中敏感片段（含 apikey）会被 isSafeKey 拒绝，
     * 但用户显式勾选后应当允许，因此这里单独判定。
     */
    fun isSafeKeyWithSecrets(rawKey: String): Boolean = isSafe(rawKey, allowSecretStringKeys = true)

    /** 只保留安全键;供测试与导出逻辑共用同一过滤语义。 */
    fun sanitize(raw: Map<String, String>): Map<String, String> = raw.filterKeys(::isSafeKey)

    /** v2.6.0: 含密钥恢复侧过滤 —— 允许 providers_json 写回。 */
    fun sanitizeWithSecrets(raw: Map<String, String>): Map<String, String> = raw.filterKeys(::isSafeKeyWithSecrets)
}
