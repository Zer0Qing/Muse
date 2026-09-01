package io.zer0.muse.ui

import io.zer0.muse.ui.theme.MuseMotion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import compose.icons.TablerIcons
import compose.icons.tablericons.X
import io.zer0.ai.image.ImageGenParams
import io.zer0.ai.image.ImageModelCatalog
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 录音波形条:把最近振幅历史渲染成竖条。
 * v1.91: 振幅改为归一化 Float(0-1f),无需再除以 32768。
 */
@Composable
internal fun RecordingWaveform(amplitudes: List<Float>) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.height(MuseIconSizes.waveformHeight),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        amplitudes.forEach { amp ->
            // v1.91: amp 已是 0-1f 归一化值,直接 coerceIn 即可
            val fraction = amp.coerceIn(0.05f, 1f)
            val animatedHeight by animateFloatAsState(
                targetValue = fraction,
                animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
                label = "wave",
            )
            Box(
                modifier = Modifier
                    .width(MuseIconSizes.waveformBarWidth)
                    .fillMaxHeight(animatedHeight)
                    .clip(RoundedCornerShape(MuseIconSizes.waveformBarRadius))
                    .background(primary.copy(alpha = 0.7f)),
            )
        }
    }
}

/**
 * v0.35: 绘图模式参数面板 — 尺寸/质量/风格 + 参考图临时覆盖。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageGenParamsPanel(
    params: ImageGenParams,
    onParamsChange: (ImageGenParams) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            scope.launch {
                // v1.79 (H-I4): 大图片读取 + Base64 编码移到 IO 线程,避免阻塞主线程
                // v1.140: 选图后自动压缩到 1024px 内 + JPEG 85,限制 base64 后 <= 4MB,
                //         避免原图 10MB+ 直传导致 OOM 和请求超时
                withContext(Dispatchers.IO) {
                    runCatching {
                        compressReferenceImageToDataUri(uri = it, context = context)
                    }
                }.onSuccess { result ->
                    onParamsChange(params.copy(referenceImageUri = result.dataUri))
                    // UI 提示压缩后的尺寸(透明体验)
                    MuseToast.show(
                        context.getString(R.string.chat_ref_image_compressed, result.describe()),
                        2500,
                    )
                }.onFailure { e ->
                    // v1.79 (H-I4+M-I2): 加 onFailure 提示
                    MuseToast.show(context.getString(R.string.chat_ref_image_load_failed, e.message ?: ""))
                }
            }
        }
    }

    val model = remember(params.model) { ImageModelCatalog.resolveById(params.model) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MusePaddings.contentGap)
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(MusePaddings.cardInnerAux),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_draw_params),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            model?.let {
                Text(
                    text = it.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 尺寸
        val sizes = model?.supportedSizes
        if (!sizes.isNullOrEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
            ) {
                sizes.map { it to it }.forEach { (value, label) ->
                    MuseChip(
                        selected = params.size == value,
                        onClick = { onParamsChange(params.copy(size = value)) },
                        label = label,
                    )
                }
            }
        }

        // 质量
        val qualities = model?.supportedQualities
        if (!qualities.isNullOrEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
            ) {
                qualities.forEach { value ->
                    val label = when (value) {
                        "standard" -> stringResource(R.string.chat_quality_standard)
                        "hd" -> stringResource(R.string.chat_quality_hd)
                        "high" -> stringResource(R.string.chat_quality_high)
                        "medium" -> stringResource(R.string.chat_quality_medium)
                        "low" -> stringResource(R.string.chat_quality_low)
                        "auto" -> stringResource(R.string.chat_quality_auto)
                        else -> value
                    }
                    MuseChip(
                        selected = params.quality == value,
                        onClick = { onParamsChange(params.copy(quality = value)) },
                        label = label,
                    )
                }
            }
        }

        // 风格
        val styles = model?.supportedStyles
        if (!styles.isNullOrEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
            ) {
                styles.forEach { value ->
                    val label = when (value) {
                        "vivid" -> stringResource(R.string.chat_style_vivid)
                        "natural" -> stringResource(R.string.chat_style_natural)
                        else -> value
                    }
                    MuseChip(
                        selected = params.style == value,
                        onClick = { onParamsChange(params.copy(style = value)) },
                        label = label,
                    )
                }
            }
        }

        // 参考图
        val supportsRef = model?.supportsReferenceImage == true
        if (params.referenceImageUri.isNullOrBlank()) {
            MuseChip(
                selected = false,
                onClick = {
                    if (supportsRef) imagePicker.launch("image/*")
                },
                enabled = supportsRef,
                label = if (supportsRef) stringResource(R.string.chat_ref_image_add) else stringResource(R.string.chat_ref_image_not_supported),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Photo,
                        // v1.79 (L-I3): 无障碍 contentDescription
                        contentDescription = stringResource(R.string.chat_ref_image_cd),
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = MusePaddings.maxInlineWidth)
                    .clip(MuseShapes.small),
            ) {
                SmartImage(
                    model = params.referenceImageUri,
                    contentDescription = stringResource(R.string.chat_ref_image_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                )
                IconButton(
                    onClick = { onParamsChange(params.copy(referenceImageUri = null)) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(MuseIconSizes.touchTarget)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = TablerIcons.X,
                        contentDescription = stringResource(R.string.chat_ref_image_clear_cd),
                        modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                    )
                }
            }
        }
    }
}

/**
 * v1.140: 参考图压缩结果。
 *
 * @property dataUri 形如 `data:image/jpeg;base64,...` 的 Data URI,可直接交给 ImageProvider
 * @property width 压缩后宽度
 * @property height 压缩后高度
 * @property byteCount 压缩后 JPEG 字节数(未 base64)
 */
