package io.zer0.muse.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import io.zer0.muse.ui.theme.presets.WarmPaperTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Web 端设计令牌与 Android 端 Kotlin 常量的漂移检测。
 *
 * 单一来源是 `web/apps/client/src/design/tokens.json`（Web 侧读取它，
 * 由 `src/design/token-css.ts` 生成 `--muse-*` CSS 变量）。Android 侧的权威值仍是
 * `ui/theme` 目录下的 Kotlin 文件里的常量。任何一侧单改（改颜色、改字号、改圆角、改动效时长）
 * 都会让本测试变红——这就是它的唯一职责，不做业务断言。
 *
 * 覆盖范围：全部颜色（含默认预设主题的 colorScheme 槽位）、完整字号阶梯（字号/行高/字重/
 * 字距）、全部圆角、全部动效时长与位移比例、间距、高程、图标尺寸、组件专属几何。
 *
 * 注意：`colors.web.*` 是 Web 外壳的历史色板（SaaS 暖米色布局），Android 侧没有对应
 * 常量，因此只做键集守卫、不逐值比对；`motion.easings` 的贝塞尔控制点在 Compose 的
 * Easing 对象上读不回来，同样只做键集守卫。
 */
class DesignTokenParityTest {

    private val tokens: JsonObject by lazy { loadTokens() }

    // ── 加载 ────────────────────────────────────────────────────────────

    @Test
    fun `tokens json is reachable from the repository root`() {
        val file = File(repoRoot(), TOKENS_RELATIVE_PATH)
        assertTrue(
            "找不到 Web 设计令牌文件：${file.absolutePath}（当前工作目录 ${File(".").absolutePath}）。" +
                "请确认 tokens.json 仍在 ${TOKENS_RELATIVE_PATH}，不要静默跳过本测试。",
            file.isFile,
        )
    }

    // ── 颜色 ────────────────────────────────────────────────────────────

    @Test
    fun `palette colours match the kotlin palette`() {
        val pairs = listOf(
            "colors.brand.primary" to LaurelGreen,
            "colors.brand.primaryBright" to LaurelGreenBright,
            "colors.brand.primaryContainer" to LaurelGreenLightContainer,
            "colors.brand.onPrimaryContainerDeep" to LaurelGreenDark,
            "colors.brand.onPrimaryContainerLight" to LaurelGreenLight,
            "colors.brand.primaryContainerDark" to LaurelGreenDarkContainer,
            "colors.brand.starGold" to StarGold,
            "colors.light.bg" to LightBg,
            "colors.light.aiBubble" to LightAiBubble,
            "colors.light.ink" to Ink,
            "colors.light.secondary" to Secondary,
            "colors.light.divider" to Divider,
            "colors.light.danger" to Danger,
            "colors.light.dangerContainer" to DangerLightContainer,
            "colors.dark.bg" to DarkBg,
            "colors.dark.aiBubble" to DarkAiBubble,
            "colors.dark.ink" to DarkInk,
            "colors.dark.divider" to DarkDivider,
            "colors.dark.dangerContainer" to DangerDarkContainer,
            "colors.emotion.coralWhisper" to CoralWhisper,
            "colors.emotion.lavenderDream" to LavenderDream,
            "colors.emotion.amberWarmth" to AmberWarmth,
            "colors.emotion.sageCalm" to SageCalm,
            "colors.emotion.mistGray" to MistGray,
        )
        pairs.forEach { (path, color) -> assertColor(path, color) }
    }

