package io.zer0.muse.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.zer0.common.Logger
import io.zer0.muse.mcp.McpRegistry
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * P1-6: OAuth 回调空 Activity — 仅用于接收 `io.zer0.muse://oauth/callback` Deep Link。
 *
 * 工作流程:
 *  1. 浏览器完成 Authorization Code Flow 授权后,跳转到 redirectUri
 *     (`io.zer0.muse://oauth/callback?code=xxx&state=yyy`)
 *  2. 系统匹配到本 Activity 的 intent-filter,启动本 Activity
 *  3. [onCreate] / [onNewIntent] 解析 query 中的 code + state,
 *     调用 [OAuthManager.completeAuthorizationCodeFlow] 完成 Flow
 *  4. 立即 [finish],用户看到的是浏览器关闭后回到 ProviderEditPage
 *
 * 注意:
 *  - 本 Activity 无 UI(setTheme 透明),仅做转发
 *  - exported="true" 是 Deep Link 必需的(浏览器 → 应用跳转)
 *  - state 校验由 [OAuthManager.completeAuthorizationCodeFlow] 内部完成(CSRF 防护)
 */
class OAuthCallbackActivity : ComponentActivity(), KoinComponent {

    private val mcpRegistry: McpRegistry by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            handleIntent(intent)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lifecycleScope.launch {
            handleIntent(intent)
            finish()
        }
    }

    /** 从 Intent 解析 code + state,转发给 [OAuthManager]。 */
    private suspend fun handleIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: run {
            Logger.w(TAG, "OAuthCallbackActivity 收到空 data,忽略")
            return
        }
        val code = data.getQueryParameter("code").orEmpty()
        val state = data.getQueryParameter("state").orEmpty()
        if (state.isNotBlank() && mcpRegistry.completeOAuthCallback(data.toString())) {
            return
        }
        // 优先处理 error 参数(用户拒绝授权 / 服务端错误)
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            Logger.w(TAG, "OAuth 回调报错")
            // 直接置 OAuthManager 为 ERROR 态,让挂起的 launchAuthorizationCodeFlow 返回失败
            OAuthManager.cancel()
            return
        }
        if (code.isBlank() || state.isBlank()) {
            Logger.w(TAG, "OAuth 回调缺 code/state,忽略")
            return
        }
        OAuthManager.completeAuthorizationCodeFlow(code, state)
    }

    private companion object {
        private const val TAG = "OAuthCallbackActivity"
    }
}
