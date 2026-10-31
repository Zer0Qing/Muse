package io.zer0.muse.miniphone.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 小手机自绘图标集。
 *
 * 全部用 [ImageVector.Builder] 从路径画出来,一条路径一组坐标,不引图标库也不拼 Material Icons:
 * 桌面图标、状态栏、页头按钮要的是同一个笔触(1.7 单位细线 + 圆端),换库就散架。
 *
 * 颜色一律给纯黑,由 `Icon(tint = ...)` 统一染色。
 */

private val IconSize = 24.dp
private const val VIEWPORT_SIZE = 24f
private const val LINE_WIDTH = 1.7f
private const val THIN_LINE_WIDTH = 1.5f

/** 圆弧近似系数:半径 r 的四段贝塞尔等价控制点偏移。 */
private const val ARC_FACTOR = 0.5523f

private fun lineIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = IconSize,
    defaultHeight = IconSize,
    viewportWidth = VIEWPORT_SIZE,
    viewportHeight = VIEWPORT_SIZE,
).apply(block).build()

private fun ImageVector.Builder.strokePath(width: Float = LINE_WIDTH, pathBuilder: PathBuilder.() -> Unit) {
    path(
        pathBuilder = pathBuilder,
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun ImageVector.Builder.fillPath(pathBuilder: PathBuilder.() -> Unit) {
    path(pathBuilder = pathBuilder, fill = SolidColor(Color.Black))
}

/** 圆:四段三次贝塞尔拼成,用于头像/指南针这类必须圆的图形。 */
private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
    val k = radius * ARC_FACTOR
    moveTo(cx - radius, cy)
    curveTo(cx - radius, cy - k, cx - k, cy - radius, cx, cy - radius)
    curveTo(cx + k, cy - radius, cx + radius, cy - k, cx + radius, cy)
    curveTo(cx + radius, cy + k, cx + k, cy + radius, cx, cy + radius)
    curveTo(cx - k, cy + radius, cx - radius, cy + k, cx - radius, cy)
    close()
}

/** 圆角矩形:四角同样用贝塞尔,圆角半径小到 1 也不糊。 */
private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
    val k = radius * ARC_FACTOR
    moveTo(left + radius, top)
    horizontalLineTo(right - radius)
    curveTo(right - radius + k, top, right, top + radius - k, right, top + radius)
    verticalLineTo(bottom - radius)
    curveTo(right, bottom - radius + k, right - radius + k, bottom, right - radius, bottom)
    horizontalLineTo(left + radius)
    curveTo(left + radius - k, bottom, left, bottom - radius + k, left, bottom - radius)
    verticalLineTo(top + radius)
    curveTo(left, top + radius - k, left + radius - k, top, left + radius, top)
    close()
}

/** 状态栏:四格信号,越高越强。 */
private fun signalIcon(): ImageVector = lineIcon("mp-signal") {
    strokePath(width = 2.2f) {
        moveTo(4f, 15.6f)
        lineTo(4f, 18.6f)
        moveTo(8.6f, 12.8f)
        lineTo(8.6f, 18.6f)
        moveTo(13.2f, 9.8f)
        lineTo(13.2f, 18.6f)
        moveTo(17.8f, 6.4f)
        lineTo(17.8f, 18.6f)
    }
}

/** 状态栏:Wi-Fi,三道弧加一个点。 */
private fun wifiIcon(): ImageVector = lineIcon("mp-wifi") {
    strokePath {
        moveTo(3f, 9.4f)
        curveTo(5.4f, 7.2f, 8.6f, 6f, 12f, 6f)
        curveTo(15.4f, 6f, 18.6f, 7.2f, 21f, 9.4f)
    }
    strokePath {
        moveTo(6.2f, 12.9f)
        curveTo(7.8f, 11.5f, 9.8f, 10.6f, 12f, 10.6f)
        curveTo(14.2f, 10.6f, 16.2f, 11.5f, 17.8f, 12.9f)
    }
    strokePath {
        moveTo(9.4f, 16.2f)
        curveTo(10.1f, 15.6f, 11f, 15.2f, 12f, 15.2f)
        curveTo(13f, 15.2f, 13.9f, 15.6f, 14.6f, 16.2f)
    }
    fillPath { circle(12f, 19.2f, 1.2f) }
}

/** 状态栏:电量外壳 + 电极 + 电量块。 */
private fun batteryIcon(): ImageVector = lineIcon("mp-battery") {
    strokePath(width = THIN_LINE_WIDTH) { roundedRect(2.6f, 7.4f, 19.2f, 16.6f, 2.8f) }
    fillPath { roundedRect(19.8f, 10.6f, 21.6f, 13.4f, 1.2f) }
    fillPath { roundedRect(4.6f, 9.4f, 14.2f, 14.6f, 1.8f) }
}

