package io.zer0.muse.ui.qrcode

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import io.zer0.muse.R
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.97: 二维码分享弹窗 — 显示 Provider 配置的二维码。
 *
 * 生成二维码 Bitmap 并居中显示,提示用户"包含 apiKey,请谨慎分享"。
 *
 * @param content 编码后的二维码字符串(由 QrCodeGenerator.encodeProvider 生成)
 * @param onDismiss 关闭回调
 */
@Composable
fun QrCodeShareDialog(
    content: String,
    onDismiss: () -> Unit,
) {
    var bitmap by remember(content) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(content) {
        bitmap = withContext(Dispatchers.Default) {
            QrCodeGenerator.generateQrBitmap(content, QrCodeGenerator.DEFAULT_QR_SIZE)
        }
    }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.qr_share_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_share_cd),
                        modifier = Modifier.size(280.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(280.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    text = stringResource(R.string.qr_share_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmText = stringResource(R.string.assistant_detail_done),
        onConfirm = onDismiss,
    )
}

/**
 * v1.97: 二维码扫描弹窗 — 从相册选图片识别二维码。
 *
 * 调用系统图片选择器,选中后用 ML Kit 识别二维码,
 * 成功且为 Provider 格式时回调 onResult。
 *
 * @param onDismiss 关闭回调
 * @param onResult 扫描成功回调,返回解析后的二维码原始内容
 */
@Composable
fun QrCodeScanDialog(
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        scanning = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // v1.114: 用全局 ImageLoader 单例(coil.imageLoader 扩展属性),
                    // 替代每次 new ImageLoader(context),避免绕过内存/磁盘缓存。
                    // 同时加 .size(1024) 约束,防止大图全分辨率解码(扫码只需 1024px 足够)。
                    val bmp = context.imageLoader.execute(
                        ImageRequest.Builder(context).data(uri).size(1024).build()
                    ).drawable?.toBitmap()
                    if (bmp != null) QrCodeScanner.scanFromBitmap(bmp) else null
                }.getOrNull()
            }
            scanning = false
            if (result != null) {
                onResult(result)
            } else {
                MuseToast.show(context.getString(R.string.qr_scan_failed))
                onDismiss()
            }
        }
    }

    LaunchedEffect(Unit) {
        pickImage.launch("image/*")
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.qr_scan_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (scanning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = if (scanning) stringResource(R.string.qr_scan_scanning)
                    else stringResource(R.string.qr_scan_pick_image),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmText = stringResource(R.string.qr_scan_pick_button),
        onConfirm = { pickImage.launch("image/*") },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

/**coil 的 Drawable.toBitmap() 在 coil3 中变更了 API,这里用兼容写法。*/
private fun android.graphics.drawable.Drawable.toBitmap(): Bitmap {
    return if (this is android.graphics.drawable.BitmapDrawable) {
        bitmap
    } else {
        val width = if (intrinsicWidth > 0) intrinsicWidth else 1
        val height = if (intrinsicHeight > 0) intrinsicHeight else 1
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bmp
    }
}
