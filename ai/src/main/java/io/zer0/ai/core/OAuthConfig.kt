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
         * v1.0.6: xAI OAuth Responses 预设(对齐 openhanako xai-oauth)。
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
         * v1.134 P2-1: OpenAI Codex OAuth 预设(Device Flow)。
         *
         * 对齐 Codex CLI 官方登录流程(auth.openai.com 域名),
         * 用户在 ChatGPT 网页扫码/输入 user_code 完成授权:
         *  - authorizeUrl: `https://auth.openai.com/oauth/authorize`(授权页,Authorization Code Flow 备用)
         *  - tokenUrl:     `https://auth.openai.com/oauth/token`(换 token,两个 Flow 共用)
         *  - deviceCodeUrl:`https://auth.openai.com/oauth/device/code`(Device Flow 主路径)
         *  - clientId:     留空,用户填入 Codex CLI 应用 clientId(由 OpenAI 控制台申请)
         *  - scope:        `openai/v1`(Codex 调用 /v1/responses 等接口所需)
         *
         * 真实 clientId 见 Codex CLI 源码或 OpenAI Platform 控制台;
         * 如不符可在 ProviderEditPage 手动覆盖。
         */
        val OPENAI_CODEX_PRESET = OAuthConfig(
            authorizeUrl = "https://auth.openai.com/oauth/authorize",
            tokenUrl = "https://auth.openai.com/oauth/token",
            clientId = "",
            scope = "openai/v1",
            deviceCodeUrl = "https://auth.openai.com/oauth/device/code",
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
