package io.zer0.muse.proactive

/**
 * 主动消息通知上「这次不用」按钮的协议。
 *
 * 常量与解析放在这里而不是塞进接收器：接收器是个 Android 壳子，
 * 要测「换个 action 就不该记账」这种判断时，不该先造一个 [android.content.Intent]。
 */
object ProactiveDismissProtocol {

    /** 只能在应用内触达：广播由本应用的 PendingIntent 发出。 */
    const val ACTION_DISMISS = "io.zer0.muse.action.PROACTIVE_DISMISS"

    /** 要标记的结果账 id。 */
    const val EXTRA_OUTCOME_ID = "extra_outcome_id"

    /** 要顺手收起的通知 id（0 表示不处理）。 */
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    /**
     * 从广播里取出要标记的记录 id；不是本协议或没带 id 时返回 null。
     *
     * action 必须对得上：接收器按 action 过滤注册，但只要有人显式指定，
     * 广播仍然能被递进来，所以这里再判一次，避免误收别人的广播。
     */
    fun dismissedOutcomeId(action: String?, extra: String?): String? {
        if (action != ACTION_DISMISS) return null
        return extra?.takeIf { it.isNotBlank() }
    }
}
