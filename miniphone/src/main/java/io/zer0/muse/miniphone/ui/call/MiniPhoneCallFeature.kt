package io.zer0.muse.miniphone.ui.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.data.call.MpCallController
import io.zer0.muse.miniphone.data.call.MpCallKind
import io.zer0.muse.miniphone.data.call.MpCallPhase
import io.zer0.muse.miniphone.data.call.MpCallSessionUi
import io.zer0.muse.miniphone.data.call.MpCallStore
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.misc.MpRingtoneStore
import io.zer0.muse.miniphone.ui.chat.MiniPhoneVoicePlaybackController
import io.zer0.muse.miniphone.ui.chat.MpSentinel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

private const val CALL_HISTORY_TURNS = 8

/**
 * 一通电话的装配点：把控制器与界面接起来，负责相机权限、铃声播放、离开页面时收尾。
 *
 * 阶段推进、每一轮内容、计时与落库都在 [MpCallController] 里，那边是可脱离 Compose 单元测试的；
 * 这里只留「需要 Android 环境」的那几件事：权限、音频、生命周期。
 */
@Composable
internal fun MiniPhoneCallFeature(threadId: String, characterId: String, requestedKind: MpCallKind, onBack: () -> Unit) {
    val db = koinInject<MiniPhoneDb>()
    val host = koinInject<MiniPhoneHost>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback = remember { MiniPhoneVoicePlaybackController() }
    val controller = remember(db, host) {
        MpCallController(host = host, store = MpCallStore(db.callSessionDao()), scope = scope)
    }
    val ringtones = remember { MpRingtoneStore(db) }

    var cameraAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
    }

    LaunchedEffect(Unit) {
        if (requestedKind == MpCallKind.VIDEO && !cameraAllowed) cameraLauncher.launch(Manifest.permission.CAMERA)
        controller.dial(threadId, characterId, requestedKind, cameraAllowed)
    }

    // 接通后跑一轮；对方回完一句就停下等用户说话，不自己跟自己聊。
    val phase = controller.state.value?.phase
    LaunchedEffect(phase) {
        if (phase != MpCallPhase.CONNECTED) return@LaunchedEffect
        controller.runRound(historyOf(db, threadId, controller.state.value))
    }

    // 振铃时放该种类的铃声；一个都没配就不响 —— 没配是配置事实，不假装响了。
    val ringingUri = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controller.state.value?.phase) {
        if (controller.state.value?.phase != MpCallPhase.RINGING) return@LaunchedEffect
        val ringtone = ringtones.observeFor(characterId, requestedKind.wire).first().firstOrNull()
            ?: ringtones.observeFor("", requestedKind.wire).first().firstOrNull()
        ringingUri.value = ringtone?.uri
    }
    LaunchedEffect(ringingUri.value) {
        val uri = ringingUri.value ?: return@LaunchedEffect
        playback.toggle(uri, voiceFile(uri))
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.dispose()
            playback.stop()
        }
    }

    MiniPhoneCallScreen(
        session = controller.state,
        elapsedMs = controller.elapsedMs,
        onSay = { text ->
            // speak 同步把这句记进会话，所以紧接着取到的状态已经包含它 —— 对方回答时听得见用户说了什么。
            controller.speak(text)
            val session = controller.state.value
            scope.launch { controller.runRound(historyOf(db, threadId, session)) }
        },
        onHangUp = {
            controller.hangUp()
            playback.stop()
            onBack()
        },
        onPlayVoice = { uri -> playback.toggle(uri, voiceFile(uri)) },
        onBack = {
            controller.dispose()
            playback.stop()
            onBack()
        },
    )
}

private suspend fun historyOf(db: MiniPhoneDb, threadId: String, session: MpCallSessionUi?): List<MpChatTurn> =
    threadHistoryOf(db, threadId) + (session?.turns ?: emptyList())
        .filter { it.text.isNotBlank() }
        .map { MpChatTurn(sender = it.speaker, content = it.text) }

/** 这通电话所在会话的最近几轮私信；语音与系统消息不带文本，进上下文只会稀释它。 */
private suspend fun threadHistoryOf(db: MiniPhoneDb, threadId: String): List<MpChatTurn> =
    db.messageDao().pageMessages(threadId, CALL_HISTORY_TURNS, 0)
        .filter { it.type != io.zer0.muse.miniphone.data.db.MpMessageType.SYSTEM }
        .filter { it.content.isNotBlank() && it.content != MpSentinel.NO_REPLY && it.content != MpSentinel.VOICE }
        .map { MpChatTurn(sender = it.sender, content = it.content) }

private fun voiceFile(uri: String): File = File(uri.removePrefix("file://"))