    @Test
    fun `default preset colour scheme matches the token file`() {
        val light = WarmPaperTheme.lightScheme
        listOf(
            "surface" to light.surface,
            "surfaceContainer" to light.surfaceContainer,
            "surfaceContainerLow" to light.surfaceContainerLow,
            "surfaceContainerHigh" to light.surfaceContainerHigh,
            "surfaceDim" to light.surfaceDim,
            "surfaceBright" to light.surfaceBright,
            "surfaceVariant" to light.surfaceVariant,
            "onSurface" to light.onSurface,
            "onSurfaceVariant" to light.onSurfaceVariant,
            "outline" to light.outline,
            "outlineVariant" to light.outlineVariant,
            "primary" to light.primary,
            "onPrimary" to light.onPrimary,
            "primaryContainer" to light.primaryContainer,
            "onPrimaryContainer" to light.onPrimaryContainer,
            "error" to light.error,
            "onError" to light.onError,
            "errorContainer" to light.errorContainer,
        ).forEach { (slot, color) -> assertColor("colors.scheme.light.$slot", color) }

        val dark = WarmPaperTheme.darkScheme
        listOf(
            "surface" to dark.surface,
            "surfaceContainer" to dark.surfaceContainer,
            "surfaceContainerLow" to dark.surfaceContainerLow,
            "surfaceContainerHigh" to dark.surfaceContainerHigh,
            "surfaceDim" to dark.surfaceDim,
            "surfaceBright" to dark.surfaceBright,
            "surfaceVariant" to dark.surfaceVariant,
            "onSurface" to dark.onSurface,
            "onSurfaceVariant" to dark.onSurfaceVariant,
            "outline" to dark.outline,
            "outlineVariant" to dark.outlineVariant,
            "primary" to dark.primary,
            "onPrimary" to dark.onPrimary,
            "primaryContainer" to dark.primaryContainer,
            "onPrimaryContainer" to dark.onPrimaryContainer,
            "error" to dark.error,
            "onError" to dark.onError,
            "errorContainer" to dark.errorContainer,
        ).forEach { (slot, color) -> assertColor("colors.scheme.dark.$slot", color) }
    }

    @Test
    fun `derived scheme veils are derived from their scheme colour`() {
        // alpha 来源见 tokens.json 的 colors.scheme._note（MuseDivider 13% / MuseListItem 6% /
        // SectionLabel 80% / CardGroup 标题 85% / MuseSwitch 拇指内圈 20%）。
        assertVeil("light", "dividerHairline", WarmPaperTheme.lightScheme.onSurface, 0.13)
        assertVeil("light", "pressedVeil", WarmPaperTheme.lightScheme.onSurface, 0.06)
        assertVeil("light", "onSurfaceVeil80", WarmPaperTheme.lightScheme.onSurface, 0.80)
        assertVeil("light", "onSurfaceVeil85", WarmPaperTheme.lightScheme.onSurface, 0.85)
        assertVeil("light", "primaryVeil20", WarmPaperTheme.lightScheme.primary, 0.20)
        assertVeil("dark", "dividerHairline", WarmPaperTheme.darkScheme.onSurface, 0.13)
        assertVeil("dark", "pressedVeil", WarmPaperTheme.darkScheme.onSurface, 0.06)
        assertVeil("dark", "onSurfaceVeil80", WarmPaperTheme.darkScheme.onSurface, 0.80)
        assertVeil("dark", "onSurfaceVeil85", WarmPaperTheme.darkScheme.onSurface, 0.85)
        assertVeil("dark", "primaryVeil20", WarmPaperTheme.darkScheme.primary, 0.20)
        assertNumber("colors.alpha.actionDisabled", MuseActionColors.disabledAlpha.toDouble())
    }

    @Test
    fun `colour groups expose exactly the expected keys`() {
        assertKeySet(
            "colors.brand",
            setOf(
                "primary",
                "primaryBright",
                "primaryContainer",
                "onPrimaryContainerDeep",
                "onPrimaryContainerLight",
                "primaryContainerDark",
                "starGold",
            ),
        )
        assertKeySet(
            "colors.light",
            setOf("bg", "aiBubble", "ink", "secondary", "divider", "danger", "dangerContainer"),
        )
        assertKeySet("colors.dark", setOf("bg", "aiBubble", "ink", "divider", "dangerContainer"))
        assertKeySet(
            "colors.emotion",
            setOf("coralWhisper", "lavenderDream", "amberWarmth", "sageCalm", "mistGray"),
        )
        assertKeySet("colors.alpha", setOf("actionDisabled", "actionContentDisabled"))
        val schemeKeys = setOf(
            "surface", "surfaceContainer", "surfaceContainerLow", "surfaceContainerHigh", "surfaceDim",
            "surfaceBright", "surfaceVariant", "onSurface", "onSurfaceVariant", "outline", "outlineVariant",
            "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "error", "onError",
            "errorContainer", "dividerHairline", "pressedVeil", "onSurfaceVeil80", "onSurfaceVeil85",
            "primaryVeil20", "shadow", "switchTrackOff",
        )
        assertKeySet("colors.scheme.light", schemeKeys)
        assertKeySet("colors.scheme.dark", schemeKeys)
        assertKeySet("colors.web.light", webLightKeys)
        assertKeySet("colors.web.dark", webDarkKeys)
        assertKeySet("colors.web.alpha", setOf("light", "dark"))
    }

