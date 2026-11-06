package io.zer0.muse.ui.workspace

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.workspace.WorkspaceLocation

/**
 * 工作区位置卡片（工作区页顶部）。
 *
 * 起因：工作区默认在 `filesDir/workspace`，那是任何文件管理器都进不去的私有目录 ——
 * 用户想看看里面有什么、想用电脑拷走、想拿别的应用处理，全都做不到，也不知道去哪儿找。
 *
 * 这里给三件事：**现在在哪儿**、**怎么变成看得见的位置**、**怎么复制路径**。
 *
 * 迁移必须重启才生效：工作区根目录在应用启动时就被各处持有（终端、文件工具、挂载表），
 * 中途换路径会让正在用的那批对象指向旧目录 —— 那种"一半新一半旧"的状态比不搬更糟。
 */
@Composable
fun WorkspaceLocationCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 迁移结果只影响这一屏的提示文案，所以放在局部状态里，不往上抬
    var message by remember { mutableStateOf<String?>(null) }
    var needsRestart by remember { mutableStateOf(false) }

    val prefersDownloads = remember { WorkspaceLocation.prefersDownloads(context) }
    val usingDownloads = remember { prefersDownloads && WorkspaceLocation.hasBroadStorageAccess(context) }
    val currentPath = remember { WorkspaceLocation.resolve(context).absolutePath }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = stringResource(R.string.workspace_location_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(
                if (usingDownloads) R.string.workspace_location_downloads else R.string.workspace_location_internal,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = currentPath,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MuseMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
            if (!usingDownloads) {
                MuseCapsuleButton(
                    text = stringResource(R.string.workspace_location_move_downloads),
                    onClick = {
                        if (!WorkspaceLocation.hasBroadStorageAccess(context)) {
                            // 没权限就别装作能搬：直接把用户送到系统授权页
                            val intent = WorkspaceLocation.requestBroadStorageAccessIntent(context)
                            if (intent != null) {
                                context.startActivity(intent)
                                message = context.getString(R.string.workspace_location_need_permission)
                            } else {
                                message = context.getString(R.string.workspace_location_need_permission)
                            }
                        } else {
                            when (val result = WorkspaceLocation.migrateToDownloads(context)) {
                                is WorkspaceLocation.Migration.Moved -> {
                                    message = context.getString(R.string.workspace_location_moved, result.files)
                                    needsRestart = true
                                }
                                is WorkspaceLocation.Migration.AlreadyThere -> {
                                    message = context.getString(R.string.workspace_location_moved, 0)
                                    needsRestart = true
                                }
                                is WorkspaceLocation.Migration.Failed -> {
                                    message = context.getString(R.string.workspace_location_failed, result.reason)
                                }
                            }
                        }
                    },
                )
            } else {
                MuseCapsuleButton(
                    text = stringResource(R.string.workspace_location_move_internal),
                    onClick = {
                        when (val result = WorkspaceLocation.migrateBackInternal(context)) {
                            is WorkspaceLocation.Migration.Failed ->
                                message = context.getString(R.string.workspace_location_failed, result.reason)
                            else -> {
                                message = context.getString(R.string.workspace_location_moved, 0)
                                needsRestart = true
                            }
                        }
                    },
                )
            }
            MuseCapsuleButton(
                text = stringResource(R.string.workspace_location_copy_path),
                onClick = { copyPath(context, currentPath) },
            )
            if (needsRestart) {
                MuseCapsuleButton(
                    text = stringResource(R.string.workspace_location_restart),
                    onClick = {
                        (context as? Activity)?.finishAffinity()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                )
            }
        }
        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (needsRestart) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

private fun copyPath(context: Context, path: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("workspace", path))
        MuseToast.show(path)
    }
}