/** 消息:一个带左下尾巴的气泡。 */
private fun messageIcon(): ImageVector = lineIcon("mp-message") {
    strokePath {
        moveTo(5.6f, 4f)
        horizontalLineTo(18.4f)
        curveTo(19.5f, 4f, 20.4f, 4.9f, 20.4f, 6f)
        verticalLineTo(13.8f)
        curveTo(20.4f, 14.9f, 19.5f, 15.8f, 18.4f, 15.8f)
        horizontalLineTo(9.6f)
        lineTo(5.6f, 19.6f)
        verticalLineTo(15.8f)
        curveTo(4.5f, 15.8f, 3.6f, 14.9f, 3.6f, 13.8f)
        verticalLineTo(6f)
        curveTo(3.6f, 4.9f, 4.5f, 4f, 5.6f, 4f)
        close()
    }
}

/** 通讯录:一张联系人名片 —— 卡片轮廓里一个人形。 */
private fun contactsIcon(): ImageVector = lineIcon("mp-contacts") {
    strokePath { roundedRect(4f, 3.4f, 20f, 20.6f, 3.2f) }
    strokePath { circle(12f, 9.6f, 2.7f) }
    strokePath {
        moveTo(7.8f, 17.2f)
        curveTo(7.8f, 14.7f, 9.7f, 13.2f, 12f, 13.2f)
        curveTo(14.3f, 13.2f, 16.2f, 14.7f, 16.2f, 17.2f)
    }
}

/** 发现:指南针,外圈加一枚斜指的针。 */
private fun discoverIcon(): ImageVector = lineIcon("mp-discover") {
    strokePath { circle(12f, 12f, 8.4f) }
    fillPath {
        moveTo(15.9f, 8.1f)
        lineTo(13.4f, 13.4f)
        lineTo(8.1f, 15.9f)
        lineTo(10.6f, 10.6f)
        close()
    }
}

/** 我:单独一个人形,和通讯录名片区分开。 */
private fun meIcon(): ImageVector = lineIcon("mp-me") {
    strokePath { circle(12f, 7.8f, 3.4f) }
    strokePath {
        moveTo(5f, 19.6f)
        curveTo(5f, 15.9f, 8.1f, 13.4f, 12f, 13.4f)
        curveTo(15.9f, 13.4f, 19f, 15.9f, 19f, 19.6f)
    }
}

/** 朋友圈:一枚四角星,和指南针的圆盘不撞。 */
private fun momentsIcon(): ImageVector = lineIcon("mp-moments") {
    strokePath(width = 1.6f) {
        moveTo(12f, 3.4f)
        lineTo(13.9f, 10.1f)
        lineTo(20.6f, 12f)
        lineTo(13.9f, 13.9f)
        lineTo(12f, 20.6f)
        lineTo(10.1f, 13.9f)
        lineTo(3.4f, 12f)
        lineTo(10.1f, 10.1f)
        close()
    }
}

/** 列表右侧的指向箭头。 */
private fun chevronIcon(): ImageVector = lineIcon("mp-chevron") {
    strokePath(width = 1.6f) {
        moveTo(9.6f, 6.6f)
        lineTo(15f, 12f)
        lineTo(9.6f, 17.4f)
    }
}

/** 页头的「回桌面」按钮:一块屏幕加一条 Home 横线。 */
private fun homeIcon(): ImageVector = lineIcon("mp-home") {
    strokePath(width = THIN_LINE_WIDTH) { roundedRect(6.4f, 3.2f, 17.6f, 20.8f, 2.8f) }
    strokePath(width = 1.6f) {
        moveTo(9.8f, 17.4f)
        lineTo(14.2f, 17.4f)
    }
}

/** 页头的返回箭头:一条斜线加一道横线,与右侧的 [chevronIcon] 区分。 */
private fun backIcon(): ImageVector = lineIcon("mp-back") {
    strokePath(width = 1.8f) {
        moveTo(19f, 12f)
        horizontalLineTo(5.4f)
    }
    strokePath(width = 1.8f) {
        moveTo(11f, 5.4f)
        lineTo(4.6f, 12f)
        lineTo(11f, 18.6f)
    }
}

/** 发送:一枚朝右上的纸飞机。 */
private fun sendIcon(): ImageVector = lineIcon("mp-send") {
    strokePath(width = 1.7f) {
        moveTo(4.2f, 11.4f)
        lineTo(20.2f, 4.4f)
        lineTo(14.4f, 20.2f)
        lineTo(11.6f, 13.2f)
        close()
    }
    strokePath(width = 1.5f) {
        moveTo(11.6f, 13.2f)
        lineTo(20.2f, 4.4f)
    }
}

/** 图片入口:相框 + 太阳 + 一道山脊。 */
private fun imageIcon(): ImageVector = lineIcon("mp-image") {
    strokePath(width = THIN_LINE_WIDTH) { roundedRect(3.4f, 5.2f, 20.6f, 18.8f, 3f) }
    strokePath(width = 1.6f) { circle(9.2f, 10.2f, 1.7f) }
    strokePath(width = 1.6f) {
        moveTo(4.6f, 16.4f)
        lineTo(9.6f, 12.6f)
        lineTo(13.2f, 15.6f)
        lineTo(15.8f, 13.6f)
        lineTo(19.4f, 16.6f)
    }
}

