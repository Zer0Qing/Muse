package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginVersion

/**
 * v2.7.x(PL3): 市场条目的版本视图与兼容推荐。
 *
 * 为什么这里只处理"已经能知道的"版本信息:
 *  目录条目(`PluginCatalogEntry`)现在只有单个 `version`,而
 *  `PluginCatalog.canonicalPayload` 是把**整份目录**用 `AppJson` 再序列化一次当作签名载荷,
 *  `AppJson.encodeDefaults = true`。这意味着给条目加任何字段(`versions[]`、`minAppVersion`…)
 *  都会改变已有目录的签名字节 —— 现存每一份已签名目录在客户端验签时都会失败。
 *  签名是「目录生成端 / signer 工具 / 客户端」三端要对账的东西,单改客户端不是"实现了一半",
 *  是把别人的东西改坏。所以多版本的市场侧字段属于 PL2(签名三端对账)的后续,
 *  这里先把**本机已知的版本**与**兼容判定**做出来。
 *
 * 兼容判定的依据也只有本机确实拿得到的:已安装插件的 manifest `minAppVersion`。
 * 未安装的插件**不猜**:界面上说"要求要在下载后才能确认",而不是显示一个看起来权威的
 * "兼容"。后者会让人以为安全然后装上一个跑不起来的东西。
 */
enum class MarketVersionAdvice {
    /** 本机没装,目录给了一个版本 —— 装它。 */
    InstallAvailable,

    /** 目录版本比本机新:有升级。 */
    UpgradeAvailable,

    /** 已经装了目录上那个版本。 */
    CurrentIsLatest,

    /** 目录版本比本机旧:这是降级(通常意味着目录还没更新,或本机走的是内测版本)。 */
    DowngradeAvailable,

    /** 已安装副本的 manifest 声明的最低 App 版本高于本机 App 版本。 */
    NeedsNewerApp,

    /** 版本字符串解析不了 —— 不许悄悄当成"兼容"或"最新"。 */
    UnknownVersion,
}

/** 市场一行该显示什么。[advice] 是结论,[reason] 是能直接说给用户看的一句(可为 null)。 */
data class MarketVersionView(
    val catalogVersion: String,
    val installedVersion: String?,
    val retainedVersions: List<String>,
    val advice: MarketVersionAdvice,
    val reason: String?,
)

object PluginMarketVersions {

    /**
     * 纯函数:由目录版本、本机已装版本、本机留档版本与 App 版本得出建议。
     *
     * [minAppVersion] 只应传**已安装副本**的 manifest 值;不知道就传 null,
     * 由调用方在界面上表达"未知",这里不拿 null 去推断任何兼容性结论。
     */
    fun view(
        catalogVersion: String,
        installedVersion: String?,
        retainedVersions: List<String> = emptyList(),
        appVersion: String,
        minAppVersion: String? = null,
    ): MarketVersionView {
        val catalog = PluginVersion.parse(catalogVersion)
        val installed = installedVersion?.let(PluginVersion::parse)
        // App 版本比对与 PluginVersionPolicy 同口径:哨兵开发版不卡门槛。
        if (minAppVersion != null && minAppVersion != DEV_SENTINEL && appVersion != DEV_SENTINEL) {
            val required = PluginVersion.parse(minAppVersion)
            val current = PluginVersion.parse(appVersion)
            if (required != null && current != null && required > current) {
                return MarketVersionView(
                    catalogVersion = catalogVersion,
                    installedVersion = installedVersion,
                    retainedVersions = retainedVersions,
                    advice = MarketVersionAdvice.NeedsNewerApp,
                    reason = "这个插件要求 App 不低于 $minAppVersion,本机是 $appVersion",
                )
            }
        }
        if (installedVersion.isNullOrBlank()) {
            return MarketVersionView(
                catalogVersion = catalogVersion,
                installedVersion = null,
                retainedVersions = retainedVersions,
                advice = if (catalog == null) MarketVersionAdvice.UnknownVersion else MarketVersionAdvice.InstallAvailable,
                reason = if (catalog == null) "目录版本号无法识别:$catalogVersion" else null,
            )
        }
        if (catalog == null || installed == null) {
            return MarketVersionView(
                catalogVersion = catalogVersion,
                installedVersion = installedVersion,
                retainedVersions = retainedVersions,
                advice = MarketVersionAdvice.UnknownVersion,
                reason = "版本号无法识别(目录 $catalogVersion / 本机 $installedVersion)",
            )
        }
        return when {
            catalog == installed -> MarketVersionView(
                catalogVersion,
                installedVersion,
                retainedVersions,
                MarketVersionAdvice.CurrentIsLatest,
                null,
            )

            catalog > installed -> MarketVersionView(
                catalogVersion,
                installedVersion,
                retainedVersions,
                MarketVersionAdvice.UpgradeAvailable,
                "本机 $installedVersion 可升级到 $catalogVersion",
            )

            else -> MarketVersionView(
                catalogVersion,
                installedVersion,
                retainedVersions,
                MarketVersionAdvice.DowngradeAvailable,
                "目录版本 $catalogVersion 比本机 $installedVersion 旧,装上属于降级",
            )
        }
    }

    /**
     * 本机留档里"还能切回去"的版本(排除当前正在用的那个)。
     *
     * 只算列表,不做决定:切版本走既有的回滚通道,那里才有签名与校验的完整门槛。
     */
    fun switchableVersions(installedVersion: String?, retainedVersions: List<String>): List<String> =
        retainedVersions.filter { it.isNotBlank() && it != installedVersion }.distinct().sortedWith(
            compareByDescending { PluginVersion.parse(it) ?: PluginVersion(0, 0, 0) },
        )

    private const val DEV_SENTINEL = "0.0.0-dev"
}