    // ── 字号阶梯 ────────────────────────────────────────────────────────

    @Test
    fun `type scale matches MuseTypography`() {
        listOf(
            "displayLarge" to MuseTypography.displayLarge,
            "displayMedium" to MuseTypography.displayMedium,
            "displaySmall" to MuseTypography.displaySmall,
            "headlineLarge" to MuseTypography.headlineLarge,
            "headlineMedium" to MuseTypography.headlineMedium,
            "headlineSmall" to MuseTypography.headlineSmall,
            "titleLarge" to MuseTypography.titleLarge,
            "titleMedium" to MuseTypography.titleMedium,
            "titleSmall" to MuseTypography.titleSmall,
            "bodyLarge" to MuseTypography.bodyLarge,
            "bodyMedium" to MuseTypography.bodyMedium,
            "bodySmall" to MuseTypography.bodySmall,
            "labelLarge" to MuseTypography.labelLarge,
            "labelMedium" to MuseTypography.labelMedium,
            "labelSmall" to MuseTypography.labelSmall,
        ).forEach { (key, style) -> assertTextStyle(key, style) }
    }

    // ── 圆角 ────────────────────────────────────────────────────────────

    @Test
    fun `corner radii match MuseCornerRadius`() {
        listOf(
            "tiny" to MuseCornerRadius.TINY,
            "bubbleTail" to MuseCornerRadius.BUBBLE_TAIL,
            "small" to MuseCornerRadius.SMALL,
            "button" to MuseCornerRadius.BUTTON,
            "semiLarge" to MuseCornerRadius.SEMI_LARGE,
            "bubble" to MuseCornerRadius.BUBBLE,
            "card" to MuseCornerRadius.CARD,
            "sheet" to MuseCornerRadius.SHEET,
            "largeCard" to MuseCornerRadius.LARGE_CARD,
            "mega" to MuseCornerRadius.MEGA,
        ).forEach { (key, value) -> assertNumber("radii.$key", value.toDouble()) }
        assertEquals(
            "圆角 pill 应为百分比圆角 ${MuseCornerRadius.PILL}%",
            "${MuseCornerRadius.PILL}%",
            tokens.text("radii.pill"),
        )
    }

    // ── 动效 ────────────────────────────────────────────────────────────

    @Test
    fun `motion durations and distances match MuseAnimation`() {
        listOf(
            "glyphCycle" to MuseAnimation.GLYPH_CYCLE_MS,
            "glyphTravel" to MuseAnimation.GLYPH_TRAVEL_MS,
            "fast" to MuseAnimation.FAST_MS,
            "fastNormal" to MuseAnimation.FAST_NORMAL_MS,
            "normal" to MuseAnimation.NORMAL_MS,
            "tactile" to MuseAnimation.TACTILE_MS,
            "slow" to MuseAnimation.SLOW_MS,
            "extraSlow" to MuseAnimation.EXTRA_SLOW_MS,
            "loopNormal" to MuseAnimation.LOOP_NORMAL_MS,
            "loopSlow" to MuseAnimation.LOOP_SLOW_MS,
            "loopExtraSlow" to MuseAnimation.LOOP_EXTRA_SLOW_MS,
            "navHorizontal" to MuseAnimation.NAV_HORIZONTAL_MS,
            "navVerticalEnter" to MuseAnimation.NAV_VERTICAL_ENTER_MS,
            "navVerticalExit" to MuseAnimation.NAV_VERTICAL_EXIT_MS,
            "enter" to MuseAnimation.ENTER_MS,
            "exit" to MuseAnimation.EXIT_MS,
            "contentEnter" to MuseAnimation.CONTENT_ENTER_MS,
            "contentExit" to MuseAnimation.CONTENT_EXIT_MS,
            "staggerStep" to MuseAnimation.STAGGER_STEP_MS,
        ).forEach { (key, value) -> assertNumber("motion.durations.$key", value.toDouble()) }

        assertNumber("motion.stagger.maxIndex", MuseAnimation.STAGGER_MAX_INDEX.toDouble())
        assertNumber("motion.slide.page", MuseAnimation.SLIDE_FRACTION_PAGE.toDouble())
        assertNumber("motion.slide.secondary", MuseAnimation.SLIDE_FRACTION_SECONDARY.toDouble())
        assertNumber("motion.slide.content", MuseAnimation.SLIDE_FRACTION_CONTENT.toDouble())
        assertKeySet(
            "motion.easings",
            setOf("standard", "easeOutCubic", "easeOutBack", "easeInCubic", "emphasized", "growEase"),
        )
    }

