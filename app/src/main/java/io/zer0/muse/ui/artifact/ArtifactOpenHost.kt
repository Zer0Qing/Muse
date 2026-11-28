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
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.screen.HtmlPreviewScreen

/**
 * 产物打开宿主 — 统一分流：
 *  - 富内容（HTML/SVG/Chart/Mermaid）走全屏交互浏览宿主；
 *  - 其余（Markdown/代码/JSON/文档/图片/文本）走全屏阅读器或图片浏览器。
 */
@Composable
fun ArtifactOpenHost(
    artifact: ArtifactEntity?,
    onDismiss: () -> Unit,
    onOpenSession: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
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
            MuseDialogWindowEffect(forceFullScreen = true)
            HtmlPreviewScreen(
                html = artifact.content,
                language = richLang,
                title = artifact.title.ifBlank { null },
                onBack = onDismiss,
            )
        }
    } else {
        ArtifactViewerDialog(
            artifact = artifact,
            onDismiss = onDismiss,
            onCopy = { text -> copyArtifactText(context, text) },
            onOpenSession = onOpenSession,
            onDelete = onDelete,
            onRename = onRename,
        )
    }
}

/** 复制产物文本到剪贴板 + Toast 反馈（聊天页与产物库共用）。 */
internal fun copyArtifactText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Muse Artifact", text))
    MuseToast.show(context.getString(R.string.chat_copied_toast))
}
