package io.zer0.muse

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Koin 模块**注册顺序**的守卫。
 *
 * 为什么顺序值得被测：Koin 4.0 没有 `override<T>` DSL，同 key 定义靠
 * 「后加载的模块胜出」表达覆盖（`AppKoinModule` 里 `miniPhoneHostModule` 上方的注释
 * 就是这么写的）。也就是说 —— 有人把两行调换，**不会编译失败、不会崩溃、也不会报错**，
 * 只是能力静默回落到占位实现：小手机没人格、不回话、麦克风永远置灰。
 * 同一文件里 `SsrfBridgeModule` 也注明"必须在 aiModule 前注册"，同一种隐式契约。
 *
 * 直接对真实的 `allKoinModules` 断言，不读源码文本：读源码只能证明"字在那儿"，
 * 证明不了列表里真的这个顺序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KoinModuleOrderTest {

    private fun positionOf(target: org.koin.core.module.Module): Int {
        val index = allKoinModules.indexOf(target)
        assertTrue("模块没被注册进 allKoinModules，顺序契约无从谈起：$target", index >= 0)
        return index
    }

    @Test
    fun `宿主覆盖模块必须排在小手机模块之后`() {
        val placeholderFirst = positionOf(io.zer0.muse.miniphone.di.miniPhoneModule)
        val hostOverride = positionOf(miniPhoneHostModule)

        assertTrue(
            "Koin 靠后加载者覆盖：宿主模块若排在 miniPhoneModule 之前，" +
                "小手机会静默用回占位宿主（无人格、不回话、转写永远不可用）",
            placeholderFirst < hostOverride,
        )
    }

    @Test
    fun `SSRF 桥接必须排在 ai 模块之前`() {
        val bridge = positionOf(SsrfBridgeModule)
        val ai = positionOf(io.zer0.ai.aiModule)

        assertTrue(
            "AgnesImageProvider 在 aiModule 里就要拿到 RefImageUrlValidator，" +
                "注册晚了注入的就是空实现（SSRF 校验静默失效）",
            bridge < ai,
        )
    }

    @Test
    fun `注册表非空且无重复模块`() {
        val names = allKoinModules.map { System.identityHashCode(it) }

        assertTrue("allKoinModules 不该为空", allKoinModules.isNotEmpty())
        assertTrue(
            "同一模块被注册两次会让「谁覆盖谁」变得不可读（Koin 顺序语义依赖列表位置）",
            names.size == names.distinct().size,
        )
    }
}
