package io.zer0.muse.ui.account

import io.zer0.common.Logger

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * v2.x: 账户中心 — 本地个人资料编辑页(无登录/注册 UI)。
 *
 * 设计原则:
 *  - 无云服务依赖,所有资料本地存储(DataStore)
 *  - 用户可自由选择头像(相册图片)和昵称
 *  - 头像 URI 持久化,空昵称回退默认名
 *  - 保存后立即生效,AccountCard 等订阅 accountStateFlow 的 UI 自动刷新
 *
 * UI 结构:
 *  - 顶部:MuseTopBar("编辑个人资料" + 返回)
 *  - 主体:大头像(可点击更换) + 头像提示 + 昵称输入框 + 保存按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    /** v2.x: 点击进入设置迁移过来的"个人资料"(用户画像)页。 */
    onOpenUserProfile: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accountState by settings.accountStateFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.AccountState()
    )

    // 本地编辑态 — 进入页面时用当前 accountState 初始化
    var editName by remember(accountState.userName) { mutableStateOf(accountState.userName) }
    var editAvatarUri by remember(accountState.avatarUri) { mutableStateOf(accountState.avatarUri) }
    var saving by remember { mutableStateOf(false) }

    // 头像选择 launcher — Photo Picker,只选图片
    val pickAvatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            editAvatarUri = uri.toString()
        }
    }

    val initialName = accountState.userName
    val initialAvatar = accountState.avatarUri
    val hasChanges = editName.trim() != initialName.trim() || editAvatarUri != initialAvatar
    val canSave = hasChanges && editName.isNotBlank() && !saving

    Scaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.account_edit_profile),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = MusePaddings.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            // ── 头像(可点击更换) ──
            AvatarPicker(
                userName = editName,
                avatarUri = editAvatarUri,
                onClick = {
                    pickAvatarLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )
            Text(
                text = stringResource(R.string.account_change_avatar_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // ── 昵称输入 ──
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.account_name_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                MuseTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.account_name_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

            Spacer(Modifier.weight(1f))

            // ── 个人资料入口(从设置迁移过来) ──
            UserProfileEntryCard(
                onClick = onOpenUserProfile,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── 保存按钮 ──
            SaveButton(
                enabled = canSave,
                saving = saving,
                onClick = {
                    scope.launch {
                        saving = true
                        val profile = settings.getUserProfile()
                        // v1.0.74 fix: 头像 content URI 权限重启后失效(变灰色占位),
                        // 保存时拷贝到私有目录存本地路径
                        val persistentUri = persistAvatarUri(context, editAvatarUri)
                        if (!editAvatarUri.isNullOrBlank() && persistentUri.isNullOrBlank()) {
                            saving = false
                            MuseToast.show(
                                context.getString(R.string.assistant_detail_avatar_save_failed),
                                3000,
                            )
                            return@launch
                        }
                        settings.saveUserProfile(
                            profile.copy(
                                userNickName = editName.trim(),
                                avatarUri = persistentUri,
                            ),
                        )
                        saving = false
                        onBack()
                    }
                },
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

// ── 头像选择组件 ──────────────────────────────────────────

/**
 * v2.x: 个人资料(用户画像)入口卡片。
 *
 * 把原来设置页里的"用户画像/个人资料"功能迁移到账户页,
 * 点击后进入完整用户画像编辑页。
 */
@Composable
private fun UserProfileEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.account_user_profile_title),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.account_user_profile_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.account_user_profile_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 大号头像 + 右下角相机编辑徽标。
 *
 * @param userName 用于无头像时取首字母占位
 * @param avatarUri 头像 URI(null 时用首字母占位)
 * @param onClick 点击头像触发(打开相册)
 */
@Composable
private fun AvatarPicker(
    userName: String,
    avatarUri: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomEnd,
    ) {
        // 头像主体
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (!avatarUri.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = remember(avatarUri) { avatarImageModel(avatarUri) },
                        contentDescription = stringResource(R.string.account_avatar),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        error = {
                            Text(
                                text = userName.take(1).ifBlank { "M" },
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                    )
                } else {
                    Text(
                        text = userName.take(1).ifBlank { "M" },
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        // 相机编辑徽标(右下角)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.size(36.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = stringResource(R.string.account_change_avatar),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ── 保存按钮 ──────────────────────────────────────────

@Composable
private fun SaveButton(
    enabled: Boolean,
    saving: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.huge,
        color = if (enabled) MaterialTheme.colorScheme.inverseSurface
            else MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (saving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.account_loading),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.account_save),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

// ── 设置页顶部账户卡片 ──────────────────────────────────────

/**
 * 设置页顶部的账户卡片 — 左侧头像 + 右侧昵称 + "编辑个人资料" 提示。
 *
 * v2.x: 移除登录/注册概念,无论是否登录都显示本地个人资料。
 * 点击进入 [AccountScreen] 编辑头像和昵称。
 */
@Composable
fun AccountCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings: SettingsRepository = koinInject()
    val accountState by settings.accountStateFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.AccountState()
    )

    Surface(
        onClick = onClick,
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像(48dp)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (!accountState.avatarUri.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = remember(accountState.avatarUri) {
                                avatarImageModel(accountState.avatarUri.orEmpty())
                            },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            error = {
                                Text(
                                    text = accountState.userName.take(1).ifBlank { "M" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            },
                        )
                    } else {
                        Text(
                            text = accountState.userName.take(1).ifBlank { "M" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = accountState.userName.ifBlank { stringResource(R.string.account_default_user) },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.account_edit_profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** v1.0.74 fix: 把头像 URI 持久化到私有目录(content URI 权限重启后失效,头像变灰)。 */
private suspend fun persistAvatarUri(context: android.content.Context, uri: String?): String? {
    if (uri.isNullOrBlank()) return uri
    // 已是本地路径/资源/data URI 直接返回(纯文件路径不带 file://,Coil 更稳)
    if (uri.startsWith("/") || uri.startsWith("android.resource://") ||
        uri.startsWith("data:image/")
    ) {
        return uri
    }
    // 兼容旧值: file:// 前缀剥掉
    val normalized = uri.removePrefix("file://")
    if (normalized.startsWith("/")) return normalized
    return copyAvatarContentUri(context, Uri.parse(uri))
}

private suspend fun copyAvatarContentUri(
    context: android.content.Context,
    sourceUri: Uri,
): String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
    val resolver = context.contentResolver
    val dir = File(context.filesDir, "avatar")
    if (!dir.exists() && !dir.mkdirs()) {
        Logger.e("AccountScreen", "无法创建头像目录: ${dir.absolutePath}")
        return@withContext null
    }
    val extension = when (resolver.getType(sourceUri)?.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
    val target = File(dir, "avatar_${UUID.randomUUID()}.$extension")
    val temp = File(dir, "${target.name}.tmp")
    try {
        val input = resolver.openInputStream(sourceUri)
        if (input == null) {
            Logger.w("AccountScreen", "无法读取头像 URI: $sourceUri")
            return@withContext null
        }
        val copied = input.use { source ->
            temp.outputStream().use { output -> copyAvatarStream(source, output) }
        }
        if (!copied) {
            temp.delete()
            Logger.w("AccountScreen", "头像超过 ${MAX_AVATAR_BYTES / 1024 / 1024}MB 上限,已拒绝")
            return@withContext null
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            Logger.e("AccountScreen", "头像文件提交失败: ${target.absolutePath}")
            return@withContext null
        }
        target.absolutePath
    } catch (e: IOException) {
        temp.delete()
        Logger.e("AccountScreen", "头像复制失败", e)
        null
    } catch (e: SecurityException) {
        temp.delete()
        Logger.e("AccountScreen", "头像读取权限失败", e)
        null
    }
}

private fun copyAvatarStream(input: InputStream, output: OutputStream): Boolean {
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return true
        total += read
        if (total > MAX_AVATAR_BYTES) return false
        output.write(buffer, 0, read)
    }
}

/** C-20: 头像文件大小上限(与 AvatarStorage 一致)。 */
private const val MAX_AVATAR_BYTES = 10L * 1024 * 1024

/** 绝对路径交给 Coil 时使用 File,避免不同版本把纯路径误解析为无 scheme URI。 */
private fun avatarImageModel(uri: String): Any =
    when {
        uri.startsWith("/") -> File(uri)
        uri.startsWith("file://") -> Uri.parse(uri).path?.let(::File) ?: uri
        else -> uri
    }