    // ── 间距 / 高程 / 图标 ──────────────────────────────────────────────

    @Test
    fun `spacing tokens match MusePaddings`() {
        listOf(
            "screen" to MusePaddings.screen,
            "sectionGap" to MusePaddings.sectionGap,
            "cardGap" to MusePaddings.cardGap,
            "searchBarHeight" to MusePaddings.searchBarHeight,
            "itemGap" to MusePaddings.itemGap,
            "contentGap" to MusePaddings.contentGap,
            "tightGap" to MusePaddings.tightGap,
            "tinyGap" to MusePaddings.tinyGap,
            "labelVerticalGap" to MusePaddings.labelVerticalGap,
            "auxGap" to MusePaddings.auxGap,
            "largeGap" to MusePaddings.largeGap,
            "emptyStateGap" to MusePaddings.emptyStateGap,
            "touchTarget" to MusePaddings.touchTarget,
            "inputPadding" to MusePaddings.inputPadding,
            "inputHorizontal" to MusePaddings.inputHorizontal,
            "inputVertical" to MusePaddings.inputVertical,
            "inputStackGap" to MusePaddings.inputStackGap,
            "previewThumb" to MusePaddings.previewThumb,
            "removeDotPadding" to MusePaddings.removeDotPadding,
            "maxInlineWidth" to MusePaddings.maxInlineWidth,
            "compactChipVertical" to MusePaddings.compactChipVertical,
            "inputIslandHorizontal" to MusePaddings.inputIslandHorizontal,
            "inputIslandVertical" to MusePaddings.inputIslandVertical,
            "inputIslandBottomGap" to MusePaddings.inputIslandBottomGap,
            "inputIslandInnerHorizontal" to MusePaddings.inputIslandInnerHorizontal,
            "inputIslandRowVertical" to MusePaddings.inputIslandRowVertical,
            "maxMessageFieldHeight" to MusePaddings.maxMessageFieldHeight,
            "dividerWidth" to MusePaddings.dividerWidth,
            "dividerThickness" to MusePaddings.dividerThickness,
            "maxToolSheetListHeight" to MusePaddings.maxToolSheetListHeight,
            "listRowVertical" to MusePaddings.listRowVertical,
            "iconPadding" to MusePaddings.iconPadding,
            "messageGap" to MusePaddings.messageGap,
            "listBottomClearance" to MusePaddings.listBottomClearance,
            "sectionTitleIndent" to MusePaddings.sectionTitleIndent,
        ).forEach { (key, dp) -> assertNumber("spacing.$key", dp.value.toDouble()) }
    }

    @Test
    fun `elevation and icon sizes match their token objects`() {
        listOf(
            "none" to MuseElevation.none,
            "micro" to MuseElevation.micro,
            "low" to MuseElevation.low,
            "card" to MuseElevation.card,
            "medium" to MuseElevation.medium,
            "high" to MuseElevation.high,
            "modal" to MuseElevation.modal,
            "floating" to MuseElevation.floating,
            "overlay" to MuseElevation.overlay,
        ).forEach { (key, dp) -> assertNumber("elevation.$key", dp.value.toDouble()) }

        listOf(
            "touchTarget" to MuseIconSizes.touchTarget,
            "iconEmpty" to MuseIconSizes.iconEmpty,
            "iconLarge" to MuseIconSizes.iconLarge,
            "icon" to MuseIconSizes.icon,
            "iconMedium" to MuseIconSizes.iconMedium,
            "iconSmall" to MuseIconSizes.iconSmall,
            "iconTiny" to MuseIconSizes.iconTiny,
            "iconSmallTiny" to MuseIconSizes.iconSmallTiny,
            "iconVideo" to MuseIconSizes.iconVideo,
            "controlTouch" to MuseIconSizes.controlTouch,
            "stopButton" to MuseIconSizes.stopButton,
            "topBarSolid" to MuseIconSizes.topBarSolid,
            "progressStroke" to MuseIconSizes.progressStroke,
            "inputMinHeight" to MuseIconSizes.inputMinHeight,
            "fab" to MuseIconSizes.fab,
            "waveformHeight" to MuseIconSizes.waveformHeight,
            "waveformBarWidth" to MuseIconSizes.waveformBarWidth,
            "waveformBarRadius" to MuseIconSizes.waveformBarRadius,
        ).forEach { (key, dp) -> assertNumber("iconSizes.$key", dp.value.toDouble()) }
        assertNumber("iconSizes.strokeWidth", MuseIconSizes.strokeWidth.toDouble())
    }

