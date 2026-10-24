package io.zer0.muse.data.provider

import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelListCache
import io.zer0.ai.core.ModelRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v2.x: 供应商模型清单拉取管线(引导页 / 供应商编辑页共用口径)。
 *
 * 流程: 缓存优先(5 分钟 TTL) → URL 多策略补全(用户漏填/多填 /v1 的常见场景) →
 * 拉取 → [ModelRegistry.enrich] 能力富化(abilities / modalities / contextWindow) →
 * 写回缓存。无论预置供应商还是自定义 OpenAI 兼容端点,都走同一条链。
 *
 * 401/403 立即失败不做 URL 回退(凭证问题换路径也没用)。
 */
object ProviderModelFetcher {

    sealed class Outcome {
        /** 成功: 已富化的模型清单(非空) + 实际命中的配置(含修正后的 baseUrl)。 */
        data class Success(val models: List<Model>, val usedConfig: ProviderConfig) : Outcome()

        /** 失败;[kind] 供调用方映射本地化文案,[message] 为原始诊断信息。 */
        data class Failure(val kind: FailureKind, val message: String) : Outcome()
    }

    enum class FailureKind { AUTH, EMPTY, NETWORK, OTHER }

    /** 拉取并富化模型清单。全策略失败时返回 [Outcome.Failure]。 */
    suspend fun fetch(config: ProviderConfig, forceFresh: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        // 1) 缓存优先(5 分钟 TTL)— 与供应商编辑页共用缓存,避免重复打上游
        if (!forceFresh) {
            val cached = ModelListCache.get(config, forceFresh = false)
            if (cached != null) {
                return@withContext if (cached.isEmpty()) {
                    Outcome.Failure(FailureKind.EMPTY, "cached model list is empty")
                } else {
                    Outcome.Success(cached.map { ModelRegistry.enrich(it) }, config)
                }
            }
        }

        // 2) URL 多策略补全 — 覆盖用户漏填/多填 /v1 的场景
        val base = config.baseUrl.trimEnd('/')
        val urlsToTry = mutableListOf<String>()
        if (base.isNotBlank()) {
            urlsToTry.add(base)
            if (!base.endsWith("/v1") && !base.endsWith("/v1beta")) {
                urlsToTry.add("$base/v1")
            } else if (base.endsWith("/v1")) {
                urlsToTry.add(base.removeSuffix("/v1"))
            }
        } else {
            urlsToTry.add(config.baseUrl)
        }

        var lastError: String? = null
        var sawEmpty = false
        for (url in urlsToTry) {
            val cfg = config.copy(baseUrl = url)
            val result = resultOf { ProviderRegistry.create(cfg).listModels(cfg) }
            val models = result.getOrNull()
            if (models != null) {
                if (models.isEmpty()) {
                    sawEmpty = true
                    continue
                }
                // 3) 逐个富化 — 自动推导 abilities / modalities / contextWindow,
                //    保证视觉/推理三态判定在引导阶段就已生效
                val enriched = models.map { ModelRegistry.enrich(it) }
                ModelListCache.put(cfg, models)
                return@withContext Outcome.Success(enriched, cfg)
            }
            var failMsg = "unknown"
            result.onError { msg, t -> failMsg = t?.message ?: msg }
            if (failMsg.contains("401") || failMsg.contains("403")) {
                return@withContext Outcome.Failure(FailureKind.AUTH, failMsg)
            }
            lastError = failMsg
        }

        // 全策略失败: 空清单 vs 网络/其他
        if (sawEmpty) {
            Outcome.Failure(FailureKind.EMPTY, "upstream returned no models")
        } else {
            Outcome.Failure(FailureKind.NETWORK, lastError ?: "unknown error")
        }
    }

    /**
     * 验证配置是否真的能用 —— 以一次真实对话为准。
     *
     * 与 [fetch] 的分工：
     *  - [fetch] 只回答“能拉到哪些模型”，依赖上游实现 /models。
     *    而 /models 恰好是兼容接口里最不可靠的一个端点 —— 不实现、返回非标准结构、
     *    单独做鉴权限制都很常见，于是一个能正常聊天的供应商也会在这里被判死。
     *  - 本方法回答“能不能真的发消息”。它先试 /models 拿一个模型名，
     *    拿不到就用 [fallbackModelId]（调用方自己填的），然后发一条最小消息。
     *
     * 结果语义：
     *  - 聊天通了 → [VerifyOutcome.Ready]，带模型名与（可能的）模型数；
     *  - 聊天不通 → [VerifyOutcome.NotReady]，带健康检查翻译过的可操作原因。
     *
     * @param config 待验证配置（可含未保存的编辑值）
     * @param fallbackModelId 上游拿不到列表时要试的模型 id（通常取用户已配置的第一个）
     */
    suspend fun verify(config: ProviderConfig, fallbackModelId: String? = null): VerifyOutcome = withContext(Dispatchers.IO) {
        // 先拿模型清单：能拿到就顺便带回数量，拿不到也不阻断聊天探测。
        val listed = fetch(config)
        val listedModels = (listed as? Outcome.Success)?.models.orEmpty()
        val usedConfig = (listed as? Outcome.Success)?.usedConfig ?: config
        val probeId =
            listedModels.firstOrNull { it.id.isNotBlank() }?.id
                ?: fallbackModelId?.takeIf { it.isNotBlank() }
                ?: return@withContext VerifyOutcome.NotReady(
                    reason = "no model to test",
                    kind = (listed as? Outcome.Failure)?.kind ?: FailureKind.OTHER,
                )

        val provider = ProviderRegistry.create(usedConfig)
        val health = resultOf {
            provider.healthCheck(Model(id = probeId, providerId = usedConfig.id))
        }
        val result = health.getOrNull()
        if (result?.success == true) {
            VerifyOutcome.Ready(
                modelId = probeId,
                modelCount = listedModels.size,
                usedConfig = usedConfig,
                models = listedModels,
            )
        } else {
            // healthCheck 已经把异常翻译成可操作的话；拿不到就走原始错误。
            val reason = result?.message
                ?: (health as? Result.Error)?.throwable?.message
                ?: "connection failed"
            VerifyOutcome.NotReady(reason = reason, kind = FailureKind.OTHER)
        }
    }

    /** [verify] 的结果。 */
    sealed class VerifyOutcome {
        /** 聊天链路可用。[modelCount] 为 0 表示上游没给列表，不代表不可用。 */
        data class Ready(
            val modelId: String,
            val modelCount: Int,
            val usedConfig: ProviderConfig,
            val models: List<Model>,
        ) : VerifyOutcome()

        /** 聊天链路不可用。[reason] 已是可展示的句子。 */
        data class NotReady(val reason: String, val kind: FailureKind) : VerifyOutcome()
    }
}
