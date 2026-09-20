package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginManager
import java.io.File
import java.security.MessageDigest

/**
 * 市场安装计划的确认提交入口。
 *
 * 用户确认预览后，本类执行最后一轮校验再委托 [PluginManager] 原子提交：
 *  1. 计划未过期（防“确认很久以前的旧包”）；
 *  2. staged 包仍存在且 zip 摘要与计划绑定的目录摘要一致（防确认前替换文件）；
 *  3. [PluginManager.installConfirmedFromFile] 重新解析包、重算内容摘要并与预览比对，
 *     未知发行者仍必须显式 `trustPublisher = true`，未签名/篡改包一律拒绝。
 *
 * 任何一步失败都不会写入插件目录；失败只发生在 staging（应用私有缓存）。
 */
internal class PluginMarketInstaller(
    private val pluginManager: PluginManager,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun confirmAndInstall(
        plan: PluginInstallPlan,
        trustPublisher: Boolean,
    ): Result<PluginManager.InstalledPlugin> {
        if (plan.isExpired(nowEpochMs())) {
            return Result.failure(IllegalStateException("安装确认已过期，请重新下载"))
        }
        if (!plan.stagedFile.isFile) {
            return Result.failure(IllegalStateException("插件包不存在，请重新下载"))
        }
        val actual = sha256Hex(plan.stagedFile)
        if (!actual.equals(plan.artifactSha256, ignoreCase = true)) {
            return Result.failure(IllegalStateException("插件包在确认后发生变化，请重新下载"))
        }
        return pluginManager.installConfirmedFromFile(
            file = plan.stagedFile,
            expectedPreview = plan.preview,
            trustPublisher = trustPublisher,
        )
    }

    /** 删除安装计划持有的 staged 包；只允许删除下载命名规则内的文件。 */
    fun discard(plan: PluginInstallPlan) {
        val staged = plan.stagedFile
        if (staged.name != PluginDownloadClient.stagedFileName(plan.entry)) return
        runCatching { if (staged.isFile) staged.delete() }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
