package io.zer0.muse.miniphone.data.forum

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpForumCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpForumPostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.util.UUID

/**
 * 论坛门面：发帖、翻时间线、按作者看主页、点赞、评论、删帖。
 *
 * 论坛是本机自己的一份公开信息流，数据全在小手机自己的库里，不碰宿主数据。
 * 库里只存原文与 id：点赞名单存成 `likeIdsJson`（id 数组的 JSON 文本），
 * 界面按当前语言渲染「几人赞」「评论」这些说法，数据层不存任何成品文案。
 * 作者名允许为空串 —— 空名由界面按当前语言兜底显示，不把某个语言的词冻进库里。
 *
 * 两处「没有就是没有」的约定：
 *  - 正文空白的帖子与评论都不落库，返回 null，不让一条空内容占住时间线；
 *  - 让角色发帖走宿主的通用一次性生成桥，拿不到结果就什么都不写，
 *    绝不替他编一条看起来像他说的话。
 *
 * @param now 时间源，默认系统时钟；测试用它钉死 `createdAt`
 * @param newId 主键生成器，默认 UUID；测试它让帖子与评论 id 可预期
 */
internal class MpForumStore(
    private val db: MiniPhoneDb,
    private val host: MiniPhoneHost,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** 时间线：新帖在前（DAO 已按 `createdAt DESC, id DESC` 排好）。 */
    fun observeTimeline(): Flow<List<MpForumPostEntity>> = db.forumPostDao().observeTimeline()

    /** 某个作者的主页：只有 TA 的帖子，同样是新帖在前。 */
    fun observeByAuthor(authorId: String): Flow<List<MpForumPostEntity>> = db.forumPostDao().observeByAuthor(authorId)

    /**
     * 翻一页时间线（新 → 旧）。
     *
     * 条数与偏移都在查询里，不是取回整表再切 —— 切完每页有效条数会缩水，
     * 下一页的游标也就错位了。
     */
    suspend fun pageTimeline(limit: Int, offset: Int): List<MpForumPostEntity> = db.forumPostDao().pageTimeline(limit, offset)

    /** 某帖的评论，按时间正序（先说的在上面）。 */
    fun observeComments(postId: String): Flow<List<MpForumCommentEntity>> = db.forumCommentDao().observeByPost(postId)

    /** 发帖：[content] 空白就不落库并返回 null；落库的正文与作者名都去掉首尾空白。 */
    suspend fun publish(authorId: String, authorName: String, content: String): MpForumPostEntity? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val post = MpForumPostEntity(
            id = newId(),
            // 三个字段都要 trim：authorId 带空格时帖子照样能落库，但按作者查主页永远查不到它，
            // 用户会看到「发了但主页没有」。只 trim 名字与正文是不够的。
            authorId = authorId.trim(),
            authorName = authorName.trim(),
            content = text,
            likeIdsJson = EMPTY_LIKES,
            createdAt = now(),
        )
        db.forumPostDao().insert(post)
        return post
    }

    /**
     * 评论某个帖子。
     *
     * 正文空白不落库；帖子已经不存在也不落库 —— 表之间没有外键约束，
     * 少这一道判断就会留下读不到的孤儿评论行。
     */
    suspend fun comment(postId: String, authorId: String, authorName: String, content: String): MpForumCommentEntity? {
        val text = content.trim()
        if (text.isEmpty()) return null
        if (db.forumPostDao().get(postId) == null) return null
        val row = MpForumCommentEntity(
            id = newId(),
            postId = postId,
            authorId = authorId,
            authorName = authorName.trim(),
            content = text,
            createdAt = now(),
        )
        db.forumCommentDao().insert(row)
        return row
    }

    /**
     * 点赞 / 取消点赞，返回操作之后「这个人是否赞了」。
     *
     * 同一个人对同一条帖子只可能出现在名单里一次，所以状态只看存在与否：
     * 在就摘掉、不在就加上，连点两次必然回到未赞。帖子已经没了就返回 false。
     */
    suspend fun toggleLike(postId: String, userId: String): Boolean {
        val post = db.forumPostDao().get(postId) ?: return false
        val id = userId.trim()
        if (id.isEmpty()) return false
        val current = decodeLikeIds(post.likeIdsJson).toMutableList()
        val liked: Boolean
        if (current.contains(id)) {
            current.remove(id)
            liked = false
        } else {
            current.add(id)
            liked = true
        }
        db.forumPostDao().setLikes(postId, encodeLikeIds(current))
        return liked
    }

    /** 删帖：评论跟着一起清掉，不留孤儿行。 */
    suspend fun deletePost(postId: String) {
        db.forumCommentDao().deleteByPost(postId)
        db.forumPostDao().delete(postId)
    }

    /** 删一条评论；谁能删由界面那一侧把关。 */
    suspend fun deleteComment(commentId: String) {
        db.forumCommentDao().delete(commentId)
    }

    /**
     * 让某个角色就一段素材发一条帖子。
     *
     * [material] 留空时自己取素材：这个角色最近的动态正文，一条也没有就当「没有素材」，
     * 提示词里只说「发一条近况」。宿主没有这条生成能力、返回空串或空白，都直接返回 null
     * 并且不落库 —— 用户要能分清「TA 没说话」和「没让 TA 说成」。
     */
    suspend fun composePost(characterId: String, characterName: String, material: String = ""): MpForumPostEntity? {
        val topic = material.trim().ifEmpty { recentMaterial(characterId) }
        val brief = buildString {
            appendLine("以第一人称在论坛发一条帖子，说一件你最近真心想分享的事，两三句就够。")
            if (topic.isNotBlank()) {
                appendLine("可以围绕这些素材，但不要逐条复述：")
                topic.lines().filter { line -> line.isNotBlank() }.forEach { line -> appendLine("- $line") }
            }
            append("只输出帖子正文，不要解释、不要署名。")
        }
        val text = host.composeScene(characterId, brief)
        if (text.isNullOrBlank()) return null
        return publish(characterId, characterName, text)
    }

    /** 角色最近的动态正文，当作发帖素材的底色；最多 [MAX_MATERIAL] 条。 */
    private suspend fun recentMaterial(characterId: String): String = db.momentDao().observeByAuthor(characterId).first()
        .asSequence()
        .map { moment -> moment.content.trim() }
        .filter { content -> content.isNotBlank() }
        .take(MAX_MATERIAL)
        .joinToString("\n")

    companion object {
        /** 空点赞名单：`likeIdsJson` 的初始值。 */
        const val EMPTY_LIKES: String = "[]"

        private const val MAX_MATERIAL = 5

        /** `likeIdsJson` → 点赞者 id 列表；空串、空数组、坏文本都当「没人赞过」。 */
        fun decodeLikeIds(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { id -> id.isNotBlank() }
                }
            }.getOrDefault(emptyList())
        }

        /** 点赞者 id 列表 → `likeIdsJson`；丢掉空 id 并去重，顺序保持传入次序。 */
        fun encodeLikeIds(ids: List<String>): String {
            val array = JSONArray()
            ids.asSequence()
                .map { id -> id.trim() }
                .filter { id -> id.isNotEmpty() }
                .distinct()
                .forEach { id -> array.put(id) }
            return array.toString()
        }
    }
}
