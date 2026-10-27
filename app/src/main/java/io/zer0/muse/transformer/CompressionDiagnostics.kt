package io.zer0.muse.transformer

/**
 * 上下文压缩的诊断文案。
 *
 * ## 为什么单独放一个对象
 *
 * 这些字符串只进日志（开发者排查用），既不是界面文案、也不是发给模型的内容。
 * 但 `ui/` 目录有"禁止硬编码中文字面量"的护栏（`check_hardcoded_cjk.py`），
 * 日志文案留在 ViewModel 里会触发该护栏、逼着我们去放宽基线——那是用降低约束来换取方便。
 * 收敛到这里既过护栏，也保持既有先例（模型侧/诊断侧文本与 `ui/` 扫描范围解耦）。
 */
internal object CompressionDiagnostics {

    /** 压缩完成、检查点已写入。 */
    fun checkpointSaved(ratio: String, covered: Int, before: Int, after: Int): String =
        "Auto-compress 已落检查点: ratio=$ratio, $covered 条并入摘要($before → $after 条)"

    /** 压缩跑完却没有覆盖记录（水位线为空），本轮不写检查点。 */
    const val NO_COVERAGE_RECORD: String = "Auto-compress 完成但无覆盖记录(水位线为空),不写检查点"

    /** 摘要不可用，已改用零请求本地重建并落检查点。 */
    fun localRebuildSaved(covered: Int, before: Int): String =
        "summary unavailable; local rebuild checkpoint saved: covered=$covered of $before"

    /** 本地重建也拿不到可摘录内容，本轮放弃（保持全量历史）。 */
    const val LOCAL_REBUILD_EMPTY: String = "local rebuild produced nothing; keeping full history"

    /** 读取当前会话检查点失败，按"无检查点"继续。 */
    fun readCheckpointFailed(message: String?): String = "读取会话检查点失败(按无检查点处理): $message"

    /** 检查点写入失败，本轮按全量历史继续。 */
    fun writeCheckpointFailed(message: String?): String = "会话检查点写入失败(本轮按全量历史继续): $message"

    /** 刷新检查点覆盖集合失败。 */
    fun refreshCoveredIdsFailed(message: String?): String = "刷新检查点覆盖集合失败: $message"

    /** 自动压缩结果已过期（期间用户切走了会话），丢弃。 */
    fun staleAutoCompressResult(sessionId: String): String = "Auto-compress 结果已过期(会话已切换),丢弃: $sessionId"
}
