package io.zer0.muse.ui.artifact

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.HtmlPreviewScreen
import io.zer0.muse.ui.common.feedback.MuseToast

/**
 * v2.0.1: 产物打开宿主 — 富内容（HTML/SVG/Chart/Mermaid）走全屏浏览器形态，
 * 其余（代码/文本）走弹窗查看器。聊天页与产物中心共用同一分流。
 */
@Composable
fun ArtifactOpenHost(
    artifact: ArtifactEntity?,
    onDismiss: () -> Unit,
) {
    if (artifact == null) return
    val context = LocalContext.current
    val richLang = richPreviewLanguage(artifact.type, artifact.language)
    if (richLang != null) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            HtmlPreviewScreen(
                html = artifact.content,
                title = artifact.title.ifBlank { null },
                onBack = onDismiss,
            )
        }
    } else {
        ArtifactViewerDialog(
            artifact = artifact,
            onDismiss = onDismiss,
            onCopy = { text -> copyArtifactText(context, text) },
        )
    }
}

/** 复制产物文本到剪贴板 + Toast 反馈（聊天页与产物中心共用）。 */
internal fun copyArtifactText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Muse Artifact", text))
    MuseToast.show(context.getString(R.string.chat_copied_toast))
}
