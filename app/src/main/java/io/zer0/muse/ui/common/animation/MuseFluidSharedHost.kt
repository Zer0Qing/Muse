package io.zer0.muse.ui.common.animation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * F2 共享元素基建 — 宿主、注入点与降级契约。
 *
 * 设计约束:
 *  - 全应用只挂一个 [SharedTransitionLayout]（在 MainActivity 的 NavHost 外）——
 *    多 scope 并存时 key 互不相认,转场会整体退化为无动画;
 *  - [LocalMuseSharedScope] 与 [LocalMuseAnimatedVisibilityScope] 都允许为 null:
 *    组件预览、独立窗口、未接管的旧页面拿不到 scope 时,[museSharedElement] 原样返回
 *    ——降级是默认路径,调用点不需要各自判空;
 *  - 跨窗口弹层（Dialog 类查看器）无法参与宿主 scope 的连贯形变,这是 Compose 的窗口
 *    边界而非用法问题;涉及场景（缩略图 ↔ 全屏预览）待查看器改造为树内浮层后再接入。
 *
 * navigation-compose 2.9.x 为目的地 content 提供 AnimatedVisibilityScope 接收者,
 * 各 NavGraph 在目的地包装处把它注入 [LocalMuseAnimatedVisibilityScope]。
 */
val LocalMuseSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** 目的地级可见性作用域;由 NavGraph 注入,跨页面共享元素的两端各自读取。 */
val LocalMuseAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 共享元素宿主:包在 NavHost 外面。
 *
 * content 直接渲染,不要求接收者——保证包裹前后 NavHost 的 composable 块写法不变。
 */
@Composable
fun MuseFluidSharedHost(content: @Composable () -> Unit) {
    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalMuseSharedScope provides this,
            content = content,
        )
    }
}

/**
 * 标记一个跨页面共享元素。
 *
 * 任一 scope 缺失（预览/独立窗口/未接管页面）时原样返回,即"没有连贯形变,
 * 也绝不白屏或跳变"的降级承诺。key 必须在两端一致且非空,例如
 * `assistant-avatar-<assistantId>`。
 */
@Composable
fun Modifier.museSharedElement(
    key: String,
    animatedVisibilityScope: AnimatedVisibilityScope? = LocalMuseAnimatedVisibilityScope.current,
): Modifier {
    val sharedScope = LocalMuseSharedScope.current ?: return this
    if (animatedVisibilityScope == null || key.isBlank()) return this
    return with(sharedScope) {
        this@museSharedElement.sharedElement(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
        )
    }
}
