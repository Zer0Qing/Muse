package io.zer0.muse.data.plugin.market

/**
 * 安装包内置的官方插件目录。
 *
 * 插件市场开箱即用：目录地址与目录信任根随应用一起发布，用户无需任何配置
 * （设置页仍保留覆盖入口，用于测试目录或应急切换，见 [PluginMarketSettings]）。
 *
 * 安全约定：
 *  - 这里的公钥是**目录签名信任根**，与设备上用户逐次确认的发行者信任根相互独立；
 *  - 内置信任根始终参与验签，用户配置无法移除或覆盖它，避免误操作让官方目录失效；
 *  - 目录内容本身仍需通过签名校验，内置的只是「信任哪把钥匙」，不是「信任内容」。
 */
object PluginMarketDefaults {

    /** 官方目录地址；必须是 https，且解析到公网地址（出口会做 DNS 固定与 SSRF 校验）。 */
    const val CATALOG_URL = "https://museai.ltd/muse-market/signed-catalog.json"

    /** 目录标识；已接受 sequence 按它分别记录，切换目录时不会互相覆盖。 */
    const val CATALOG_ID = "museai-official"

    /** 目录信任根 keyId，必须与签名目录 JSON 里的 `keyId` 逐字一致。 */
    const val CATALOG_ROOT_KEY_ID = "museai-catalog-1"

    /** 目录信任根公钥：P-256 的 X.509 SubjectPublicKeyInfo，标准 Base64 单行。 */
    const val CATALOG_ROOT_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7R5oDYokrWIXNgHa7ZUrREnSv2enqzd4aJ+O8MMrDMWHBom78kw1wfJX/hsZ1v34W9tlftp26fRq/26UOJ7alQ=="

    /** 公钥 SHA-256 指纹；仅用于自检与展示，便于人工核对内置值与发布端一致。 */
    const val CATALOG_ROOT_FINGERPRINT = "33b85c984cb4cb695ff93042523de7ecaabf2a4d937bcab7b9b2d33feb210d8d"

    /** 内置信任根集合；与 [CATALOG_ROOT_FINGERPRINT] 一一对应。 */
    val catalogRootKeys: Map<String, String> = mapOf(CATALOG_ROOT_KEY_ID to CATALOG_ROOT_PUBLIC_KEY)
}
