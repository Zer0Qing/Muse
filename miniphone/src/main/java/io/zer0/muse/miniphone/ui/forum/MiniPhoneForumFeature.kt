package io.zer0.muse.miniphone.ui.forum

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.forum.MpForumStore
import io.zer0.muse.miniphone.data.moment.MP_LOCAL_ACTOR_ID
import io.zer0.muse.miniphone.ui.launchGuarded
import org.koin.compose.koinInject

private const val TAG_FORUM = "MiniPhoneForum"

/**
 * 论坛模块的装配点：从容器取库与宿主，持有门面与协程作用域，把纯渲染的页面接上动作。
 *
 * 本机用户统一用 [MP_LOCAL_ACTOR_ID] 作为作者与点赞人 id，作者名取宿主资料的昵称；
 * 昵称没设置时传空串，由页面按当前语言显示「我」，不把某个语言的词写进库。
 *
 * 「让角色发帖」是一次生成：宿主没有这条能力就不落库，页面上什么也不会多出来。
 */
@Composable
internal fun MiniPhoneForumFeature(
    characters: List<MpCharacter>,
    onOpenCharacterChat: suspend (String) -> String? = { null },
    onBack: () -> Unit,
) {
    val db = koinInject<MiniPhoneDb>()
    val host = koinInject<MiniPhoneHost>()
    val store = remember(db, host) { MpForumStore(db, host) }
    val scope = rememberCoroutineScope()
    val profile by host.observeProfile().collectAsStateWithLifecycle(initialValue = MpProfile(nickname = ""))
    val selfName = profile.nickname
    MiniPhoneForumScreen(
        posts = store.observeTimeline(),
        commentsFor = { postId -> store.observeComments(postId) },
        currentUserId = MP_LOCAL_ACTOR_ID,
        characters = characters,
        onPublish = { content ->
            scope.launchGuarded(TAG_FORUM, "发帖") { store.publish(MP_LOCAL_ACTOR_ID, selfName, content) }
        },
        onComment = { postId, content ->
            scope.launchGuarded(TAG_FORUM, "写评论") { store.comment(postId, MP_LOCAL_ACTOR_ID, selfName, content) }
        },
        onToggleLike = { postId ->
            scope.launchGuarded(TAG_FORUM, "点赞/取消点赞") { store.toggleLike(postId, MP_LOCAL_ACTOR_ID) }
        },
        onDelete = { postId -> scope.launchGuarded(TAG_FORUM, "删帖") { store.deletePost(postId) } },
        onComposePost = { characterId ->
            scope.launchGuarded(TAG_FORUM, "让角色发帖") {
                val name = characters.firstOrNull { character -> character.id == characterId }?.name.orEmpty()
                store.composePost(characterId, name)
            }
        },
        onMessageAuthor = { characterId ->
            scope.launchGuarded(TAG_FORUM, "发私信") { onOpenCharacterChat(characterId) }
        },
        onBack = onBack,
    )
}
