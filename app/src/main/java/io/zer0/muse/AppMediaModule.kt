package io.zer0.muse

import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * P2-3 拆域：OCR / TTS / 语音克隆等媒体服务注册独立模块。
 */
val appMediaModule = module {

    // Phase 8.6: 本地 OCR 管理�?ML Kit 中英文离线识�?
    single { io.zer0.muse.doc.OcrManager() }

    // v1.0.30 gap4.6: 翻译术语表存储(JSON 文件持久化原文→译文映射)
    single { io.zer0.muse.ui.translate.GlossaryStore(androidContext()) }

    // Phase 8.7: TTS 管理�?Android 系统 TextToSpeech,0 APK 体积)
    // v1.97: 注入 CloudTtsService 支持云端 TTS(OpenAI/MiniMax/Edge)
    // v1.97 修复: CloudTtsService 构造需�?OkHttpClient,必须�?named("chat") qualifier
    //   Koin 只注册了�?qualifier �?OkHttpClient(chat/webSearch),�?get() 找不到定�?
    //   release 混淆下触�?NoDefinitionFoundException,链式导致 ChatViewModel 创建失败 �?应用崩溃�?
    //   chat client 已配�?30s/120s/30s 超时 + 代理,适合 TTS 网络请求,无需单独再建一个�?
    single { io.zer0.muse.ui.speech.CloudTtsService(get(named("chat"))) }
    single { io.zer0.muse.ui.speech.TtsManager(androidContext(), get()) }

    // P2-9: 语音克隆 — ElevenLabs Voice Cloning Provider 复用 chat OkHttpClient
    //   (内部用 newBuilder() 覆盖为 30s 三项超时,满足"API 调用必须有超时(30 秒)"约束)
    single { io.zer0.muse.ui.speech.ElevenLabsVoiceCloningProvider(get(named("chat"))) }
    single { io.zer0.muse.ui.speech.FishAudioVoiceCloningProvider(get(named("chat"))) }
    // P2-9: VoiceCloningService 多 Provider 分发(后续 OpenVoice / Fish Audio 等可继续加入 map)
    single {
        io.zer0.muse.ui.speech.VoiceCloningService(
            mapOf(
                "elevenlabs" to get<io.zer0.muse.ui.speech.ElevenLabsVoiceCloningProvider>(),
                "fish" to get<io.zer0.muse.ui.speech.FishAudioVoiceCloningProvider>(),
            )
        )
    }
}