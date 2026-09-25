@file:Suppress("LargeClass")

package io.zer0.muse.ui.common.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * Muse Icons — 自绘图标集的 Compose ImageVector 版本（静态,非 Composable）。
 *
 * 由 muse-icons/scripts/to-compose.py 自动生成，勿手改。
 * 源: https://github.com/Zer0Qing/muse-icons
 */
object MuseIcons {
    /** search */
    val search: ImageVector by lazy {
        ImageVector.Builder(
            name = "search",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 10.5a6 6 0 1 0 12 0a6 6 0 1 0 -12 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M14.9 14.9L20 20"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** chat */
    val chat: ImageVector by lazy {
        ImageVector.Builder(
            name = "chat",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7.5 16.5h-1a3 3 0 0 1-3-3v-4a3 3 0 0 1 3-3h11a3 3 0 0 1 3 3v4a3 3 0 0 1-3 3h-6." +
                            "3l-3 2.5c-.4.33-1 .04-1-.48z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** user */
    val user: ImageVector by lazy {
        ImageVector.Builder(
            name = "user",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M8.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5.5 19.5c0-3 2.9-5 6.5-5s6.5 2 6.5 5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** plus */
    val plus: ImageVector by lazy {
        ImageVector.Builder(
            name = "plus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 5.5v13M5.5 12h13"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** edit */
    val edit: ImageVector by lazy {
        ImageVector.Builder(
            name = "edit",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4 20h4L18.5 9.5a2.83 2.83 0 0 0-4-4L4 16z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M13.5 6.5l4 4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** send */
    val send: ImageVector by lazy {
        ImageVector.Builder(
            name = "send",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M20.2 3.8L3.9 10.9a.6.6 0 0 0 .07 1.13l5.9 1.9 1.9 5.9a.6.6 0 0 0 1.13.07L20.2 3" +
                            ".8z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M20.2 3.8L9.87 13.93"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** paperclip */
    val paperclip: ImageVector by lazy {
        ImageVector.Builder(
            name = "paperclip",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M16.6 9.4l-6.2 6.2a2.1 2.1 0 0 0 3 3l6.2-6.2a4.1 4.1 0 0 0-5.8-5.8L6.6 13.6a6.1 " +
                            "6.1 0 0 0 8.6 8.6l5.6-5.5",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** arrow-left */
    val arrowLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "arrow-left",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M19 12H5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M11 5l-7 7 7 7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** x */
    val x: ImageVector by lazy {
        ImageVector.Builder(
            name = "x",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M6 6l12 12M18 6L6 18"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** more-horizontal */
    val moreHorizontal: ImageVector by lazy {
        ImageVector.Builder(
            name = "more-horizontal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M6 12h.01M12 12h.01M18 12h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** sliders */
    val sliders: ImageVector by lazy {
        ImageVector.Builder(
            name = "sliders",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 7.5h15M4.5 12h15M4.5 16.5h15"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7.5 7.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12.5 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6 16.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** calendar */
    val calendar: ImageVector by lazy {
        ImageVector.Builder(
            name = "calendar",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7 5.5h10a3 3 0 0 1 3 3v8a3 3 0 0 1 -3 3h-10a3 3 0 0 1 -3 -3v-8a3 3 0 0 1 3 -3z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 3.5v3.5M15.5 3.5v3.5M4 10.5h16"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** device-mobile */
    val deviceMobile: ImageVector by lazy {
        ImageVector.Builder(
            name = "device-mobile",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M9.5 3.5h5a2.5 2.5 0 0 1 2.5 2.5v12a2.5 2.5 0 0 1 -2.5 2.5h-5a2.5 2.5 0 0 1 -2.5" +
                            " -2.5v-12a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10.75 17.5h2.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** robot */
    val robot: ImageVector by lazy {
        ImageVector.Builder(
            name = "robot",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7.5 8h9a3 3 0 0 1 3 3v4.5a3 3 0 0 1 -3 3h-9a3 3 0 0 1 -3 -3v-4.5a3 3 0 0 1 3 -3" +
                            "z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 8V5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.75 12.5h.01M14.25 12.5h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M2.5 12v3M21.5 12v3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** sparkle */
    val sparkle: ImageVector by lazy {
        ImageVector.Builder(
            name = "sparkle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M12 4.2c.42 3.2 2.6 5.38 5.8 5.8-3.2.42-5.38 2.6-5.8 5.8-.42-3.2-2.6-5.38-5.8-5." +
                            "8 3.2-.42 5.38-2.6 5.8-5.8z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M17.8 14.6c.24 1.8 1.5 3.06 3.3 3.3-1.8.24-3.06 1.5-3.3 3.3-.24-1.8-1.5-3.06-3.3" +
                            "-3.3 1.8-.24 3.06-1.5 3.3-3.3z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** puzzle */
    val puzzle: ImageVector by lazy {
        ImageVector.Builder(
            name = "puzzle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M9.4 4.5H6.5a2 2 0 0 0-2 2v2.9h2a1.7 1.7 0 1 1 0 3.4h-2v4.7a2 2 0 0 0 2 2h4.7v-2" +
                            "a1.7 1.7 0 1 1 3.4 0v2h2.9a2 2 0 0 0 2-2v-4.7h-2a1.7 1.7 0 1 1 0-3.4h2V6.5a2 2 0" +
                            " 0 0-2-2h-4.7v2a1.7 1.7 0 1 1-3.4 0z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** book-open */
    val bookOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "book-open",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M12 6.5C10.6 5.1 8.4 4.5 5.5 4.5v13c2.9 0 5.1.6 6.5 2 1.4-1.4 3.6-2 6.5-2v-13c-2" +
                            ".9 0-5.1.6-6.5 2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 6.5v13"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** bolt */
    val bolt: ImageVector by lazy {
        ImageVector.Builder(
            name = "bolt",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M13.3 3.7c.52-.6 1.53-.18 1.46.61l-.5 5.19h4.02c.72 0 1.1.84.63 1.38l-8.21 9.42c" +
                            "-.52.6-1.53.18-1.46-.61l.5-5.19H5.72c-.72 0-1.1-.84-.63-1.38z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** check */
    val check: ImageVector by lazy {
        ImageVector.Builder(
            name = "check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M5 12.5l4.5 4.5L19 7.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** trash */
    val trash: ImageVector by lazy {
        ImageVector.Builder(
            name = "trash",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 7h15"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.5 7V5.5a1.5 1.5 0 0 1 1.5-1.5h2a1.5 1.5 0 0 1 1.5 1.5V7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6.5 7l.8 11.1a2 2 0 0 0 2 1.9h5.4a2 2 0 0 0 2-1.9L17.5 7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10.2 11v5M13.8 11v5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** refresh */
    val refresh: ImageVector by lazy {
        ImageVector.Builder(
            name = "refresh",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M19.9 12a7.9 7.9 0 1 1-2.32-5.58"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M20 4v5.5h-5.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** chevron-down */
    val chevronDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "chevron-down",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M6 9.5l6 6 6-6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** chevron-up */
    val chevronUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "chevron-up",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M6 14.5l6-6 6 6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** chevron-right */
    val chevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "chevron-right",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9.5 6l6 6-6 6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** chevron-left */
    val chevronLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "chevron-left",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M14.5 6l-6 6 6 6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** arrow-down */
    val arrowDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "arrow-down",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 5v14"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5.5 12.5L12 19l6.5-6.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** arrow-up */
    val arrowUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "arrow-up",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 19V5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5.5 11.5L12 5l6.5 6.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** arrow-right */
    val arrowRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "arrow-right",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M5 12h14"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12.5 5.5L19 12l-6.5 6.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** more-vertical */
    val moreVertical: ImageVector by lazy {
        ImageVector.Builder(
            name = "more-vertical",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 6h.01M12 12h.01M12 18h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** copy */
    val copy: ImageVector by lazy {
        ImageVector.Builder(
            name = "copy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M11 8.5h6a2.5 2.5 0 0 1 2.5 2.5v6a2.5 2.5 0 0 1 -2.5 2.5h-6a2.5 2.5 0 0 1 -2.5 -" +
                            "2.5v-6a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5.5 15.5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** share */
    val share: ImageVector by lazy {
        ImageVector.Builder(
            name = "share",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M14.5 6a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M14.5 18a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M15 7.2l-5.9 3.5M9.1 13.3l5.9 3.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** download */
    val download: ImageVector by lazy {
        ImageVector.Builder(
            name = "download",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 4v11M7.5 10.5L12 15l4.5-4.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4.5 16.5v1a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-1"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** upload */
    val upload: ImageVector by lazy {
        ImageVector.Builder(
            name = "upload",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 15V4M7.5 8.5L12 4l4.5 4.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4.5 16.5v1a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-1"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** external-link */
    val externalLink: ImageVector by lazy {
        ImageVector.Builder(
            name = "external-link",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M13.5 5H19v5.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M19 5l-8 8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M19 13.5V17a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h3.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** microphone */
    val microphone: ImageVector by lazy {
        ImageVector.Builder(
            name = "microphone",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M12 3.5h0a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3h-0a3 3 0 0 1 -3 -3v-5a3 3 0 0 1 3 -3z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5.5 11.5a6.5 6.5 0 0 0 13 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 18v2.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** camera */
    val camera: ImageVector by lazy {
        ImageVector.Builder(
            name = "camera",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M3.5 8A2.5 2.5 0 0 1 6 5.5h1.2a1.5 1.5 0 0 0 1.3-.76l.5-.88a1.5 1.5 0 0 1 1.3-.7" +
                            "6h3.4a1.5 1.5 0 0 1 1.3.76l.5.88a1.5 1.5 0 0 0 1.3.76H18A2.5 2.5 0 0 1 20.5 8v8a" +
                            "2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 16z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.8 12.5a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** image */
    val image: ImageVector by lazy {
        ImageVector.Builder(
            name = "image",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6.5 4.5h11a3 3 0 0 1 3 3v9a3 3 0 0 1 -3 3h-11a3 3 0 0 1 -3 -3v-9a3 3 0 0 1 3 -3" +
                            "z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7.25 9.5a1.75 1.75 0 1 0 3.5 0a1.75 1.75 0 1 0 -3.5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5.8 19.5l5.2-5.2a1.9 1.9 0 0 1 2.7 0l5.5 5.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** play */
    val play: ImageVector by lazy {
        ImageVector.Builder(
            name = "play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7.5 5.4a1 1 0 0 1 1.5-.87l9.5 5.6a1 1 0 0 1 0 1.74l-9.5 5.6a1 1 0 0 1-1.5-.87z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** pause */
    val pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M8.6 5h0.4a1.6 1.6 0 0 1 1.6 1.6v10.8a1.6 1.6 0 0 1 -1.6 1.6h-0.4a1.6 1.6 0 0 1 " +
                            "-1.6 -1.6v-10.8a1.6 1.6 0 0 1 1.6 -1.6z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M15 5h0.4a1.6 1.6 0 0 1 1.6 1.6v10.8a1.6 1.6 0 0 1 -1.6 1.6h-0.4a1.6 1.6 0 0 1 -" +
                            "1.6 -1.6v-10.8a1.6 1.6 0 0 1 1.6 -1.6z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** stop */
    val stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "stop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9 6h6a3 3 0 0 1 3 3v6a3 3 0 0 1 -3 3h-6a3 3 0 0 1 -3 -3v-6a3 3 0 0 1 3 -3z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** folder */
    val folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "folder",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M3.5 7.5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.4.6l1.1 1.1a2 2 0 0 0 1.4.6h5.2a2 2 0 0 1" +
                            " 2 2v7.7a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** file-text */
    val fileText: ImageVector by lazy {
        ImageVector.Builder(
            name = "file-text",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M13.5 3.5H7a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M13.5 3.5V9.5H19"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 13.5h6M9 16.5h4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** archive */
    val archive: ImageVector by lazy {
        ImageVector.Builder(
            name = "archive",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v2a1.5 1.5 0 0 1 -1.5 1.5h-14a1.5 1.5 0 0 1 -1.5 " +
                            "-1.5v-2a1.5 1.5 0 0 1 1.5 -1.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5 9.5v8a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10 13.5h4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** clipboard */
    val clipboard: ImageVector by lazy {
        ImageVector.Builder(
            name = "clipboard",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M9 5.5H8a2.5 2.5 0 0 0-2.5 2.5v10a2.5 2.5 0 0 0 2.5 2.5h8a2.5 2.5 0 0 0 2.5-2.5V" +
                            "8A2.5 2.5 0 0 0 16 5.5h-1",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M10.5 3.5h3a1.5 1.5 0 0 1 1.5 1.5v1a1.5 1.5 0 0 1 -1.5 1.5h-3a1.5 1.5 0 0 1 -1.5" +
                            " -1.5v-1a1.5 1.5 0 0 1 1.5 -1.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** cloud */
    val cloud: ImageVector by lazy {
        ImageVector.Builder(
            name = "cloud",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7 18.5a4.25 4.25 0 0 1-.44-8.48 5.5 5.5 0 0 1 10.72.55A3.9 3.9 0 0 1 16.5 18.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** cloud-upload */
    val cloudUpload: ImageVector by lazy {
        ImageVector.Builder(
            name = "cloud-upload",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M7 16.5a4 4 0 0 1-.4-7.98 5.2 5.2 0 0 1 10.12.52A3.7 3.7 0 0 1 16.7 16.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 12.5V20M9 15.5l3-3 3 3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** server */
    val server: ImageVector by lazy {
        ImageVector.Builder(
            name = "server",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M5.5 4.5h13a2 2 0 0 1 2 2v3a2 2 0 0 1 -2 2h-13a2 2 0 0 1 -2 -2v-3a2 2 0 0 1 2 -2" +
                            "z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M5.5 12.5h13a2 2 0 0 1 2 2v3a2 2 0 0 1 -2 2h-13a2 2 0 0 1 -2 -2v-3a2 2 0 0 1 2 -" +
                            "2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7 8h.01M7 16h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** database */
    val database: ImageVector by lazy {
        ImageVector.Builder(
            name = "database",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 6a7.5 3 0 1 0 15 0a7.5 3 0 1 0 -15 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4.5 6v12c0 1.66 3.36 3 7.5 3s7.5-1.34 7.5-3V6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4.5 12c0 1.66 3.36 3 7.5 3s7.5-1.34 7.5-3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** globe */
    val globe: ImageVector by lazy {
        ImageVector.Builder(
            name = "globe",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M3.5 12h17"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M12 3.5c2.3 2.3 3.5 5.3 3.5 8.5s-1.2 6.2-3.5 8.5c-2.3-2.3-3.5-5.3-3.5-8.5s1.2-6." +
                            "2 3.5-8.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** lock */
    val lock: ImageVector by lazy {
        ImageVector.Builder(
            name = "lock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7.5 10.5h9a2.5 2.5 0 0 1 2.5 2.5v4.5a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2" +
                            ".5 -2.5v-4.5a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8 10.5V7.5a4 4 0 0 1 8 0v3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 14.5v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** eye */
    val eye: ImageVector by lazy {
        ImageVector.Builder(
            name = "eye",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12S6.5 5.5 12 5.5 20.5 12 20.5 12 17.5 18.5 12 18.5 3.5 12 3.5 12z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** eye-off */
    val eyeOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "eye-off",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4 4l16 16"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M6.6 7.5A15 15 0 0 0 3.5 12S6.5 18.5 12 18.5c1.5 0 2.8-.47 3.9-1.1M9.9 5.8A9.4 9" +
                            ".4 0 0 1 12 5.5c5.5 0 8.5 6.5 8.5 6.5a15.4 15.4 0 0 1-3.4 4.3",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10 10.6a2.8 2.8 0 0 0 3.6 3.8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** info */
    val info: ImageVector by lazy {
        ImageVector.Builder(
            name = "info",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 11v5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 8h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** alert-triangle */
    val alertTriangle: ImageVector by lazy {
        ImageVector.Builder(
            name = "alert-triangle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M10.3 4.9a2 2 0 0 1 3.4 0l7 12.1a2 2 0 0 1-1.7 3H5a2 2 0 0 1-1.7-3z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 9.5v4.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 17h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** alert-circle */
    val alertCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "alert-circle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 7.5V13"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 16.5h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** shield-check */
    val shieldCheck: ImageVector by lazy {
        ImageVector.Builder(
            name = "shield-check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 3.5l7 2.6v5.4c0 4.4-3 7.9-7 9-4-1.1-7-4.6-7-9V6.1z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 11.8l2.2 2.2 4-4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** bell */
    val bell: ImageVector by lazy {
        ImageVector.Builder(
            name = "bell",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M12 4a5.5 5.5 0 0 0-5.5 5.5c0 3.6-1 4.7-1.8 5.5-.5.5-.1 1.5.6 1.5h13.4c.7 0 1.1-" +
                            "1 .6-1.5-.8-.8-1.8-1.9-1.8-5.5A5.5 5.5 0 0 0 12 4z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10 19.5a2.2 2.2 0 0 0 4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** clock */
    val clock: ImageVector by lazy {
        ImageVector.Builder(
            name = "clock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 7.5V12l3 2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** star */
    val star: ImageVector by lazy {
        ImageVector.Builder(
            name = "star",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 3.6l2.5 5.3 5.8.8-4.2 4.1 1 5.8-5.1-2.7-5.1 2.7 1-5.8L3.7 9.7l5.8-.8z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** users */
    val users: ImageVector by lazy {
        ImageVector.Builder(
            name = "users",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M6.5 8.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4 19c0-2.8 2.4-4.5 5.5-4.5s5.5 1.7 5.5 4.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M15.5 5.8a3 3 0 0 1 0 5.4M17.4 14.9c1.6.7 2.6 2 2.6 3.6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** swap-horizontal */
    val swapHorizontal: ImageVector by lazy {
        ImageVector.Builder(
            name = "swap-horizontal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 8h14M15 4.5L18.5 8 15 11.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M19.5 16h-14M9 12.5L5.5 16 9 19.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** languages */
    val languages: ImageVector by lazy {
        ImageVector.Builder(
            name = "languages",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 5.5h9M8 3.5v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10.5 5.5c-.6 2.8-2.3 5.3-4.7 7M5.3 8.2c.9 2.1 2.6 4 4.7 4.8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12.5 20.5l3.5-9 3.5 9M13.8 17.5h4.4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** bulb */
    val bulb: ImageVector by lazy {
        ImageVector.Builder(
            name = "bulb",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M8.5 14.5A6 6 0 1 1 15.5 14.5c-.9.7-1.5 1.6-1.5 2.6h-4c0-1-.6-1.9-1.5-2.6z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.8 20h4.4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** wrench */
    val wrench: ImageVector by lazy {
        ImageVector.Builder(
            name = "wrench",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M20.2 6.3a5 5 0 0 1-6.3 6.3L7 19.5a2.12 2.12 0 0 1-3-3l6.9-6.9a5 5 0 0 1 6.3-6.3" +
                            "l-3.1 3.1 3 3z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** flame */
    val flame: ImageVector by lazy {
        ImageVector.Builder(
            name = "flame",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M12 3.5c3 3.2 5.5 6 5.5 9.6a5.5 5.5 0 1 1-11 0c0-1.7.8-3.3 1.8-4.6.3 1 1 1.8 2 2" +
                            ".1.1-2.6.9-5 1.7-7.1z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** brain */
    val brain: ImageVector by lazy {
        ImageVector.Builder(
            name = "brain",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M10.4 4.6A2.6 2.6 0 0 0 8.1 7.2c-1.5.4-2.6 1.7-2.6 3.3 0 .7.2 1.3.6 1.9-.4.5-.6 " +
                            "1.1-.6 1.8 0 1.7 1.2 3.1 2.8 3.3.3 1.2 1.3 2 2.6 2 .9 0 1.7-.4 2.1-1.1V5.7c-.5-." +
                            "7-1.4-1.1-2.4-1.1z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M13.6 4.6a2.6 2.6 0 0 1 2.3 2.6c1.5.4 2.6 1.7 2.6 3.3 0 .7-.2 1.3-.6 1.9.4.5.6 1" +
                            ".1.6 1.8 0 1.7-1.2 3.1-2.8 3.3-.3 1.2-1.3 2-2.6 2-.9 0-1.7-.4-2.1-1.1V5.7c.5-.7 " +
                            "1.4-1.1 2.4-1.1z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** wand */
    val wand: ImageVector by lazy {
        ImageVector.Builder(
            name = "wand",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M15.5 4.5l1 2.4 2.4 1-2.4 1-1 2.4-1-2.4-2.4-1 2.4-1z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M20 12l.5 1.4 1.4.5-1.4.5-.5 1.4-.5-1.4-1.4-.5 1.4-.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M13.8 8.2L3.5 18.5a1.2 1.2 0 0 0 1.7 1.7L15.5 9.9"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** activity */
    val activity: ImageVector by lazy {
        ImageVector.Builder(
            name = "activity",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12h3l2.5-6 4.5 12 2.5-6h4.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** affiliate */
    val affiliate: ImageVector by lazy {
        ImageVector.Builder(
            name = "affiliate",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9.5 5.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M3 18.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M16 18.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 8v3M12 11l-5.3 5.3M12 11l5.3 5.3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** arrows-vertical */
    val arrowsVertical: ImageVector by lazy {
        ImageVector.Builder(
            name = "arrows-vertical",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 4v16"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 7.5L12 4l3.5 3.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 16.5L12 20l3.5-3.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** maximize */
    val maximize: ImageVector by lazy {
        ImageVector.Builder(
            name = "maximize",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M9 3.5H5A1.5 1.5 0 0 0 3.5 5v4M15 3.5h4A1.5 1.5 0 0 1 20.5 5v4M15 20.5h4a1.5 1.5" +
                            " 0 0 0 1.5-1.5v-4M9 20.5H5A1.5 1.5 0 0 1 3.5 19v-4",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** at */
    val at: ImageVector by lazy {
        ImageVector.Builder(
            name = "at",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M8.5 12a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M15.5 12v1.5a2.5 2.5 0 0 0 5 0V12a8.5 8.5 0 1 0-3.2 6.6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** atom */
    val atom: ImageVector by lazy {
        ImageVector.Builder(
            name = "atom",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            group(
                rotate = 60f,
                pivotX = 12f,
                pivotY = 12f,
            ) {
                addPath(
                    pathData = addPathNodes("M3 12a9 4 0 1 0 18 0a9 4 0 1 0 -18 0"),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            group(
                rotate = -60f,
                pivotX = 12f,
                pivotY = 12f,
            ) {
                addPath(
                    pathData = addPathNodes("M3 12a9 4 0 1 0 18 0a9 4 0 1 0 -18 0"),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    /** ban */
    val ban: ImageVector by lazy {
        ImageVector.Builder(
            name = "ban",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6.2 6.2l11.6 11.6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** book */
    val book: ImageVector by lazy {
        ImageVector.Builder(
            name = "book",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M20 5.5v13.5a1 1 0 0 1-1 1H7.5A2.5 2.5 0 0 1 5 17.5v-11A3 3 0 0 1 8 3.5h11a1 1 0" +
                            " 0 1 1 1z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5 17.5A2.5 2.5 0 0 1 7.5 15H19"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** bookmark */
    val bookmark: ImageVector by lazy {
        ImageVector.Builder(
            name = "bookmark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M6.5 4.5h11a1 1 0 0 1 1 1v15l-6.5-4.2L5.5 20.5v-15a1 1 0 0 1 1-1z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** box */
    val box: ImageVector by lazy {
        ImageVector.Builder(
            name = "box",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 3.5l8 4.2v8.6l-8 4.2-8-4.2V7.7z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4.2 7.9L12 12l7.8-4.1M12 12v8.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** braces */
    val braces: ImageVector by lazy {
        ImageVector.Builder(
            name = "braces",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M9 4.5c-2 0-3 1-3 3v2.5c0 1.5-1 2-2 2 1 0 2 .5 2 2V17c0 2 1 3 3 3M15 4.5c2 0 3 1" +
                            " 3 3v2.5c0 1.5 1 2 2 2-1 0-2 .5-2 2V17c0 2-1 3-3 3",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** brand-android */
    val brandAndroid: ImageVector by lazy {
        ImageVector.Builder(
            name = "brand-android",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M5.5 16.5a6.5 6.5 0 0 1 13 0z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 7.2L7 5M15.5 7.2L17 5M3.8 16.5v3.2M20.2 16.5v3.2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.5 11.5h.01M14.5 11.5h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** brand-telegram */
    val brandTelegram: ImageVector by lazy {
        ImageVector.Builder(
            name = "brand-telegram",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M21 4.5L3 11.7a.5.5 0 0 0 .05.93l4.7 1.6 1.6 4.7a.5.5 0 0 0 .93.05z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M21 4.5L8.9 14.1"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** brand-github */
    val brandGithub: ImageVector by lazy {
        ImageVector.Builder(
            name = "brand-github",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M12 3.5a8.5 8.5 0 0 0-2.7 16.56c.42.08.58-.18.58-.4v-1.5c-2.37.5-2.87-1.1-2.87-1" +
                            ".1-.38-.98-.94-1.24-.94-1.24-.77-.52.06-.51.06-.51.85.06 1.3.87 1.3.87.76 1.29 1" +
                            ".98.92 2.47.7.08-.55.3-.92.53-1.13-1.9-.22-3.9-.95-3.9-4.22 0-.93.33-1.69.87-2.2" +
                            "9-.09-.22-.38-1.08.08-2.26 0 0 .72-.23 2.35.87a8.1 8.1 0 0 1 4.28 0c1.63-1.1 2.3" +
                            "5-.87 2.35-.87.46 1.18.17 2.04.08 2.26.54.6.87 1.36.87 2.29 0 3.27-2 4-3.9 4.22." +
                            "3.26.58.79.58 1.6v2.37c0 .22.15.48.59.4A8.5 8.5 0 0 0 12 3.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** brand-google */
    val brandGoogle: ImageVector by lazy {
        ImageVector.Builder(
            name = "brand-google",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M15.5 9.2A4.5 4.5 0 1 0 16.4 12H12"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** brand-bing */
    val brandBing: ImageVector by lazy {
        ImageVector.Builder(
            name = "brand-bing",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.5 6.8v9l6.5-2.4-2.8-1.6v-2.6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** browser */
    val browser: ImageVector by lazy {
        ImageVector.Builder(
            name = "browser",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6.5 4.5h11a3 3 0 0 1 3 3v9a3 3 0 0 1 -3 3h-11a3 3 0 0 1 -3 -3v-9a3 3 0 0 1 3 -3" +
                            "z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M3.5 9h17"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6.5 6.75h.01M9 6.75h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** bug */
    val bug: ImageVector by lazy {
        ImageVector.Builder(
            name = "bug",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9.5 4.5L8 3M14.5 4.5L16 3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M12 6.5h0a4 4 0 0 1 4 4v3a4 4 0 0 1 -4 4h-0a4 4 0 0 1 -4 -4v-3a4 4 0 0 1 4 -4z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8 10.5H3.5M8 13.5H3.5M16 10.5h4.5M16 13.5h4.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 6.5v11"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** calculator */
    val calculator: ImageVector by lazy {
        ImageVector.Builder(
            name = "calculator",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M8 3.5h8a2.5 2.5 0 0 1 2.5 2.5v12a2.5 2.5 0 0 1 -2.5 2.5h-8a2.5 2.5 0 0 1 -2.5 -" +
                            "2.5v-12a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M9 7.5h6M9 11.5h.01M12 11.5h.01M15 11.5h.01M9 15h.01M12 15h.01M15 15h.01M9 18.5h" +
                            ".01M12 18.5h.01M15 18.5h.01",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** calendar-stats */
    val calendarStats: ImageVector by lazy {
        ImageVector.Builder(
            name = "calendar-stats",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7 5.5h10a3 3 0 0 1 3 3v8a3 3 0 0 1 -3 3h-10a3 3 0 0 1 -3 -3v-8a3 3 0 0 1 3 -3z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 3.5v3.5M15.5 3.5v3.5M4 10.5h16"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 16.5v-2M12 16.5v-3.5M15 16.5v-1.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** calendar-time */
    val calendarTime: ImageVector by lazy {
        ImageVector.Builder(
            name = "calendar-time",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6 5h6a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1 -2.5 2.5h-6a2.5 2.5 0 0 1 -2.5 -2.5" +
                            "v-8a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6.5 3.5V6M11.5 3.5V6M3.5 9h11"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M14 15.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M17.5 13.8v1.7l1.2.8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** chart-bar */
    val chartBar: ImageVector by lazy {
        ImageVector.Builder(
            name = "chart-bar",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4 3.5v17h17"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8 17v-4M12 17v-7M16 17v-2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** chart-line */
    val chartLine: ImageVector by lazy {
        ImageVector.Builder(
            name = "chart-line",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4 3.5v17h17"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7 15l3.5-4 3 2.5L18 8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** circle */
    val circle: ImageVector by lazy {
        ImageVector.Builder(
            name = "circle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** circle-check */
    val circleCheck: ImageVector by lazy {
        ImageVector.Builder(
            name = "circle-check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 12.2l2.5 2.5 4.5-5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** circle-minus */
    val circleMinus: ImageVector by lazy {
        ImageVector.Builder(
            name = "circle-minus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 12h7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** cloud-download */
    val cloudDownload: ImageVector by lazy {
        ImageVector.Builder(
            name = "cloud-download",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M7 16.5a4 4 0 0 1-.4-7.98 5.2 5.2 0 0 1 10.12.52A3.7 3.7 0 0 1 16.7 16.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 12.5V20M9 17l3 3 3-3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** cloud-off */
    val cloudOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "cloud-off",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4 4l16 16"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M7 17a4 4 0 0 1-.4-7.9A5.5 5.5 0 0 1 9.5 5.6M17.3 8.6a3.9 3.9 0 0 1 1 7.3M14 17h" +
                            "-7",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** color-swatch */
    val colorSwatch: ImageVector by lazy {
        ImageVector.Builder(
            name = "color-swatch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4 12a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0 -11 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 12a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0 -11 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** compass */
    val compass: ImageVector by lazy {
        ImageVector.Builder(
            name = "compass",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M15.5 8.5l-2 5-5 2 2-5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** computer */
    val computer: ImageVector by lazy {
        ImageVector.Builder(
            name = "computer",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6 5h12a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5 -2" +
                            ".5v-7a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 20.5h6M12 17v3.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** device-floppy */
    val deviceFloppy: ImageVector by lazy {
        ImageVector.Builder(
            name = "device-floppy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M5.5 3.5h10L20.5 8.5v10a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8 3.5V8h7V3.5M7.5 20.5v-6h9v6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** feather */
    val feather: ImageVector by lazy {
        ImageVector.Builder(
            name = "feather",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M20.2 3.8a6.5 6.5 0 0 0-9.2 0L4 10.8V20h9.2l7-7a6.5 6.5 0 0 0 0-9.2z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M16 8L3.5 20.5M17.5 15H9"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** file */
    val file: ImageVector by lazy {
        ImageVector.Builder(
            name = "file",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M13.5 3.5H7a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M13.5 3.5V9.5H19"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** filter */
    val filter: ImageVector by lazy {
        ImageVector.Builder(
            name = "filter",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 5h15l-5.9 7.2v5.4l-3.2 2v-7.4z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** flask */
    val flask: ImageVector by lazy {
        ImageVector.Builder(
            name = "flask",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M9.5 3.5v5.2L4.8 17a2.2 2.2 0 0 0 1.9 3.3h10.6a2.2 2.2 0 0 0 1.9-3.3l-4.7-8.3V3." +
                            "5",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 3.5h7M7.2 14.5h9.6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** folder-open */
    val folderOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "folder-open",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M4 15V6.5a2 2 0 0 1 2-2h3.5a2 2 0 0 1 1.4.6l1 1a2 2 0 0 0 1.4.6H17a2 2 0 0 1 2 2" +
                            "V10",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M4.2 16.8l1.7-5.3a1.2 1.2 0 0 1 1.15-.85h13.3a.9.9 0 0 1 .85 1.15l-1.6 5.1a1.6 1" +
                            ".6 0 0 1-1.5 1.1H5.5a1.2 1.2 0 0 1-1.3-1.2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** folder-plus */
    val folderPlus: ImageVector by lazy {
        ImageVector.Builder(
            name = "folder-plus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M3.5 7.5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.4.6l1.1 1.1a2 2 0 0 0 1.4.6h5.2a2 2 0 0 1" +
                            " 2 2v7.7a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 11.5v5M9.5 14h5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** forward */
    val forward: ImageVector by lazy {
        ImageVector.Builder(
            name = "forward",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M14.5 5.5L19 10l-4.5 4.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4.5 19v-5a4 4 0 0 1 4-4H19"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** gauge */
    val gauge: ImageVector by lazy {
        ImageVector.Builder(
            name = "gauge",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 15a8.5 8.5 0 1 1 17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 15l3.8-4.8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** git-merge */
    val gitMerge: ImageVector by lazy {
        ImageVector.Builder(
            name = "git-merge",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 6a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M3.5 18a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M15.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6 8.5v7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.2 6H11a6 6 0 0 1 6 6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** grip-vertical */
    val gripVertical: ImageVector by lazy {
        ImageVector.Builder(
            name = "grip-vertical",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9 5h.01M15 5h.01M9 12h.01M15 12h.01M9 19h.01M15 19h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** heart */
    val heart: ImageVector by lazy {
        ImageVector.Builder(
            name = "heart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M19.5 12.6L12 20l-7.5-7.4a5 5 0 0 1 7.5-6.6 5 5 0 0 1 7.5 6.6z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** help */
    val help: ImageVector by lazy {
        ImageVector.Builder(
            name = "help",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.3 9.5a2.7 2.7 0 0 1 5.2.9c0 1.8-2.5 2.1-2.5 3.6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 17.2h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** hexagon */
    val hexagon: ImageVector by lazy {
        ImageVector.Builder(
            name = "hexagon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 3.5l7.4 4.25v8.5L12 20.5l-7.4-4.25v-8.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** hierarchy */
    val hierarchy: ImageVector by lazy {
        ImageVector.Builder(
            name = "hierarchy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M10.5 3.5h3a1.5 1.5 0 0 1 1.5 1.5v1.5a1.5 1.5 0 0 1 -1.5 1.5h-3a1.5 1.5 0 0 1 -1" +
                            ".5 -1.5v-1.5a1.5 1.5 0 0 1 1.5 -1.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M4.5 16h3a1.5 1.5 0 0 1 1.5 1.5v1.5a1.5 1.5 0 0 1 -1.5 1.5h-3a1.5 1.5 0 0 1 -1.5" +
                            " -1.5v-1.5a1.5 1.5 0 0 1 1.5 -1.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M16.5 16h3a1.5 1.5 0 0 1 1.5 1.5v1.5a1.5 1.5 0 0 1 -1.5 1.5h-3a1.5 1.5 0 0 1 -1." +
                            "5 -1.5v-1.5a1.5 1.5 0 0 1 1.5 -1.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 8v8M6 16v-4h12v4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** history */
    val history: ImageVector by lazy {
        ImageVector.Builder(
            name = "history",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 20.5a8.5 8.5 0 1 1 8.5-8.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M20.5 4.5v4h-4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 8v4.5l3 1.8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** home */
    val home: ImageVector by lazy {
        ImageVector.Builder(
            name = "home",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 10.5L12 4l7.5 6.5V19a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 19z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.5 20.5V14h5v6.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** inbox */
    val inbox: ImageVector by lazy {
        ImageVector.Builder(
            name = "inbox",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M20.5 13.5v4a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M3.5 13.5L6 6a1.5 1.5 0 0 1 1.4-1h9.2A1.5 1.5 0 0 1 18 6l2.5 7.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M3.5 13.5h5l1.5 2h4l1.5-2h5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** infinity */
    val infinity: ImageVector by lazy {
        ImageVector.Builder(
            name = "infinity",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M12 12c-1.2 2-2.2 3.2-4 3.2a3.2 3.2 0 0 1 0-6.4c1.8 0 2.8 1.2 4 3.2s2.2 3.2 4 3." +
                            "2a3.2 3.2 0 0 0 0-6.4c-1.8 0-2.8 1.2-4 3.2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** key */
    val key: ImageVector by lazy {
        ImageVector.Builder(
            name = "key",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 16a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10.5 13.5L20 4M16.5 7.5l2.5 2.5M14 10l2 2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** layout-columns */
    val layoutColumns: ImageVector by lazy {
        ImageVector.Builder(
            name = "layout-columns",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v10a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5" +
                            " -2.5v-10a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 4.5v15"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** lifebuoy */
    val lifebuoy: ImageVector by lazy {
        ImageVector.Builder(
            name = "lifebuoy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 12a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6 6l3.5 3.5M18 6l-3.5 3.5M18 18l-3.5-3.5M6 18l3.5-3.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** link */
    val link: ImageVector by lazy {
        ImageVector.Builder(
            name = "link",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9.5 14.5l5-5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M11 7.5l1.8-1.8a3.7 3.7 0 0 1 5.2 5.2L16.2 12.7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M13 16.5l-1.8 1.8a3.7 3.7 0 0 1-5.2-5.2L7.8 11.3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** mail */
    val mail: ImageVector by lazy {
        ImageVector.Builder(
            name = "mail",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6 5.5h12a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5 " +
                            "-2.5v-8a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M3.9 6.8l6.9 5.3a2 2 0 0 0 2.4 0l6.9-5.3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** map-pin */
    val mapPin: ImageVector by lazy {
        ImageVector.Builder(
            name = "map-pin",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 21.5s-7-6.2-7-11.5a7 7 0 0 1 14 0c0 5.3-7 11.5-7 11.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** memory-chip */
    val memoryChip: ImageVector by lazy {
        ImageVector.Builder(
            name = "memory-chip",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M8.5 6.5h7a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-7a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M10.5 9.5h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 3.5v3M15 3.5v3M9 17.5v3M15 17.5v3M3.5 9h3M3.5 15h3M17.5 9h3M17.5 15h3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** messages */
    val messages: ImageVector by lazy {
        ImageVector.Builder(
            name = "messages",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M17 12.5h1a2.5 2.5 0 0 0 2.5-2.5v-3A2.5 2.5 0 0 0 18 4.5H9A2.5 2.5 0 0 0 6.5 7v1",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M7 9.5h10A2.5 2.5 0 0 1 19.5 12v3a2.5 2.5 0 0 1-2.5 2.5h-4.6l-3.2 2.5c-.4.3-1 .0" +
                            "4-1-.48v-2.02H7A2.5 2.5 0 0 1 4.5 15v-3A2.5 2.5 0 0 1 7 9.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** minus */
    val minus: ImageVector by lazy {
        ImageVector.Builder(
            name = "minus",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M5.5 12h13"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** mood-sad */
    val moodSad: ImageVector by lazy {
        ImageVector.Builder(
            name = "mood-sad",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 10h.01M15 10h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 16a4.6 4.6 0 0 1 7 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** mood-smile */
    val moodSmile: ImageVector by lazy {
        ImageVector.Builder(
            name = "mood-smile",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 10h.01M15 10h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 14.5a4.6 4.6 0 0 0 7 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** moon */
    val moon: ImageVector by lazy {
        ImageVector.Builder(
            name = "moon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** moon-stars */
    val moonStars: ImageVector by lazy {
        ImageVector.Builder(
            name = "moon-stars",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M18.5 15.5A7.5 7.5 0 1 1 8.5 5.4a6.2 6.2 0 0 0 10 10.1z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M18 3.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** movie */
    val movie: ImageVector by lazy {
        ImageVector.Builder(
            name = "movie",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v10a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5" +
                            " -2.5v-10a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7.5 4.5v15M16.5 4.5v15M3.5 9.5h4M3.5 14.5h4M16.5 9.5h4M16.5 14.5h4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** note */
    val note: ImageVector by lazy {
        ImageVector.Builder(
            name = "note",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M5 5.5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v8.5l-7 7H7a2 2 0 0 1-2-2z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 21v-6a1 1 0 0 1 1-1h6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** package */
    val packageIcon: ImageVector by lazy {
        ImageVector.Builder(
            name = "package",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 3.5l8 4.2v8.6l-8 4.2-8-4.2V7.7z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4.2 7.9L12 12l7.8-4.1M12 12v8.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.2 5.6L16 9.8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** palette */
    val palette: ImageVector by lazy {
        ImageVector.Builder(
            name = "palette",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M12 3.5c-4.7 0-8.5 3.4-8.5 7.6 0 4.2 3.8 7.6 8.5 7.6.9 0 1.6-.7 1.6-1.6 0-.4-.2-" +
                            ".8-.4-1.1-.3-.3-.4-.7-.4-1.1 0-.9.7-1.6 1.6-1.6h1.9c2.1 0 3.8-1.7 3.8-3.8 0-3.3-" +
                            "3.6-6-8.1-6z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7.6 12h.01M9.6 8.4h.01M14.4 8.4h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** phone */
    val phone: ImageVector by lazy {
        ImageVector.Builder(
            name = "phone",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6.5 3.5h3l1.5 4.5-2 1.5a11.5 11.5 0 0 0 5.5 5.5l1.5-2 4.5 1.5v3a2 2 0 0 1-2 2A1" +
                            "5.5 15.5 0 0 1 4.5 5.5a2 2 0 0 1 2-2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** photo-library */
    val photoLibrary: ImageVector by lazy {
        ImageVector.Builder(
            name = "photo-library",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M10 3.5h8a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1 -2.5 2.5h-8a2.5 2.5 0 0 1 -2.5 -" +
                            "2.5v-8a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M16.5 16.5v1a2.5 2.5 0 0 1-2.5 2.5H6a2.5 2.5 0 0 1-2.5-2.5V10A2.5 2.5 0 0 1 6 7." +
                            "5h1",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10.6 8a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8 15l2.8-2.8a1.6 1.6 0 0 1 2.2 0l3.3 3.3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** pin */
    val pin: ImageVector by lazy {
        ImageVector.Builder(
            name = "pin",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9.5 3.5h5l-.8 4.4 3.3 3.3v1.3H7v-1.3l3.3-3.3z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 12.5V21"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** planet */
    val planet: ImageVector by lazy {
        ImageVector.Builder(
            name = "planet",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M6.5 12a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0 -11 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            group(
                rotate = -20f,
                pivotX = 12f,
                pivotY = 12f,
            ) {
                addPath(
                    pathData = addPathNodes("M2 12a10 3.5 0 1 0 20 0a10 3.5 0 1 0 -20 0"),
                    stroke = SolidColor(Color(0xFF000000)),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    /** plug */
    val plug: ImageVector by lazy {
        ImageVector.Builder(
            name = "plug",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9 3.5v5M15 3.5v5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6.5 8.5h11v2a5.5 5.5 0 0 1-11 0z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 16v4.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** power */
    val power: ImageVector by lazy {
        ImageVector.Builder(
            name = "power",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 3.5v7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6.8 6.8a8 8 0 1 0 10.4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** qrcode */
    val qrcode: ImageVector by lazy {
        ImageVector.Builder(
            name = "qrcode",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M5 3.5h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1 -1.5 1.5h-4a1.5 1.5 0 0 1 -1.5 -1" +
                            ".5v-4a1.5 1.5 0 0 1 1.5 -1.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M15 3.5h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1 -1.5 1.5h-4a1.5 1.5 0 0 1 -1.5 -" +
                            "1.5v-4a1.5 1.5 0 0 1 1.5 -1.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M5 13.5h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1 -1.5 1.5h-4a1.5 1.5 0 0 1 -1.5 -" +
                            "1.5v-4a1.5 1.5 0 0 1 1.5 -1.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M13.5 13.5h3.2v3.2h-3.2zM20.5 17.5v3M17 17.5h.01M17 20.5h.01M20.5 13.5h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** reply */
    val reply: ImageVector by lazy {
        ImageVector.Builder(
            name = "reply",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9.5 5.5L5 10l4.5 4.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M19.5 19v-5a4 4 0 0 0-4-4H5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** rocket */
    val rocket: ImageVector by lazy {
        ImageVector.Builder(
            name = "rocket",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 3.5c2.8 1.6 4.5 4.8 4.5 8.5v4h-9v-4c0-3.7 1.7-6.9 4.5-8.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M10.4 10a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7.5 16l-2.5 2.5V21l3-1.2M16.5 16l2.5 2.5V21l-3-1.2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** route */
    val route: ImageVector by lazy {
        ImageVector.Builder(
            name = "route",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 19a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M15.5 5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8.5 19h6.5a3.5 3.5 0 0 0 0-7h-6a3.5 3.5 0 0 1 0-7h6.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** router */
    val router: ImageVector by lazy {
        ImageVector.Builder(
            name = "router",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M5.5 13.5h13a2 2 0 0 1 2 2v2.5a2 2 0 0 1 -2 2h-13a2 2 0 0 1 -2 -2v-2.5a2 2 0 0 1" +
                            " 2 -2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7 16.75h.01M10.5 16.75h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 13.5v-2.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9 9.5a4.5 4.5 0 0 1 6 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** rss */
    val rss: ImageVector by lazy {
        ImageVector.Builder(
            name = "rss",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 11a8.5 8.5 0 0 1 8.5 8.5M4.5 4.5a15 15 0 0 1 15 15"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5.5 19h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** school */
    val school: ImageVector by lazy {
        ImageVector.Builder(
            name = "school",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M2.5 9.5L12 5l9.5 4.5L12 14z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6.5 11.5v4c0 1.4 2.5 2.5 5.5 2.5s5.5-1.1 5.5-2.5v-4M21.5 9.5V15"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** shield */
    val shield: ImageVector by lazy {
        ImageVector.Builder(
            name = "shield",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 3.5l7 2.6v5.4c0 4.4-3 7.9-7 9-4-1.1-7-4.6-7-9V6.1z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** sort */
    val sort: ImageVector by lazy {
        ImageVector.Builder(
            name = "sort",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 6.5h8M4.5 12h6M4.5 17.5h4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M17 5.5v13M14 15.5l3 3 3-3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** square */
    val square: ImageVector by lazy {
        ImageVector.Builder(
            name = "square",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7 3.5h10a3.5 3.5 0 0 1 3.5 3.5v10a3.5 3.5 0 0 1 -3.5 3.5h-10a3.5 3.5 0 0 1 -3.5" +
                            " -3.5v-10a3.5 3.5 0 0 1 3.5 -3.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** stack */
    val stack: ImageVector by lazy {
        ImageVector.Builder(
            name = "stack",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M12 3.5l8.5 4.25L12 12 3.5 7.75z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M3.5 12L12 16.25 20.5 12M3.5 16.25L12 20.5l8.5-4.25"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** stars */
    val stars: ImageVector by lazy {
        ImageVector.Builder(
            name = "stars",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9.5 3.5l1.8 3.7 4 .6-2.9 2.8.7 4-3.6-1.9-3.6 1.9.7-4L3.5 7.8l4-.6z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M17.5 13l.9 1.85 2 .3-1.45 1.4.35 2-1.8-.95-1.8.95.35-2-1.45-1.4 2-.3z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** sun */
    val sun: ImageVector by lazy {
        ImageVector.Builder(
            name = "sun",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M12 3.5v2M12 18.5v2M3.5 12h2M18.5 12h2M6 6l1.4 1.4M16.6 16.6L18 18M18 6l-1.4 1.4" +
                            "M7.4 16.6L6 18",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** switch */
    val switch: ImageVector by lazy {
        ImageVector.Builder(
            name = "switch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7 7.5h10a4.5 4.5 0 0 1 4.5 4.5v0a4.5 4.5 0 0 1 -4.5 4.5h-10a4.5 4.5 0 0 1 -4.5 " +
                            "-4.5v-0a4.5 4.5 0 0 1 4.5 -4.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M14.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** temperature */
    val temperature: ImageVector by lazy {
        ImageVector.Builder(
            name = "temperature",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M10 13.8V5.5a2 2 0 1 1 4 0v8.3a4 4 0 1 1-4 0z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** template */
    val template: ImageVector by lazy {
        ImageVector.Builder(
            name = "template",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6.5 4h11a2.5 2.5 0 0 1 2.5 2.5v11a2.5 2.5 0 0 1 -2.5 2.5h-11a2.5 2.5 0 0 1 -2.5" +
                            " -2.5v-11a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4 9h16M9.5 9v11"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** terminal */
    val terminal: ImageVector by lazy {
        ImageVector.Builder(
            name = "terminal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v10a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5" +
                            " -2.5v-10a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7 9l3 3-3 3M12.5 15H17"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** thumb-up */
    val thumbUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "thumb-up",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M7 10.5V20M7 10.5l3.5-6a1.8 1.8 0 0 1 1.8 1.8V9h5.1a1.8 1.8 0 0 1 1.77 2.13l-1.0" +
                            "5 6A1.8 1.8 0 0 1 16.35 18.5H7",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M4 10.5h3v9H4a.5.5 0 0 1-.5-.5v-8a.5.5 0 0 1 .5-.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** timer */
    val timer: ImageVector by lazy {
        ImageVector.Builder(
            name = "timer",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 13.5a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 9.5v4l2.5 1.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.5 2.5h5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** trending-down */
    val trendingDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "trending-down",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 7l6 6 4-4 6.5 6.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M20 10v5.5h-5.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** trending-up */
    val trendingUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "trending-up",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 17l6-6 4 4 6.5-6.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M20 13v-5.5h-5.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** typography */
    val typography: ImageVector by lazy {
        ImageVector.Builder(
            name = "typography",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4.5 5.5v-2h15v2M12 3.5v17M8.5 20.5h7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** video */
    val video: ImageVector by lazy {
        ImageVector.Builder(
            name = "video",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M5 6.5h8a2.5 2.5 0 0 1 2.5 2.5v6a2.5 2.5 0 0 1 -2.5 2.5h-8a2.5 2.5 0 0 1 -2.5 -2" +
                            ".5v-6a2.5 2.5 0 0 1 2.5 -2.5z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M15.5 10l4.3-2.6a.8.8 0 0 1 1.2.7v7.8a.8.8 0 0 1-1.2.7L15.5 14"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** volume */
    val volume: ImageVector by lazy {
        ImageVector.Builder(
            name = "volume",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M11 5L6.5 8.5H3.5v7h3L11 19z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M15 9.5a3.5 3.5 0 0 1 0 5M17.5 7a7 7 0 0 1 0 10"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** wallet */
    val wallet: ImageVector by lazy {
        ImageVector.Builder(
            name = "wallet",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M19 8V6.5a1.5 1.5 0 0 0-1.5-1.5H6A2.5 2.5 0 0 0 3.5 7.5V17A2.5 2.5 0 0 0 6 19.5h" +
                            "11.5a1.5 1.5 0 0 0 1.5-1.5v-1.5",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M20.5 10h-4.25a2.75 2.75 0 0 0 0 5.5h4.25a.5.5 0 0 0 .5-.5v-4.5a.5.5 0 0 0-.5-.5" +
                            "z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** wave-sine */
    val waveSine: ImageVector by lazy {
        ImageVector.Builder(
            name = "wave-sine",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.5 12c1.6-5.5 3.9-5.5 5.5 0s3.9 5.5 5.5 0 3.9-5.5 5.5 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** wifi */
    val wifi: ImageVector by lazy {
        ImageVector.Builder(
            name = "wifi",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3 9.5a13.5 13.5 0 0 1 18 0M6 13a9 9 0 0 1 12 0M9 16.5a4.5 4.5 0 0 1 6 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 20h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** wind */
    val wind: ImageVector by lazy {
        ImageVector.Builder(
            name = "wind",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M3.5 8.5h9.5a2.5 2.5 0 1 0-2.5-2.5M3.5 12.5h13a2.5 2.5 0 1 1-2.5 2.5M3.5 16.5h7",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** celebration */
    val celebration: ImageVector by lazy {
        ImageVector.Builder(
            name = "celebration",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9 8.5L3.5 20.5l12-5.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M13.5 4l.8 2M17 7l2 .8M15 10.5l2.5 1.5M18.5 13.5l1.5.5M16.5 6.5l1.5-1.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** brush */
    val brush: ImageVector by lazy {
        ImageVector.Builder(
            name = "brush",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M19.5 4.5a2.1 2.1 0 0 1 0 3L13 14l-3.5 1 1-3.5z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M8 15.5c-1 .5-2.5 2-2.8 3.5-.2.9-.9 1.4-1.7 1.7 1.7.4 4 .2 5.2-1.2.9-1 .8-2.5-.7" +
                            "-4z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** battery */
    val battery: ImageVector by lazy {
        ImageVector.Builder(
            name = "battery",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        "M4.5 8h11a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-11a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M20.5 11v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M6 11v2M9 11v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** code */
    val code: ImageVector by lazy {
        ImageVector.Builder(
            name = "code",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M8.5 7l-5 5 5 5M15.5 7l5 5-5 5M13 5.5l-2.5 13"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** hand-finger */
    val handFinger: ImageVector by lazy {
        ImageVector.Builder(
            name = "hand-finger",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M9.5 11.5V6.25a1.75 1.75 0 0 1 3.5 0V11"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData =
                    addPathNodes(
                        "M13 10.5V8.75a1.75 1.75 0 0 1 3.5 0v5.25a6.5 6.5 0 0 1-6.5 6.5h-1a6 6 0 0 1-6-6v" +
                            "-2.25a1.75 1.75 0 0 1 3.5 0",
                    ),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** provider */
    val provider: ImageVector by lazy {
        ImageVector.Builder(
            name = "provider",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M7 13.4a4 4 0 0 1-.42-7.98 5.2 5.2 0 0 1 10.12.52A3.7 3.7 0 0 1 16.6 13.4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M12 13.4v1.4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M9.6 17.6a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** task-routing */
    val taskRouting: ImageVector by lazy {
        ImageVector.Builder(
            name = "task-routing",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M3.3 12a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M16 5.5a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M16 12a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M16 18.5a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M7.7 12l8.2-5.3M7.7 12h8.2M7.7 12l8.2 5.3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** proxy */
    val proxy: ImageVector by lazy {
        ImageVector.Builder(
            name = "proxy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M11 9h2a2 2 0 0 1 2 2v2a2 2 0 0 1 -2 2h-2a2 2 0 0 1 -2 -2v-2a2 2 0 0 1 2 -2z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M3.5 12h4M16.5 12h4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M5.5 10l2 2-2 2M18.5 10l-2 2 2 2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
}