/** 图片坏了:同一个相框,中间一道裂纹。 */
private fun brokenImageIcon(): ImageVector = lineIcon("mp-image-broken") {
    strokePath(width = THIN_LINE_WIDTH) { roundedRect(3.4f, 5.2f, 20.6f, 18.8f, 3f) }
    strokePath(width = 1.6f) {
        moveTo(8.2f, 5.6f)
        lineTo(11.8f, 10.4f)
        lineTo(9.6f, 12.8f)
        lineTo(13.6f, 15.2f)
        lineTo(11.6f, 18.4f)
    }
}

/** 语音条:播放,一个朝右的实心三角。 */
private fun playIcon(): ImageVector = lineIcon("mp-play") {
    fillPath {
        moveTo(9.4f, 6.2f)
        lineTo(17.8f, 12f)
        lineTo(9.4f, 17.8f)
        close()
    }
}

/** 语音条:暂停,两根竖条。 */
private fun pauseIcon(): ImageVector = lineIcon("mp-pause") {
    fillPath { roundedRect(8.2f, 6.4f, 11.1f, 17.6f, 1.1f) }
    fillPath { roundedRect(12.9f, 6.4f, 15.8f, 17.6f, 1.1f) }
}

/** 语音输入:一枚竖直话筒,底下托一道弧与短杆。 */
private fun micIcon(): ImageVector = lineIcon("mp-mic") {
    strokePath(width = 1.7f) { roundedRect(9.4f, 3.2f, 14.6f, 12.8f, 2.6f) }
    strokePath(width = 1.7f) {
        moveTo(6.2f, 11.2f)
        curveTo(6.2f, 15.6f, 8.8f, 18.2f, 12f, 18.2f)
        curveTo(15.2f, 18.2f, 17.8f, 15.6f, 17.8f, 11.2f)
    }
    strokePath(width = 1.7f) {
        moveTo(12f, 18.2f)
        lineTo(12f, 20.8f)
    }
}

/** 点赞:一枚心形,两条上弧收成一个尖。 */
private fun heartIcon(): ImageVector = lineIcon("mp-heart") {
    strokePath(width = 1.7f) {
        moveTo(12f, 20f)
        curveTo(9.4f, 17.8f, 4.4f, 14.4f, 4.4f, 10f)
        curveTo(4.4f, 7.3f, 6.4f, 5.2f, 8.9f, 5.2f)
        curveTo(10.4f, 5.2f, 11.5f, 6f, 12f, 6.9f)
        curveTo(12.5f, 6f, 13.6f, 5.2f, 15.1f, 5.2f)
        curveTo(17.6f, 5.2f, 19.6f, 7.3f, 19.6f, 10f)
        curveTo(19.6f, 14.4f, 14.6f, 17.8f, 12f, 20f)
        close()
    }
}

/** 建群入口:一个加号。两根一样长的线相交,与页头其他细线图标同一套笔触。 */
private fun plusIcon(): ImageVector = lineIcon("mp-plus") {
    strokePath(width = 1.8f) {
        moveTo(12f, 5.2f)
        lineTo(12f, 18.8f)
    }
    strokePath(width = 1.8f) {
        moveTo(5.2f, 12f)
        lineTo(18.8f, 12f)
    }
}

/** 已选中:一枚对勾。 */
private fun checkIcon(): ImageVector = lineIcon("mp-check") {
    strokePath(width = 2f) {
        moveTo(5.4f, 12.6f)
        lineTo(10f, 17.2f)
        lineTo(18.6f, 7f)
    }
}

/** 写一条:一枚斜放的笔,笔尖朝下。 */
private fun penIcon(): ImageVector = lineIcon("mp-pen") {
    strokePath(width = 1.7f) {
        moveTo(4.4f, 19.6f)
        lineTo(5.8f, 15.2f)
        lineTo(16.2f, 4.8f)
        curveTo(17.1f, 3.9f, 18.5f, 3.9f, 19.4f, 4.8f)
        curveTo(20.3f, 5.7f, 20.3f, 7.1f, 19.4f, 8f)
        lineTo(9f, 18.4f)
        close()
    }
    strokePath(width = 1.4f) {
        moveTo(14.8f, 6.2f)
        lineTo(17.8f, 9.2f)
    }
}

object MiniPhoneIcons {
    val Signal: ImageVector = signalIcon()
    val Wifi: ImageVector = wifiIcon()
    val Battery: ImageVector = batteryIcon()
    val Message: ImageVector = messageIcon()
    val Contacts: ImageVector = contactsIcon()
    val Discover: ImageVector = discoverIcon()
    val Me: ImageVector = meIcon()
    val Moments: ImageVector = momentsIcon()
    val Chevron: ImageVector = chevronIcon()
    val Home: ImageVector = homeIcon()
    val Back: ImageVector = backIcon()
    val Send: ImageVector = sendIcon()
    val Image: ImageVector = imageIcon()
    val BrokenImage: ImageVector = brokenImageIcon()
    val Play: ImageVector = playIcon()
    val Pause: ImageVector = pauseIcon()
    val Heart: ImageVector = heartIcon()
    val Pen: ImageVector = penIcon()
    val Mic: ImageVector = micIcon()
    val Plus: ImageVector = plusIcon()
    val Check: ImageVector = checkIcon()
}
