package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * P1-6: OAuth 认证配置。
 *
 * 用于支持 OAuth Device Flow(RFC 8628)或 Authorization Code Flow(PKCE)的
 * 供应商(如 xAI、OpenAI Codex 等),通过 OAuth 登录替代手动填 apiKey。
 *
 * - [deviceCodeUrl] 非空时优先走 Device Flow:POST 拿 device_code + user_code,
 *   弹窗显示 user_code,轮询 [tokenUrl] 直到拿到 access_token。
 * - [deviceCodeUrl] 为空时走 Authorization Code Flow:用系统浏览器打开
 *   [authorizeUrl],通过 Deep Link(`io.zer0.muse://oauth/callback`)接收 code,
 *   再用 code 换 access_token。
 *
 * @param authorizeUrl Authorization Code Flow 的授权页 URL
 * @param tokenUrl 两种 Flow 共用的 token 端点(换 access_token)
 * @param clientId OAuth 应用客户端 ID(必填,由供应商控制台申请)
 * @param clientSecret OAuth 应用客户端密钥(Confidential Client 才需要,
 *   移动端 Public Client 应留空,改用 PKCE)
 * @param redirectUri Authorization Code Flow 的回调 URI,
 *   默认 `io.zer0.muse://oauth/callback`(已在 AndroidManifest 注册)
 * @param scope 申请的权限范围(如 `chat.read chat.write`),空表示用供应商默认
 * @param deviceCodeUrl Device Flow 的 device_code 端点;为 null 表示不支持 Device Flow
 */
@Serializable
data class OAuthConfig(
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String = "io.zer0.muse://oauth/callback",
    val scope: String = "",
    val deviceCodeUrl: String? = null,
) {
    companion object {
        /**
         * xAI OAuth 预设。
         *
         * xAI 官方 OAuth 文档未稳定公开,这里用通用 OpenAI 兼容 device flow 模板:
         *  - authorizeUrl: `https://x.ai/oauth/authorize`(授权页)
         *  - tokenUrl:     `https://api.x.ai/v1/oauth/token`(换 token)
         *  - deviceCodeUrl:`https://api.x.ai/v1/oauth/device_code`(设备流程)
         *  - clientId:     留空,用户需在 xAI 控制台申请后填入
         *  - scope:        `chat.read chat.write`(xAI 通用聊天权限)
         *
         * 真实端点以 xAI 官方文档为准,如不符可在 ProviderEditPage 手动覆盖。
         */
        val XAI_PRESET = OAuthConfig(
            authorizeUrl = "https://x.ai/oauth/authorize",
            tokenUrl = "https://api.x.ai/v1/oauth/token",
            clientId = "",
            scope = "chat.read chat.write",
            deviceCodeUrl = "https://api.x.ai/v1/oauth/device_code",
        )

        /**
         * v1.0.6: xAI OAuth Responses 预设(对齐 既有实现 xai-oauth)。
         *
         * 与 [XAI_PRESET] 区别:此预设走 OpenAI Responses 协议(openai-responses),
         * baseUrl 指向 xAI OAuth 代理端点 https://cli-chat-proxy.grok.com,
         * 用户授权后拿到 access_token 调用 /v1/responses 端点访问 Grok 4.5+ 模型。
         *
         * 真实端点以 xAI 官方文档为准,如不符可在 ProviderEditPage 手动覆盖。
         */
        val XAI_OAUTH_PRESET = OAuthConfig(
            authorizeUrl = "https://x.ai/oauth/authorize",
            tokenUrl = "https://api.x.ai/v1/oauth/token",
            clientId = "",
            scope = "chat.read chat.write",
            deviceCodeUrl = "https://api.x.ai/v1/oauth/device_code",
        )

        /**
         * v2.x（用户反馈）: OpenAI Codex OAuth 预设 —— 按照参考实现（Codex CLI 同款协议）修正。
         *
         * ChatGPT 订阅账号登录（不走 API 计费，走订阅额度）：
         *  - 流程: Authorization Code Flow + PKCE(S256)，浏览器打开授权页后回跳 loopback；
         *  - authorizeUrl: `https://auth.openai.com/oauth/authorize`；
         *  - tokenUrl:     `https://auth.openai.com/oauth/token`（code 换 token 与刷新共用）；
         *  - clientId:     Codex CLI 公开 client_id（社区实现通用值）；
         *  - scope:        `openid profile email offline_access`（offline_access 才会发 refresh_token）；
         *  - 推理端点:     `https://chatgpt.com/backend-api/codex/responses`（见 PresetProviders.openaiCodex）；
         *  - 请求头:       除 Bearer 外还需 `ChatGPT-Account-ID`（从 id_token JWT 解析）。
         */
        val OPENAI_CODEX_PRESET = OAuthConfig(
            authorizeUrl = "https://auth.openai.com/oauth/authorize",
            tokenUrl = "https://auth.openai.com/oauth/token",
            clientId = "app_EMoamEEZ73f0CkXaXp7hrann",
            scope = "openid profile email offline_access",
            redirectUri = "http://localhost:1455/auth/callback",
        )

        /**
         * v1.134 P2-1: GitHub Copilot OAuth 预设(Device Flow)。
         *
         * GitHub 官方 OAuth Device Flow(RFC 8628),用户在浏览器输入 user_code 授权后,
         * 用 access_token 调用 Copilot API(https://api.githubcopilot.com):
         *  - authorizeUrl: `https://github.com/login/oauth/authorize`(Authorization Code Flow 备用)
         *  - tokenUrl:     `https://github.com/login/oauth/access_token`(换 token)
         *  - deviceCodeUrl:`https://github.com/login/device/code`(Device Flow 主路径)
         *  - clientId:     留空,用户填入 GitHub OAuth App 的 clientId
         *    (建议创建 OAuth App 而非 GitHub App,以兼容 Device Flow)
         *  - scope:        `read:user`(Copilot API 仅需基本用户身份,无需额外 scope)
         *
         * 调用 Copilot 时把 access_token 当作 apiKey,BASE_URL 指向
         * `https://api.githubcopilot.com`,Copilot 网关会代理到 OpenAI/GPT-4o 等模型。
         */
        val GITHUB_COPILOT_PRESET = OAuthConfig(
            authorizeUrl = "https://github.com/login/oauth/authorize",
            tokenUrl = "https://github.com/login/oauth/access_token",
            clientId = "",
            scope = "read:user",
            deviceCodeUrl = "https://github.com/login/device/code",
        )
    }
}
