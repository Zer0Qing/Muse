package io.zer0.ai

import io.zer0.ai.image.AgnesImageProvider
import io.zer0.ai.image.ImageProviderRegistry
import io.zer0.ai.image.ImageService
import io.zer0.ai.image.OpenAIImageProvider
import io.zer0.ai.video.AgnesVideoProvider
import io.zer0.ai.video.GenericOpenAiVideoProvider
import io.zer0.ai.video.KlingVideoProvider
import io.zer0.ai.video.VideoGenerationService
import io.zer0.ai.video.VideoProviderRegistry
import okhttp3.OkHttpClient
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * ai 模块的 Koin 装配。
 *
 * [ProviderConfigStore] 由 app 模块提供实现(基于 DataStore),
 * [OkHttpClient] 也由 app 模块注册(用 named("chat") qualifier,与 web search client 区分),
 * 因此这里只声明 [ChatService] / [ImageService] / [VideoGenerationService]。
 *
 * v1.0.18: 图片生成改为 Provider 抽象 — 注册 [ImageProviderRegistry] 与各 [io.zer0.ai.image.ImageProvider]
 * (Agnes / OpenAI),[ImageService] 通过 registry 按 [io.zer0.ai.core.ProviderConfig] 自动选择适配器。
 */
val aiModule: Module = module {
    single { ChatService(get()) }

    // v1.0.18: 图片生成 Provider 抽象层
    single { AgnesImageProvider(get(named("chat"))) }
    single { OpenAIImageProvider(get(named("chat"))) }
    single {
        ImageProviderRegistry().apply {
            register(get<AgnesImageProvider>())
            register(get<OpenAIImageProvider>())
        }
    }
    single { ImageService(get(), get()) } // configStore + registry

    // P2-8: 视频生成服务 — 注入 named("chat") OkHttpClient(已配 30s connect / 300s read 超时,
    // 满足提交 30s + 轮询 5min 的超时要求)
    // v1.137: 视频生成重构 — 注册 [VideoProviderRegistry],按 specId/host 路由到具体 Provider,
    // 不再按 providerId 硬匹配(修复 preset_kling ≠ kling 的路由 bug)。
    // 各 Provider 实现 VideoProvider 接口,统一 submit/poll 协议。
    single { KlingVideoProvider(get(named("chat"))) }
    single { AgnesVideoProvider(get(named("chat"))) }
    single { GenericOpenAiVideoProvider(get(named("chat"))) }
    single {
        VideoProviderRegistry(genericProvider = get<GenericOpenAiVideoProvider>()).apply {
            register(get<KlingVideoProvider>())
            register(get<AgnesVideoProvider>())
        }
    }
    single { VideoGenerationService(get()) }
}
