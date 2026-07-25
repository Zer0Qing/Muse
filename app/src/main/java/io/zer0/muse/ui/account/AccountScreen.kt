package io.zer0.muse.ui.account

import android.util.Patterns
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.util.MusePatterns
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.27: 账户中心 — 拟真登录/注册页(本地桩,接口预留)。
 *
 * 设计原则:
 *  - 登录/注册 UI 完整可用,验证逻辑齐全,但实际不连接服务器
 *  - 登录后调用 settings.mockLogin() 写入本地状态
 *  - 登出后调用 settings.logout() 清除本地状态
 *  - 后续接入服务器时,只需替换 mockLogin/logout 内部实现,UI 无需改动
 *  - 第三方登录入口预留(微信/QQ/Google),点击后模拟 loading 然后登录
 *
 * UI 结构:
 *  - 未登录:SegmentedControl 切换登录/注册 + 表单 + 第三方登录 + 游客模式
 *  - 已登录:用户卡片(头像+用户名+登录方式+登录时间)+ 云同步入口 + 登出按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val accountState by settings.accountStateFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.AccountState()
    )

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.account_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (accountState.isLoggedIn) {
            // 已登录 — 显示用户信息 + 登出
            LoggedInView(
                accountState = accountState,
                onLogout = {
                    scope.launch { settings.logout() }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            // 未登录 — 显示登录/注册表单
            LoginRegisterView(
                onAuthed = { userName, method ->
                    scope.launch { settings.mockLogin(userName, method) }
                },
                onGuestMode = {
                    scope.launch { settings.enterGuestMode() }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

// ── 已登录视图 ──────────────────────────────────────────

@Composable
private fun LoggedInView(
    accountState: io.zer0.muse.data.AccountState,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // v1.48: 登出二次确认,与 SettingsSubPages 的登出确认行为一致
    var showLogoutConfirm by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // 用户信息卡片
        Surface(
            shape = MuseShapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 头像
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = accountState.userName.take(1).ifBlank { "M" },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = accountState.userName.ifBlank { stringResource(R.string.account_default_user) },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // v1.71: 用 remember 缓存 SimpleDateFormat(放在 if 外,避免条件 remember)
                val loginFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }
                if (accountState.loginAt > 0) {
                    val dateStr = loginFmt.format(Date(accountState.loginAt))
                    Text(
                        text = stringResource(R.string.account_login_method, loginMethodLabel(accountState.loginMethod), dateStr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 云同步入口(预留,接入服务器后可用)
        Surface(
            shape = MuseShapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.account_more_features),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AccountFeatureRow(
                    icon = Icons.Outlined.Cloud,
                    title = stringResource(R.string.account_cloud_sync),
                    subtitle = stringResource(R.string.account_cloud_sync_desc),
                )
            }
        }

        // 登出按钮
        Surface(
            shape = MuseShapes.huge,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clickable(onClick = { showLogoutConfirm = true }),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.account_logout),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Spacer(Modifier.weight(1f))
    }

    // v1.48: 登出二次确认弹窗
    if (showLogoutConfirm) {
        MuseDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = stringResource(R.string.account_logout),
            content = { Text(stringResource(R.string.account_logout_confirm)) },
            confirmText = stringResource(R.string.account_logout_btn),
            onConfirm = {
                showLogoutConfirm = false
                onLogout()
            },
            destructive = true,
        )
    }
}

// ── 登录/注册视图 ──────────────────────────────────────

@Composable
private fun LoginRegisterView(
    onAuthed: (userName: String, method: String) -> Unit,
    onGuestMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var mode by remember { mutableStateOf(AuthMode.LOGIN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var confirmPasswordError by remember { mutableStateOf<String?>(null) }

    // 预提取错误消息字符串(lambda 中无法直接调用 stringResource)
    val errEmailInvalid = stringResource(R.string.account_err_email_invalid)
    val errPasswordTooShort = stringResource(R.string.account_err_password_too_short)
    val errPasswordMismatch = stringResource(R.string.account_err_password_mismatch)
    val errEmailRequired = stringResource(R.string.account_err_email_required)
    val errPasswordRequired = stringResource(R.string.account_err_password_required)
    val errUsernameRequired = stringResource(R.string.account_err_username_required)
    val errPasswordMismatchFull = stringResource(R.string.account_err_password_mismatch_full)

    val doLogin: () -> Unit = {
        val err = validateLogin(email, password, errEmailRequired, errEmailInvalid, errPasswordRequired)
        if (err != null) {
            error = err
        } else if (!loading) {
            keyboard?.hide()
            loading = true
            error = null
            scope.launch {
                delay(1000) // 模拟网络请求
                onAuthed(email.substringBefore("@"), "email")
                loading = false
            }
        }
    }
    val doRegister: () -> Unit = {
        val err = validateRegister(username, email, password, confirmPassword, errUsernameRequired, errEmailRequired, errEmailInvalid, errPasswordRequired, errPasswordTooShort, errPasswordMismatchFull)
        if (err != null) {
            error = err
        } else if (!loading) {
            keyboard?.hide()
            loading = true
            error = null
            scope.launch {
                delay(1000) // 模拟网络请求
                onAuthed(username.ifBlank { email.substringBefore("@") }, "email")
                loading = false
            }
        }
    }
    val submit: () -> Unit = if (mode == AuthMode.LOGIN) doLogin else doRegister

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = MusePaddings.largeGap)
            .navigationBarsPadding(),
    ) {
        Spacer(Modifier.height(16.dp))

        // SegmentedControl 切换登录/注册
        SegmentedControl(
            mode = mode,
            onModeChange = {
                mode = it
                error = null
            },
        )

        Spacer(Modifier.height(20.dp))

        // 表单(切换时淡入淡出)
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 }) togetherWith
                    (fadeOut(tween(MuseAnimation.FAST_NORMAL_MS)) + slideOutVertically(tween(MuseAnimation.FAST_NORMAL_MS)) { -it / 4 })
            },
            label = "auth_form",
        ) { current ->
            when (current) {
                AuthMode.LOGIN -> LoginForm(
                    email = email,
                    onEmailChange = {
                        email = it
                        error = null
                        emailError = if (it.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(it).matches()) errEmailInvalid else null
                    },
                    password = password,
                    onPasswordChange = {
                        password = it
                        error = null
                        passwordError = if (it.isNotEmpty() && it.length < 6) errPasswordTooShort else null
                    },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    loading = loading,
                    onSubmit = submit,
                    emailError = emailError,
                    passwordError = passwordError,
                )
                AuthMode.REGISTER -> RegisterForm(
                    username = username,
                    onUsernameChange = { username = it; error = null },
                    email = email,
                    onEmailChange = {
                        email = it
                        error = null
                        emailError = if (it.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(it).matches()) errEmailInvalid else null
                    },
                    password = password,
                    onPasswordChange = {
                        password = it
                        error = null
                        passwordError = if (it.isNotEmpty() && it.length < 6) errPasswordTooShort else null
                        confirmPasswordError = if (confirmPassword.isNotEmpty() && confirmPassword != it) errPasswordMismatch else null
                    },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    confirmPassword = confirmPassword,
                    onConfirmPasswordChange = {
                        confirmPassword = it
                        error = null
                        confirmPasswordError = if (it.isNotEmpty() && it != password) errPasswordMismatch else null
                    },
                    confirmVisible = confirmVisible,
                    onToggleConfirmVisible = { confirmVisible = !confirmVisible },
                    loading = loading,
                    onSubmit = submit,
                    emailError = emailError,
                    passwordError = passwordError,
                    confirmPasswordError = confirmPasswordError,
                )
            }
        }

        // 错误提示
        error?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))

        // 主按钮:登录/注册
        PrimaryCapsuleButton(
            text = if (mode == AuthMode.LOGIN) stringResource(R.string.account_login) else stringResource(R.string.account_register),
            loading = loading,
            onClick = submit,
        )

        Spacer(Modifier.height(8.dp))

        // 切换链接
        SwitchLinkText(
            mode = mode,
            onClick = {
                mode = if (mode == AuthMode.LOGIN) AuthMode.REGISTER else AuthMode.LOGIN
                error = null
            },
        )

        Spacer(Modifier.height(20.dp))

        // 第三方登录分割线
        DividerWithText(stringResource(R.string.account_or_use))

        Spacer(Modifier.height(16.dp))

        // 第三方登录按钮(模拟)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThirdPartyButton(
                letter = "W",
                label = stringResource(R.string.account_wechat),
                modifier = Modifier.weight(1f),
                enabled = !loading,
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        delay(800)
                        onAuthed("", "wechat")
                        loading = false
                    }
                },
            )
            ThirdPartyButton(
                letter = "Q",
                label = stringResource(R.string.account_qq),
                modifier = Modifier.weight(1f),
                enabled = !loading,
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        delay(800)
                        onAuthed("", "qq")
                        loading = false
                    }
                },
            )
            ThirdPartyButton(
                letter = "G",
                label = stringResource(R.string.account_google),
                modifier = Modifier.weight(1f),
                enabled = !loading,
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        delay(800)
                        onAuthed("", "google")
                        loading = false
                    }
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        // 游客模式
        Text(
            text = stringResource(R.string.account_guest_mode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !loading) {
                    error = null
                    onGuestMode()
                }
                .padding(vertical = MusePaddings.contentGap),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ── 设置页顶部账户卡片(根据登录状态显示)──

/**
 * v1.27: 设置页顶部的账户卡片 — 根据登录状态显示不同内容。
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
            // 头像
            Surface(
                shape = CircleShape,
                color = if (accountState.isLoggedIn) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (accountState.isLoggedIn) {
                        Text(
                            text = accountState.userName.take(1).ifBlank { "M" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (accountState.isLoggedIn) accountState.userName.ifBlank { stringResource(R.string.account_default_user) } else stringResource(R.string.account_not_logged_in),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (accountState.isLoggedIn) stringResource(R.string.account_manage) else stringResource(R.string.account_login_register),
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

// ── 内部组件 ──────────────────────────────────────────

private enum class AuthMode { LOGIN, REGISTER }

@Composable
private fun SegmentedControl(
    mode: AuthMode,
    onModeChange: (AuthMode) -> Unit,
) {
    Surface(
        shape = MuseShapes.huge,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            SegmentItem(
                text = stringResource(R.string.account_login),
                selected = mode == AuthMode.LOGIN,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(AuthMode.LOGIN) },
            )
            SegmentItem(
                text = stringResource(R.string.account_register),
                selected = mode == AuthMode.REGISTER,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(AuthMode.REGISTER) },
            )
        }
    }
}

@Composable
private fun SegmentItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = MusePaddings.auxGap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoginForm(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    loading: Boolean,
    onSubmit: () -> Unit,
    emailError: String?,
    passwordError: String?,
) {
    val emailFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { emailFocusRequester.requestFocus() }
    Column {
        AuthTextField(
            value = email,
            onValueChange = onEmailChange,
            placeholder = stringResource(R.string.account_email),
            leadingIcon = Icons.Default.Mail,
            modifier = Modifier.focusRequester(emailFocusRequester),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            enabled = !loading,
            isError = emailError != null,
            supportingText = emailError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        )
        Spacer(Modifier.height(12.dp))
        AuthTextField(
            value = password,
            onValueChange = onPasswordChange,
            placeholder = stringResource(R.string.account_password),
            leadingIcon = Icons.Default.Lock,
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisible) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) stringResource(R.string.account_hide_password) else stringResource(R.string.account_show_password),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            enabled = !loading,
            onImeAction = onSubmit,
            isError = passwordError != null,
            supportingText = passwordError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        )
    }
}

@Composable
private fun RegisterForm(
    username: String,
    onUsernameChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    confirmVisible: Boolean,
    onToggleConfirmVisible: () -> Unit,
    loading: Boolean,
    onSubmit: () -> Unit,
    emailError: String?,
    passwordError: String?,
    confirmPasswordError: String?,
) {
    val usernameFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { usernameFocusRequester.requestFocus() }
    Column {
        AuthTextField(
            value = username,
            onValueChange = onUsernameChange,
            placeholder = stringResource(R.string.account_username),
            leadingIcon = Icons.Default.Person,
            modifier = Modifier.focusRequester(usernameFocusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            enabled = !loading,
        )
        Spacer(Modifier.height(12.dp))
        AuthTextField(
            value = email,
            onValueChange = onEmailChange,
            placeholder = stringResource(R.string.account_email),
            leadingIcon = Icons.Default.Mail,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            enabled = !loading,
            isError = emailError != null,
            supportingText = emailError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        )
        Spacer(Modifier.height(12.dp))
        AuthTextField(
            value = password,
            onValueChange = onPasswordChange,
            placeholder = stringResource(R.string.account_password),
            leadingIcon = Icons.Default.Lock,
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisible) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) stringResource(R.string.account_hide_password) else stringResource(R.string.account_show_password),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            enabled = !loading,
            isError = passwordError != null,
            supportingText = passwordError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        )
        Spacer(Modifier.height(12.dp))
        AuthTextField(
            value = confirmPassword,
            onValueChange = onConfirmPasswordChange,
            placeholder = stringResource(R.string.account_confirm_password),
            leadingIcon = Icons.Default.Lock,
            trailingIcon = {
                IconButton(onClick = onToggleConfirmVisible) {
                    Icon(
                        imageVector = if (confirmVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (confirmVisible) stringResource(R.string.account_hide_password) else stringResource(R.string.account_show_password),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            enabled = !loading,
            onImeAction = onSubmit,
            isError = confirmPasswordError != null,
            supportingText = confirmPasswordError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        )
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    onImeAction: (() -> Unit)? = null,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = trailingIcon,
        singleLine = true,
        enabled = enabled,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(onDone = { onImeAction?.invoke() }),
        isError = isError,
        supportingText = supportingText,
        shape = MuseShapes.semiLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedBorderColor = if (isError) MaterialTheme.colorScheme.error else Color.Transparent,
            unfocusedBorderColor = if (isError) MaterialTheme.colorScheme.error else Color.Transparent,
            disabledBorderColor = Color.Transparent,
            focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            errorBorderColor = MaterialTheme.colorScheme.error,
        ),
    )
}

@Composable
private fun PrimaryCapsuleButton(
    text: String,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.huge,
        color = if (loading) MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.fillMaxWidth().height(52.dp).clickable(enabled = !loading, onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.account_loading),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

@Composable
private fun SwitchLinkText(
    mode: AuthMode,
    onClick: () -> Unit,
) {
    val prefix = if (mode == AuthMode.LOGIN) stringResource(R.string.account_no_account) else stringResource(R.string.account_has_account)
    val action = if (mode == AuthMode.LOGIN) stringResource(R.string.account_register_action) else stringResource(R.string.account_login_action)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = MusePaddings.labelVerticalGap),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = action,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DividerWithText(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = MusePaddings.itemGap),
        )
        Box(modifier = Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun ThirdPartyButton(
    letter: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.huge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(48.dp).clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AccountFeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MusePaddings.auxGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.account_coming_soon),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ── 工具函数 ──────────────────────────────────────────

@Composable
private fun loginMethodLabel(method: String): String = when (method) {
    "email" -> stringResource(R.string.account_method_email)
    "phone" -> stringResource(R.string.account_method_phone)
    "wechat" -> stringResource(R.string.account_method_wechat)
    "qq" -> stringResource(R.string.account_method_qq)
    "google" -> stringResource(R.string.account_method_google)
    "guest" -> stringResource(R.string.account_method_guest)
    else -> stringResource(R.string.account_method_unknown)
}

// v1.131: EMAIL_REGEX 已迁移到 io.zer0.muse.util.MusePatterns.EMAIL_REGEX

private fun validateLogin(
    email: String,
    password: String,
    errEmailRequired: String,
    errEmailInvalid: String,
    errPasswordRequired: String,
): String? {
    if (email.isBlank()) return errEmailRequired
    if (!MusePatterns.EMAIL_REGEX.matches(email.trim())) return errEmailInvalid
    if (password.isBlank()) return errPasswordRequired
    return null
}

private fun validateRegister(
    username: String,
    email: String,
    password: String,
    confirmPassword: String,
    errUsernameRequired: String,
    errEmailRequired: String,
    errEmailInvalid: String,
    errPasswordRequired: String,
    errPasswordTooShort: String,
    errPasswordMismatch: String,
): String? {
    if (username.isBlank()) return errUsernameRequired
    if (email.isBlank()) return errEmailRequired
    if (!MusePatterns.EMAIL_REGEX.matches(email.trim())) return errEmailInvalid
    if (password.isBlank()) return errPasswordRequired
    if (password.length < 6) return errPasswordTooShort
    if (confirmPassword != password) return errPasswordMismatch
    return null
}
