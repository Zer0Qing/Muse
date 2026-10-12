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
    /**
     * v2.6.0: Codex 专用设备码流程开关。
     *
     * true 时走 OpenAI Codex 特有的设备授权流程(官方 codex CLI `--device-auth` 同款):
     *  - POST {base}/api/accounts/deviceauth/usercode (JSON, body {client_id})
     *  - 轮询 POST {base}/api/accounts/deviceauth/token (JSON, body {device_auth_id, user_code})
     *  - POST {base}/oauth/token 用返回的 authorization_code + code_verifier 换 token
     *    (redirect_uri 固定为 https://auth.openai.com/deviceauth/callback)
     * 全程不需要 localhost 回调 —— 避免用户代理把回环地址吞进隧道导致连接超时。
     */
    val codexDeviceAuth: Boolean = false,
    val scope: String = "",
    val deviceCodeUrl: String? = null,
) {
    companion object {
        /**
         * xAI OAuth 预设。
         *
         * v2.6.0（用户反馈：一键登录全部不可用）: 按官方/社区实现修正。
         *
         * 原实现端点与 clientId 均为占位(x.ai/oauth、clientId 空) —— 空 clientId 会让
         * Device Flow 直接判“不可用”。实际 xAI 订阅登录：
         *  - 授权服务器: `https://auth.x.ai`（非 x.ai），支持 OIDC 发现
         *  - device code: `https://auth.x.ai/oauth2/device/code`（RFC 8628）
         *  - token:      `https://auth.x.ai/oauth2/token`
         *  - clientId:    Grok CLI 公开 device-flow client（与 oh-my-pi/hermes-agent 同源，非机密）
         *  - scope:       `openid profile email offline_access grok-cli:access`
         */
        val XAI_PRESET = OAuthConfig(
            authorizeUrl = "https://auth.x.ai/oauth2/authorize",
            tokenUrl = "https://auth.x.ai/oauth2/token",
            clientId = "b1a00492-073a-47ea-816f-4c329264a828",
            scope = "openid profile email offline_access grok-cli:access",
            deviceCodeUrl = "https://auth.x.ai/oauth2/device/code",
        )

        /**
         * v1.0.6: xAI OAuth Responses 预设(对齐 既有实现 xai-oauth)。
         *
         * 与 [XAI_PRESET] 区别:此预设走 OpenAI Responses 协议(openai-responses),
         * baseUrl 指向 xAI OAuth 代理端点 https://cli-chat-proxy.grok.com,
         * 用户授权后拿到 access_token 调用 /v1/responses 端点访问 Grok 4.5+ 模型。
         *
         * v2.6.0: 同步修正为 auth.x.ai 端点 + 公开 client_id（同 [XAI_PRESET]）。
         */
        val XAI_OAUTH_PRESET = OAuthConfig(
            authorizeUrl = "https://auth.x.ai/oauth2/authorize",
            tokenUrl = "https://auth.x.ai/oauth2/token",
            clientId = "b1a00492-073a-47ea-816f-4c329264a828",
            scope = "openid profile email offline_access grok-cli:access",
            deviceCodeUrl = "https://auth.x.ai/oauth2/device/code",
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
            // v2.6.0: 标记 Codex 专用设备码流程 —— Codex 不用标准 RFC 8628:
            // 设备码端点走 /api/accounts/deviceauth/*(JSON + 两段式),
            // token 交换 redirect_uri 固定 https://auth.openai.com/deviceauth/callback。
            // 走这条路径不需要 localhost 回调,彻底避开“代理吞回环→超时”问题。
            codexDeviceAuth = true,
        )

        /**
         * v1.134 P2-1: GitHub Copilot OAuth 预设(Device Flow)。
         *
         * GitHub 官方 OAuth Device Flow(RFC 8628),用户在浏览器输入 user_code 授权后,
         * 用 access_token 调用 Copilot API(https://api.githubcopilot.com):
         *  - authorizeUrl: `https://github.com/login/oauth/authorize`(Authorization Code Flow 备用)
         *  - tokenUrl:     `https://github.com/login/oauth/access_token`(换 token)
         *  - deviceCodeUrl:`https://github.com/login/device/code`(Device Flow 主路径)
         *  - clientId:     GitHub Copilot 公开 OAuth client（VS Code / Copilot CLI 及
         *                  大量第三方工具通用，非机密；v2.6.0 修正：原为空导致直接不可用）
         *  - scope:        `read:user`
         *
         * 调用 Copilot 时把 access_token 当作 apiKey,BASE_URL 指向
         * `https://api.githubcopilot.com`,Copilot 网关会代理到 OpenAI/GPT-4o 等模型。
         */
        val GITHUB_COPILOT_PRESET = OAuthConfig(
            authorizeUrl = "https://github.com/login/oauth/authorize",
            tokenUrl = "https://github.com/login/oauth/access_token",
            clientId = "Iv1.b507a08c87ecfe98",
            scope = "read:user",
            deviceCodeUrl = "https://github.com/login/device/code",
        )
    }
}
