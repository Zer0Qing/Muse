package io.zer0.muse

import io.zer0.ai.RefImageUrlValidator
import io.zer0.muse.ui.SsrfGuard
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * G4: 参考图 SSRF 校验桥接 — 把 app 层 [SsrfGuard.refImageUrlValidator] 注册为
 * ai 模块的 [RefImageUrlValidator] Koin 类型,供 [io.zer0.ai.image.AgnesImageProvider]
 * 注入。ai 模块不能依赖 app(模块方向 app→ai),故经此桥接模块传递。
 *
 * 已加入 [allKoinModules](AppKoinModule.kt,排在 aiModule 前)。
 */
val SsrfBridgeModule: Module = module {
    single<RefImageUrlValidator> { SsrfGuard.refImageUrlValidator }
}
