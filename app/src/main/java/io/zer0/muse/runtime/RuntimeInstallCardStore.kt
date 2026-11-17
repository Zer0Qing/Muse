package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.data.runtime.RuntimeInstallCardEntity
import io.zer0.muse.data.runtime.RuntimeInstallCardState
import io.zer0.muse.data.session.MuseDb

/**
 * 运行环境安装卡的落库入口。
 *
 * UI 只问三件事：这张卡现在是什么状态、用户有没有说过"以后再说"、装完该不该消失。
 * 状态由 [RuntimeInstallStatus] 的真实上报推导，绝不自己猜进度。
 */
object RuntimeInstallCardStore {

    private const val TAG = "RuntimeInstallCardStore"

    /** 卡片随消息持久化后，用户选择的"以后再说"必须活过进程重启。 */
    suspend fun markDismissed(context: Context, messageId: String) {
        runCatching {
            MuseDb.get(context).runtimeInstallCardDao().markDismissed(messageId, System.currentTimeMillis())
        }.onFailure { Logger.w(TAG, "标记以后再说失败: ${it.message}") }
    }

    /** 该条消息对应的卡片是否已被收起。 */
    suspend fun isDismissed(context: Context, messageId: String): Boolean =
        runCatching { MuseDb.get(context).runtimeInstallCardDao().findByMessageId(messageId)?.dismissed == true }
            .getOrDefault(false)

    /** 运行环境就绪后卡片失去意义，删掉而不是留一条 READY 历史行。 */
    suspend fun clear(context: Context, messageId: String) {
        runCatching {
            MuseDb.get(context).runtimeInstallCardDao().deleteByMessageId(messageId)
        }.onFailure { Logger.w(TAG, "清理安装卡失败: ${it.message}") }
    }

    /**
     * 按当前真实进度写卡。已存在的 `dismissed` 一律保留 ——
     * 用户说过"以后再说"之后，进度回调不许再把卡片推回他眼前。
     */
    suspend fun record(
        context: Context,
        messageId: String,
        runtimeId: RuntimeId,
        version: String,
        progress: RuntimeInstaller.Progress?,
        error: String?,
        ready: Boolean,
    ) {
        val dao = MuseDb.get(context).runtimeInstallCardDao()
        val existing = runCatching { dao.findByMessageId(messageId) }
            .onFailure { Logger.w(TAG, "读取安装卡失败: ${it.message}") }
            .getOrNull()
        val downloaded = (progress as? RuntimeInstaller.Progress.Downloading)?.percent() ?: 0
        val card = RuntimeInstallCardEntity(
            messageId = messageId,
            runtimeId = runtimeId,
            version = version,
            state = stateOf(progress = progress, error = error, ready = ready),
            progress = downloaded,
            errorKind = errorKindOf(error),
            dismissed = existing?.dismissed ?: false,
            updatedAt = System.currentTimeMillis(),
        )
        runCatching { dao.upsert(card) }
            .onFailure { Logger.w(TAG, "写入安装卡失败: ${it.message}") }
    }

    /**
     * 纯函数：把上报的进度折叠成卡片状态。
     * 优先级 ready > error > 进行中阶段 > 未开始 —— 装成之后不该再显示"失败"。
     */
    internal fun stateOf(
        progress: RuntimeInstaller.Progress?,
        error: String?,
        ready: Boolean,
    ): RuntimeInstallCardState = when {
        ready -> RuntimeInstallCardState.READY
        error != null -> RuntimeInstallCardState.FAILED
        progress is RuntimeInstaller.Progress.Downloading -> RuntimeInstallCardState.DOWNLOADING
        progress is RuntimeInstaller.Progress.Extracting -> RuntimeInstallCardState.EXTRACTING
        progress is RuntimeInstaller.Progress.Verifying ||
            progress is RuntimeInstaller.Progress.SelfCheck -> RuntimeInstallCardState.VERIFYING
        progress is RuntimeInstaller.Progress.Done -> RuntimeInstallCardState.READY
        else -> RuntimeInstallCardState.OFFERED
    }

    /**
     * 纯函数：把安装失败原因归成宿主能判定的短码。
     * 文案会随语言变，短码不会 —— 卡片与诊断都只认短码。
     */
    internal fun errorKindOf(error: String?): String? = when {
        error.isNullOrBlank() -> null
        error.contains("存储空间不够") || error.contains("storage") -> "no_space"
        error.contains("解压失败") || error.contains("tar") -> "extract_failed"
        error.contains("校验") || error.contains("sha256") -> "checksum_failed"
        error.contains("node -v") || error.contains("自检") -> "self_check_failed"
        error.contains("下载") || error.contains("download") -> "download_failed"
        else -> "unknown"
    }

    /** 下载进度百分比；总长未知时返回 0（UI 显示不确定进度条）。 */
    private fun RuntimeInstaller.Progress.Downloading.percent(): Int =
        if (total > 0L) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
}
