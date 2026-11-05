package io.zer0.muse.channel

/**
 * v2.6.23(C1): 渠道对话目标策略的判定(纯函数,零 IO)。
 *
 * 三种模式:
 *  - open:全部放行(默认,行为与加此功能之前一致);
 *  - allowlist:只有名单内的群/用户可对话;名单为空 = 谁都不行
 *    (用户选了"只允许"却没填目标时,宁可不说话也不对所有人开放);
 *  - denylist:名单内禁言,其余放行。
 *
 * 入站自动回复与出站发送必须走同一个函数:两处各写一份判定的历史教训
 * 是"入站拦了、relay 工具还能发出去"。target 一律用平台的会话/用户 id
 * (入站即 Inbound.from,出站即 targetOverride ?: config.targetId)。
 */
object ChannelPolicy {

    const val MODE_OPEN = "open"
    const val MODE_ALLOWLIST = "allowlist"
    const val MODE_DENYLIST = "denylist"

    /** 该渠道是否允许与 [targetId] 这个会话/用户对话。 */
    fun allows(config: ChannelConfig, targetId: String): Boolean = when (config.policyMode) {
        MODE_ALLOWLIST -> config.policyTargets.any { it == targetId }
        MODE_DENYLIST -> config.policyTargets.none { it == targetId }
        // 未知取值按 open 处理:配置损坏不该演变成"全面禁言"这种最难排查的形态。
        else -> true
    }

    /** 给 UI/日志的可读拒绝原因(不写具体 id 之外信息,防把名单抄进日志)。 */
    fun blockReason(config: ChannelConfig, targetId: String): String = "渠道策略(${config.policyMode})不允许与「${targetId.take(16)}」对话"

    /** 名单规范化:去空、去重、去首尾空白。录入以逗号/换行分隔。 */
    fun normalizeTargets(raw: String): List<String> = raw.split(',', '\n', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
