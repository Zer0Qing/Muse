package io.zer0.muse.miniphone.ui.checkup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.checkup.MpCheckupStore
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.ui.launchGuarded
import org.koin.compose.koinInject

private const val TAG_CHECKUP = "MiniPhoneCheckup"

/**
 * 查岗模块的装配点：从容器取库与宿主，持有门面与协程作用域，把纯渲染的页面接上动作。
 *
 * 立案后立刻解读一次 —— 用户点「发起查岗」期望看到结果，而不是再点一次「解读」。
 * 解读失败会把案卷标成未完成，列表里照样看得见，不会静默消失。
 */
@Composable
internal fun MiniPhoneCheckupFeature(characters: List<MpCharacter>, onBack: () -> Unit) {
    val db = koinInject<MiniPhoneDb>()
    val host = koinInject<MiniPhoneHost>()
    val store = remember(db, host) { MpCheckupStore(db, host) }
    val scope = rememberCoroutineScope()
    MiniPhoneCheckupScreen(
        cases = store.observeRecent(),
        characters = characters,
        evidenceFor = { caseId -> store.observeEvidence(caseId) },
        onStart = { characterId, direction, intensity ->
            scope.launchGuarded(TAG_CHECKUP, "发起查岗") {
                val caseId = store.openCase(characterId, direction, intensity)
                store.interpret(caseId)
            }
        },
        onInterpret = { caseId -> scope.launchGuarded(TAG_CHECKUP, "解读案卷") { store.interpret(caseId) } },
        onDelete = { caseId -> scope.launchGuarded(TAG_CHECKUP, "删除案卷") { store.deleteCase(caseId) } },
        onBack = onBack,
    )
}