    @Test
    fun `component geometry matches the compose components`() {
        // 卡片：MusePaddings.cardInner / cardGap / contentGap / dividerThickness、MuseCornerRadius。
        assertNumber("component.card.innerPaddingX", paddingX(MusePaddings.cardInner))
        assertNumber("component.card.innerPaddingY", paddingY(MusePaddings.cardInner))
        assertNumber("component.card.groupGap", MusePaddings.cardGap.value.toDouble())
        assertNumber("component.card.contentGap", MusePaddings.contentGap.value.toDouble())
        assertNumber("component.card.radius", MuseCornerRadius.CARD.toDouble())
        assertNumber("component.card.groupRadius", MuseCornerRadius.LARGE_CARD.toDouble())
        assertNumber("component.card.borderWidth", MusePaddings.dividerThickness.value.toDouble())

        // 列表行：MuseListItemSizes（原先字面量在组件默认参数里）+ MusePaddings.screen / inputPadding。
        assertNumber("component.listRow.minHeight", MuseListItemSizes.minHeight.value.toDouble())
        assertNumber("component.listRow.minHeightCompact", MuseListItemSizes.compactMinHeight.value.toDouble())
        assertNumber("component.listRow.paddingX", MusePaddings.screen.value.toDouble())
        assertNumber("component.listRow.paddingY", MusePaddings.inputPadding.value.toDouble())

        // 开关：MuseSwitchSizes。
        assertNumber("component.switch.trackWidth", MuseSwitchSizes.trackWidth.value.toDouble())
        assertNumber("component.switch.trackHeight", MuseSwitchSizes.trackHeight.value.toDouble())
        assertNumber("component.switch.thumbSize", MuseSwitchSizes.thumbSize.value.toDouble())
        assertNumber("component.switch.trackInset", MuseSwitchSizes.trackPadding.value.toDouble())

        // 按钮：胶囊 48（MuseIconSizes.touchTarget）+ 24（MuseShapes.huge = MuseCornerRadius.SHEET）
        // + 0.97（MuseCapsuleButtonSizes）；普通按钮 40（MuseDialogSizes.buttonVisualHeight）。
        assertNumber("component.button.capsuleMinHeight", MuseIconSizes.touchTarget.value.toDouble())
        assertNumber("component.button.capsuleRadius", MuseCornerRadius.SHEET.toDouble())
        assertNumber("component.button.capsulePressScale", MuseCapsuleButtonSizes.pressScale.toDouble())
        assertNumber("component.button.minHeight", MuseDialogSizes.buttonVisualHeight.value.toDouble())

        // 气泡：MuseBubbleStyles / MuseCornerRadius / MusePaddings.bubbleInner / messageGap。
        assertNumber("component.bubble.maxWidthRatio", MuseBubbleStyles.MAX_WIDTH_FRACTION.toDouble())
        assertNumber("component.bubble.radius", MuseCornerRadius.CARD.toDouble())
        assertNumber("component.bubble.tailRadius", MuseCornerRadius.BUBBLE_TAIL.toDouble())
        assertNumber("component.bubble.innerPaddingX", paddingX(MusePaddings.bubbleInner))
        assertNumber("component.bubble.innerPaddingY", paddingY(MusePaddings.bubbleInner))
        assertNumber("component.bubble.gap", MusePaddings.messageGap.value.toDouble())

        // 输入岛（InputBar）：高度由 48dp 触控区决定，内/外边距取 MusePaddings.inputIsland*。
        assertNumber("component.inputBar.height", MuseIconSizes.touchTarget.value.toDouble())
        assertNumber("component.inputBar.innerPaddingX", MusePaddings.inputIslandInnerHorizontal.value.toDouble())
        assertNumber("component.inputBar.innerPaddingY", MusePaddings.inputIslandRowVertical.value.toDouble())
        assertNumber("component.inputBar.outerPaddingX", MusePaddings.inputIslandHorizontal.value.toDouble())
        assertNumber("component.inputBar.outerPaddingY", MusePaddings.inputIslandVertical.value.toDouble())
        assertNumber("component.inputBar.bottomGap", MusePaddings.inputIslandBottomGap.value.toDouble())

        // 顶栏（MuseTopBar）：行内边距 = chipInnerTight，标题内边距 = bubbleInner；
        // 高度是推导值（行上下 1×2 + 标题上下 8×2 + titleLarge 行高 26 = 44），不另存字面量。
        assertNumber("component.topBar.rowPaddingX", paddingX(MusePaddings.chipInnerTight))
        assertNumber("component.topBar.rowPaddingY", paddingY(MusePaddings.chipInnerTight))
        assertNumber("component.topBar.titlePaddingX", paddingX(MusePaddings.bubbleInner))
        assertNumber("component.topBar.titlePaddingY", paddingY(MusePaddings.bubbleInner))
        assertNumber(
            "component.topBar.height",
            paddingY(MusePaddings.chipInnerTight) * 2 +
                paddingY(MusePaddings.bubbleInner) * 2 +
                MuseTypography.titleLarge.lineHeight.value.toDouble(),
        )

        // 触控目标：MusePaddings.touchTarget 委托 MuseIconSizes.touchTarget。
        assertNumber("component.touchTarget", MuseIconSizes.touchTarget.value.toDouble())

        // 头像三档：MuseAvatarSize。
        assertNumber("component.avatar.inline", MuseAvatarSize.inline.value.toDouble())
        assertNumber("component.avatar.list", MuseAvatarSize.list.value.toDouble())
        assertNumber("component.avatar.detail", MuseAvatarSize.detail.value.toDouble())
    }

