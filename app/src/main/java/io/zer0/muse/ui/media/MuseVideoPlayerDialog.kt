package io.zer0.muse.ui.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.media.MediaAssetStorage
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * In-app fullscreen video player dialog powered by Media3 ExoPlayer.
 * Supports local `file://`, remote `http(s)://`, `content://`, and `data:` URIs with save/share/external actions.
 */
@OptIn(UnstableApi::class)
@Composable
fun MuseVideoPlayerDialog(
    videoUri: String,
    promptSummary: String? = null,
    onDismiss: () -> Unit,
    storage: MediaAssetStorage = koinInject(),
    isFavorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var preparedUri by remember(videoUri) { mutableStateOf<Uri?>(null) }
    var prepareError by remember(videoUri) { mutableStateOf<String?>(null) }
    var playbackError by remember(videoUri) { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(videoUri) {
        preparedUri = null
        prepareError = null
        playbackError = null
        val resolved = withContext(Dispatchers.IO) {
            resolvePlayableVideoUri(context, videoUri)
        }
        if (resolved == null) {
            prepareError = context.getString(R.string.media_studio_video_prepare_failed)
        } else {
            preparedUri = resolved
        }
    }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Logger.w("MuseVideoPlayerDialog", "ExoPlayer error: ${error.message}")
                playbackError = error.localizedMessage ?: error.errorCodeName
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        exoPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(preparedUri, exoPlayer) {
        val uri = preparedUri ?: return@LaunchedEffect
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    prepareError != null || playbackError != null -> {
                        Text(
                            text = prepareError ?: playbackError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(MuseSpacing.lg),
                        )
                    }
                    preparedUri == null -> {
                        MuseSpinner(modifier = Modifier.align(Alignment.Center))
                    }
                    else -> {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = true
                                }
                            },
                            update = { view ->
                                if (view.player !== exoPlayer) {
                                    view.player = exoPlayer
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Top control bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                        .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        onClick = onDismiss,
                        contentDescription = stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        size = MuseIconSizes.touchTarget,
                    )
                    Text(
                        text = stringResource(R.string.media_studio_player_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.download,
                        onClick = {
                            if (isSaving) return@MuseTactileButton
                            isSaving = true
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    ensureLocalVideoFile(context, videoUri)
                                }
                                val res = if (file != null) {
                                    storage.saveToSystemGallery(file, isVideo = true)
                                } else {
                                    Result.failure(IllegalStateException("unavailable"))
                                }
                                isSaving = false
                                res.onSuccess {
                                    MuseToast.show(context.getString(R.string.media_studio_saved_to_gallery))
                                }.onFailure { e ->
                                    MuseToast.show(
                                        context.getString(
                                            R.string.media_studio_save_failed,
                                            e.message ?: "",
                                        ),
                                    )
                                }
                            }
                        },
                        contentDescription = stringResource(R.string.media_studio_save_to_gallery),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        size = MuseIconSizes.touchTarget,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.share,
                        onClick = {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    ensureLocalVideoFile(context, videoUri)
                                }
                                if (file == null || !storage.shareLocalFile(context, file, "video/mp4")) {
                                    MuseToast.show(context.getString(R.string.chat_share_failed_no_uri))
                                }
                            }
                        },
                        contentDescription = stringResource(R.string.common_share),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        size = MuseIconSizes.touchTarget,
                    )
                    if (onToggleFavorite != null && isFavorite != null) {
                        MuseTactileButton(
                            icon = MuseIcons.star,
                            onClick = onToggleFavorite,
                            contentDescription = stringResource(R.string.media_studio_favorite_cd),
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                            size = MuseIconSizes.touchTarget,
                        )
                    }
                    if (onDelete != null) {
                        MuseTactileButton(
                            icon = MuseIcons.trash,
                            onClick = onDelete,
                            contentDescription = stringResource(R.string.media_studio_delete_asset_cd),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            size = MuseIconSizes.touchTarget,
                        )
                    }
                    MuseTactileButton(
                        icon = MuseIcons.externalLink,
                        onClick = {
                            val target = preparedUri ?: return@MuseTactileButton
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(target, "video/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.onFailure { e ->
                                MuseToast.show(context.getString(R.string.chat_video_open_failed, e.message ?: ""))
                            }
                        },
                        contentDescription = stringResource(R.string.media_studio_open_external_player),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        size = MuseIconSizes.touchTarget,
                    )
                }

                if (!promptSummary.isNullOrBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                            .padding(MuseSpacing.md),
                    ) {
                        Text(
                            text = promptSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.08f),
                                    MuseShapes.small,
                                )
                                .padding(MuseSpacing.sm),
                        )
                    }
                } else {
                    Spacer(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    )
                }
            }
        }
    }
}

private fun resolvePlayableVideoUri(context: Context, videoUri: String): Uri? {
    val trimmed = videoUri.trim()
    if (trimmed.isEmpty()) return null
    return when {
        trimmed.startsWith("data:") -> {
            val file = decodeDataUriToCache(context, trimmed) ?: return null
            Uri.fromFile(file)
        }
        trimmed.startsWith("file://") -> Uri.parse(trimmed)
        trimmed.startsWith("/") -> Uri.fromFile(File(trimmed))
        else -> runCatching { Uri.parse(trimmed) }.getOrNull()
    }
}

internal fun resolveVideoToLocalFile(context: Context, videoUri: String): File? {
    ensureLocalVideoFile(context, videoUri)?.let { return it }
    val trimmed = videoUri.trim()
    if (trimmed.startsWith("content://")) {
        return runCatching {
            val uri = Uri.parse(trimmed)
            val file = File(context.cacheDir, "muse_video_${trimmed.hashCode()}.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return runCatching {
            val file = File(context.cacheDir, "muse_video_dl_${trimmed.hashCode()}.mp4")
            if (file.exists() && file.length() > 0L) return@runCatching file
            val conn = (java.net.URL(trimmed).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 25_000
            }
            conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }
    return null
}

private fun ensureLocalVideoFile(context: Context, videoUri: String): File? {
    val trimmed = videoUri.trim()
    return when {
        trimmed.startsWith("data:") -> decodeDataUriToCache(context, trimmed)
        trimmed.startsWith("file://") -> File(trimmed.removePrefix("file://")).takeIf { it.exists() }
        trimmed.startsWith("/") -> File(trimmed).takeIf { it.exists() }
        else -> null
    }
}

private fun decodeDataUriToCache(context: Context, dataUri: String): File? {
    val b64 = dataUri.substringAfter("base64,", "").replace("\n", "").replace("\r", "")
    if (b64.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val file = File(context.cacheDir, "muse_video_${dataUri.hashCode()}.mp4")
        file.writeBytes(bytes)
        file
    }.getOrNull()
}