private data class CompressedReferenceImage(
    val dataUri: String,
    val width: Int,
    val height: Int,
    val byteCount: Int,
) {
    /** 人类可读的尺寸/体积描述,用于 Toast 提示。 */
    fun describe(): String {
        val kb = byteCount / 1024
        return "${width}x${height}, ${kb}KB"
    }
}

/**
 * v1.140: 把用户选中的参考图 URI 压缩为符合体积/尺寸约束的 Data URI。
 *
 * 处理流程:
 *  1. 先解码边界获取原始尺寸(API 28+ 用 ImageDecoder,低版本用 BitmapFactory)
 *  2. 计算 inSampleSize,使长边缩到 [maxSide] 附近(2 的幂次降采样)
 *  3. 解码得到 Bitmap 后,如长边仍 > [maxSide],用 Matrix 精确缩放
 *  4. JPEG 压缩质量 [quality],base64 后如仍超过 [maxBase64Bytes],则
 *     逐级降质量 / 缩尺寸,直到满足体积约束或降到下限
 *
 * 抛出 [IllegalStateException] 表示压缩失败(原图无法解码或压缩后仍过大)。
 */
private fun compressReferenceImageToDataUri(
    uri: Uri,
    context: android.content.Context,
    maxSide: Int = 1024,
    quality: Int = 85,
    maxBase64Bytes: Int = 4 * 1024 * 1024,
): CompressedReferenceImage {
    val resolver = context.contentResolver

    // 1. 解码原始尺寸
    val (origW, origH) = decodeImageBounds(resolver, uri)
    if (origW <= 0 || origH <= 0) {
        error("decode bounds failed for $uri")
    }

    // 2. 计算 inSampleSize(2 的幂次,使降采样后长边尽量接近 maxSide 但不超过 2 倍)
    var sample = 1
    while (origW / sample / 2 >= maxSide || origH / sample / 2 >= maxSide) sample *= 2

    // 3. 解码为 Bitmap(降采样后)
    var bitmap = decodeSampledBitmap(resolver, uri, sample)
        ?: error("decode bitmap failed for $uri")

    // 4. 精确缩放到 maxSide 内(保持宽高比)
    val scaled = scaleBitmapToMaxSide(bitmap, maxSide)
    if (scaled !== bitmap) {
        bitmap.recycle()
        bitmap = scaled
    }

    // 5. 逐级压缩,直到 base64 体积满足约束或降到下限
    var currentQuality = quality
    var currentBmp = bitmap
    var bytes = compressJpeg(currentBmp, currentQuality)
    var base64Len = base64Length(bytes.size)

    // 5.1 先尝试只降质量(75 → 65 → 55)
    val qualitySteps = listOf(75, 65, 55)
    var stepIndex = 0
    while (base64Len > maxBase64Bytes && stepIndex < qualitySteps.size) {
        currentQuality = qualitySteps[stepIndex++]
        bytes = compressJpeg(currentBmp, currentQuality)
        base64Len = base64Length(bytes.size)
    }

    // 5.2 仍超限则缩小尺寸(768 → 512 → 384)
    val sideSteps = listOf(768, 512, 384)
    var sideIndex = 0
    while (base64Len > maxBase64Bytes && sideIndex < sideSteps.size) {
        val newSide = sideSteps[sideIndex++]
        val shrunk = scaleBitmapToMaxSide(currentBmp, newSide)
        if (shrunk !== currentBmp) {
            currentBmp.recycle()
            currentBmp = shrunk
        }
        bytes = compressJpeg(currentBmp, currentQuality)
        base64Len = base64Length(bytes.size)
    }

    val width = currentBmp.width
    val height = currentBmp.height
    currentBmp.recycle()

    if (base64Len > maxBase64Bytes) {
        // 仍超限:拒绝上传,避免 OOM/超时
        error("image still too large after compression (${width}x${height}, ${bytes.size / 1024}KB)")
    }

    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    val dataUri = "data:image/jpeg;base64,$base64"
    return CompressedReferenceImage(
        dataUri = dataUri,
        width = width,
        height = height,
        byteCount = bytes.size,
    )
}

/** 解码原图边界(宽高),不将像素加载到内存。 */
private fun decodeImageBounds(
    resolver: android.content.ContentResolver,
    uri: Uri,
): Pair<Int, Int> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
    return opts.outWidth to opts.outHeight
}

/** 按 inSampleSize 解码 Bitmap。 */
private fun decodeSampledBitmap(
    resolver: android.content.ContentResolver,
    uri: Uri,
    sampleSize: Int,
): Bitmap? {
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
}

/** 把 Bitmap 等比缩放到长边 <= maxSide;若已满足则原样返回。 */
private fun scaleBitmapToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
    val w = src.width
    val h = src.height
    val longSide = maxOf(w, h)
    if (longSide <= maxSide) return src
    val scale = maxSide.toFloat() / longSide
    val newW = (w * scale).toInt().coerceAtLeast(1)
    val newH = (h * scale).toInt().coerceAtLeast(1)
    val matrix = Matrix().apply { setScale(scale, scale) }
    return Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
}

/** JPEG 压缩为字节数组。 */
private fun compressJpeg(bmp: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** base64 编码后体积约为原字节 * 4/3,向上取整。 */
private fun base64Length(byteCount: Int): Int {
    // 每 3 字节 → 4 字符;不足 3 按 3 算。NO_WRAP 不加换行符。
    return ((byteCount + 2) / 3) * 4
}