    @Test
    fun `component groups expose exactly the expected keys`() {
        assertKeySet(
            "component",
            setOf("card", "listRow", "switch", "button", "bubble", "inputBar", "topBar", "touchTarget", "avatar"),
        )
        assertKeySet(
            "component.card",
            setOf("innerPaddingX", "innerPaddingY", "groupGap", "contentGap", "radius", "groupRadius", "borderWidth"),
        )
        assertKeySet("component.listRow", setOf("minHeight", "minHeightCompact", "paddingX", "paddingY"))
        assertKeySet("component.switch", setOf("trackWidth", "trackHeight", "thumbSize", "trackInset"))
        assertKeySet("component.button", setOf("capsuleMinHeight", "capsuleRadius", "capsulePressScale", "minHeight"))
        assertKeySet(
            "component.bubble",
            setOf("maxWidthRatio", "radius", "tailRadius", "innerPaddingX", "innerPaddingY", "gap"),
        )
        assertKeySet(
            "component.inputBar",
            setOf("height", "innerPaddingX", "innerPaddingY", "outerPaddingX", "outerPaddingY", "bottomGap"),
        )
        assertKeySet(
            "component.topBar",
            setOf("height", "rowPaddingX", "rowPaddingY", "titlePaddingX", "titlePaddingY"),
        )
        assertKeySet("component.avatar", setOf("inline", "list", "detail"))
    }

    // ── 断言工具 ────────────────────────────────────────────────────────

    private fun assertColor(path: String, color: Color) {
        val android = color.hexString()
        val web = tokens.text(path).trim().uppercase()
        assertEquals("颜色令牌 $path 漂移：Android=$android, tokens.json=$web", android, web)
    }

    private fun assertVeil(mode: String, key: String, source: Color, alpha: Double) {
        val path = "colors.scheme.$mode.$key"
        val parts = parseRgba(tokens.text(path))
        val argb = source.toArgb()
        assertEquals("$path 的红色分量应派生自 ${source.hexString()}", argb shr 16 and 0xFF, parts[0].toInt())
        assertEquals("$path 的绿色分量应派生自 ${source.hexString()}", argb shr 8 and 0xFF, parts[1].toInt())
        assertEquals("$path 的蓝色分量应派生自 ${source.hexString()}", argb and 0xFF, parts[2].toInt())
        assertTrue("$path 的 alpha 应为 $alpha，实际 ${parts[3]}", abs(parts[3] - alpha) <= 0.005)
    }

    private fun assertTextStyle(key: String, style: TextStyle) {
        assertNumber("typography.scale.$key.fontSize", style.fontSize.value.toDouble())
        assertNumber("typography.scale.$key.lineHeight", style.lineHeight.value.toDouble())
        assertNumber("typography.scale.$key.weight", (style.fontWeight?.weight ?: 400).toDouble())
        if (style.letterSpacing != TextUnit.Unspecified) {
            assertNumber("typography.scale.$key.letterSpacing", style.letterSpacing.value.toDouble())
        }
    }

