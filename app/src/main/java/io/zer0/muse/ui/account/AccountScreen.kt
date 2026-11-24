package io.zer0.muse.ui.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.AvatarStorage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.settings.SettingsFormScaffold
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.util.launchSafely
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 账户中心 — 本地个人资料编辑页（无登录/注册 UI）。
 *
 * 顶栏右侧提供紧凑保存按钮并在存在未保存改动时拦截返回；头像选择后立即落盘。
 */
@Composable
fun AccountScreen(onBack: () -> Unit, onOpenUserProfile: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accountState by settings.accountStateFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.AccountState(),
    )

    var editName by remember(accountState.userName) { mutableStateOf(accountState.userName) }
    var editAvatarUri by remember(accountState.avatarUri) { mutableStateOf(accountState.avatarUri) }
    var saving by remember { mutableStateOf(false) }
    var avatarSaving by remember { mutableStateOf(false) }

    val pickAvatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                avatarSaving = true
                try {
                    val persistentUri = AvatarStorage.persist(context, uri.toString())
                    if (persistentUri.isNullOrBlank()) {
                        MuseToast.show(
                            context.getString(R.string.assistant_detail_avatar_save_failed),
                            3000,
                        )
                    } else {
                        val profile = settings.getUserProfile()
                        settings.saveUserProfile(profile.copy(avatarUri = persistentUri))
                        editAvatarUri = persistentUri
                    }
                } finally {
                    avatarSaving = false
                }
            }
        }
    }

    val initialName = accountState.userName
    val initialAvatar = accountState.avatarUri
    val hasChanges = editName.trim() != initialName.trim() || editAvatarUri != initialAvatar
    val canSave = hasChanges && editName.isNotBlank() && !saving && !avatarSaving

    val performSave: () -> Unit = {
        scope.launch {
            saving = true
            val profile = settings.getUserProfile()
            val persistentUri = AvatarStorage.persist(context, editAvatarUri)
            val avatarWasChanged = editAvatarUri != profile.avatarUri
            if (avatarWasChanged && !editAvatarUri.isNullOrBlank() && persistentUri.isNullOrBlank()) {
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
                    avatarUri = persistentUri ?: profile.avatarUri,
                ),
            )
            saving = false
            onBack()
        }
    }

    SettingsFormScaffold(
        title = stringResource(R.string.account_edit_profile),
        dirty = hasChanges,
        saveEnabled = canSave,
        onSave = performSave,
        onDismiss = onBack,
    ) {
        item(key = "account_avatar") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MuseSpacing.md, bottom = MuseSpacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                AvatarPicker(
                    userName = editName,
                    avatarUri = editAvatarUri,
                    onClick = { pickAvatarLauncher.launchSafely("image/*", context) },
                )
                Text(
                    text = stringResource(R.string.account_change_avatar_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "account_name_card") {
            CardGroup {
                content(key = "account_name_field") {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        MuseTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.account_name_label)) },
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
                }
            }
        }

        item(key = "account_profile_entry") {
            CardGroup {
                navigationItem(
                    key = "user_profile",
                    title = stringResource(R.string.account_user_profile_title),
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = onOpenUserProfile,
                )
            }
        }
    }
}

/**
 * 大号头像 + 右下角相机编辑徽标。
 */
@Composable
private fun AvatarPicker(userName: String, avatarUri: String?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(AVATAR_PICKER_SIZE)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(AVATAR_PICKER_SIZE),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (!avatarUri.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = remember(avatarUri) { AvatarStorage.imageModel(avatarUri) },
                        contentDescription = stringResource(R.string.account_avatar),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        error = {
                            Text(
                                text = userName.take(1).ifBlank { "M" },
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                    )
                } else {
                    Text(
                        text = userName.take(1).ifBlank { "M" },
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.size(AVATAR_BADGE_SIZE),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.camera,
                    contentDescription = stringResource(R.string.account_change_avatar),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
        }
    }
}

/**
 * 设置页顶部的账户卡片 — 左侧头像 + 右侧昵称 + "编辑个人资料" 提示。
 */
@Composable
fun AccountCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val settings: SettingsRepository = koinInject()
    val accountState by settings.accountStateFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.AccountState(),
    )

    CardGroup(modifier = modifier.fillMaxWidth()) {
        item(
            key = "account_card",
            onClick = onClick,
            leadingContent = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(ACCOUNT_CARD_AVATAR_SIZE),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!accountState.avatarUri.isNullOrBlank()) {
                            SubcomposeAsyncImage(
                                model = remember(accountState.avatarUri) {
                                    AvatarStorage.imageModel(accountState.avatarUri.orEmpty())
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
            },
            headlineContent = {
                Text(text = accountState.userName.ifBlank { stringResource(R.string.account_default_user) })
            },
            supportingContent = {
                Text(text = stringResource(R.string.account_edit_profile))
            },
            trailingContent = { ChevronRight() },
        )
    }
}

private val AVATAR_PICKER_SIZE = 112.dp
private val AVATAR_BADGE_SIZE = 34.dp
private val ACCOUNT_CARD_AVATAR_SIZE = 44.dp
