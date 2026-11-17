package io.zer0.muse.web

/**
 * 搜索供应商返回了「有答复但不可用」的响应（鉴权失败、额度不足、服务端错误）。
 *
 * 为什么要抛而不是返回空列表：空列表在上层等于「搜了，没结果」，
 * 模型会据此编答案；而 401/402/5xx 的真实含义是「这条路没通」，
 * 必须让工具层把原因如实交给模型和用户。
 */
class SearchProviderFailure(
    val providerName: String,
    val httpCode: Int,
    val detail: String,
) : RuntimeException(
    buildString {
        append(providerName)
        append(" 返回 HTTP ")
        append(httpCode)
        when {
            httpCode == 401 || httpCode == 403 -> append("（API Key 无效或无权限）")
            httpCode == 402 -> append("（额度不足，需要充值）")
            httpCode == 404 -> append("（接口地址不存在，检查 Base URL）")
            httpCode in 500..599 -> append("（服务端错误，可稍后重试）")
        }
        if (detail.isNotBlank()) {
            append("：")
            append(detail.take(160))
        }
    },
)