    /** PaddingValues 的水平内边距（Compose 只暴露 calculate*Padding）。 */
    private fun paddingX(values: PaddingValues): Double =
        values.calculateLeftPadding(LayoutDirection.Ltr).value.toDouble()

    /** PaddingValues 的垂直内边距。 */
    private fun paddingY(values: PaddingValues): Double = values.calculateTopPadding().value.toDouble()

    private fun assertNumber(path: String, expected: Double, tolerance: Double = 0.0001) {
        val actual = tokens.number(path)
        assertEquals("令牌 $path 漂移：Android=$expected, tokens.json=$actual", expected, actual, tolerance)
    }

    private fun assertKeySet(path: String, expected: Set<String>) {
        val node = tokens.at(path) as? JsonObject ?: throw AssertionError("tokens.json 的 $path 不是对象")
        val actual = node.keys.filterNot { it.startsWith("_") }.toSet()
        assertEquals("令牌键集 $path 与预期不一致（新增/删除令牌需同步本测试）", expected, actual)
    }

    private fun JsonObject.at(path: String): JsonElement {
        var node: JsonElement = this
        for (segment in path.split('.')) {
            val current = node as? JsonObject ?: throw AssertionError("tokens.json 的 $path 不是对象（$segment 处）")
            node = current[segment] ?: throw AssertionError("tokens.json 缺少 $path")
        }
        return node
    }

    private fun JsonObject.number(path: String): Double {
        val primitive = at(path) as? JsonPrimitive
        return primitive?.doubleOrNull ?: throw AssertionError("tokens.json 的 $path 不是数字")
    }

    private fun JsonObject.text(path: String): String {
        val primitive = at(path) as? JsonPrimitive
        return primitive?.content ?: throw AssertionError("tokens.json 的 $path 不是字符串")
    }

    private fun Color.hexString(): String = "#%06X".format(toArgb() and 0xFFFFFF)

    private fun parseRgba(value: String): List<Double> {
        val match = rgbaPattern.find(value.trim()) ?: throw AssertionError("tokens.json 中的 $value 不是 rgba() 值")
        val parts = match.groupValues[1].split(',').map { it.trim().toDouble() }
        if (parts.size != 4) throw AssertionError("rgba() 需要 4 个分量，实际 $value")
        return parts
    }

    private fun loadTokens(): JsonObject {
        val file = File(repoRoot(), TOKENS_RELATIVE_PATH)
        if (!file.isFile) {
            throw AssertionError(
                "找不到 Web 设计令牌文件：${file.absolutePath}（当前工作目录 ${File(".").absolutePath}）。" +
                    "本测试不允许静默跳过：请确认 tokens.json 位于仓库根的 $TOKENS_RELATIVE_PATH。",
            )
        }
        return Json.parseToJsonElement(file.readText()).jsonObjectSafely()
    }

    private fun JsonElement.jsonObjectSafely(): JsonObject = this as? JsonObject
        ?: throw AssertionError("tokens.json 顶层必须是对象")

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var current: File? = start
        while (current != null) {
            if (File(current, TOKENS_RELATIVE_PATH).isFile) return current
            current = current.parentFile
        }
        throw AssertionError(
            "找不到仓库根：从 $start 向上任何一层都没有 $TOKENS_RELATIVE_PATH。" +
                "请从仓库根或 app 模块目录运行单元测试。",
        )
    }

    private companion object {
        const val TOKENS_RELATIVE_PATH = "web/apps/client/src/design/tokens.json"
        val rgbaPattern = Regex("^rgba\\(([^)]*)\\)$")
        val webLightKeys = setOf(
            "bg", "bgDeep", "surface", "surfaceRaised", "surfaceMuted", "ink", "inkSoft", "inkMuted",
            "accent", "accentStrong", "accentHover", "success", "danger", "dangerHover", "warning",
            "warningText", "steel", "steelDeep", "borderInk", "shadowInk", "scrimInk", "white", "black",
            "codeBg", "codeInk", "toastInk",
        )
        val webDarkKeys = setOf(
            "bg", "bgDeep", "surface", "surfaceRaised", "surfaceMuted", "ink", "inkSoft", "inkMuted",
            "accent", "accentStrong", "warningText", "white", "black", "codeBg", "toastBg",
        )
    }
}
