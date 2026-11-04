package io.zer0.muse.data.skill

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.util.SafePaths
import io.zer0.muse.util.isStrictlyInside
import io.zer0.muse.util.copyZipEntryWithLimit
import io.zer0.muse.util.readZipEntryWithLimit
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * `SKILL.md` 能力包的落盘与读取（P1-B）。
 *
 * 包解到 `filesDir/workspace/skills/<skillId>/`；技能记录仍以 `skills` 表为唯一真相，
 * `implementationKotlin` 存**相对路径**（`prompt_file:workspace/skills/<id>/SKILL.md`）而不是绝对路径 ——
 * 绝对路径在备份恢复/换设备后直接失效，也没必要把内部目录结构写进数据库。
 *
 * 渐进披露就落在这里：**列表只有 `name + description`，正文由 [instructionBody] 按需读**。
 * 哪天有人图省事把正文塞进列表，退化会以"回答变蠢"的形式被用户先发现。
 *
 * ZIP 的读取交给 [SkillPackageZip]：一个是"技能包怎么管"，一个是"怎么安全解一个压缩包"，
 * 混在一起只会让这个类越来越肥。
 */
class SkillPackageStore(context: Context) {

    private val appContext = context.applicationContext

    private val zip = SkillPackageZip()

    /** 导入 journal：清理上次进程被杀留下的半成品（P1-09）。 */
    private val journal = SkillImportJournal(File(appContext.filesDir, RELATIVE_ROOT))

    /** 包根目录。 */
    val root: File get() = File(appContext.filesDir, RELATIVE_ROOT)

    /** 某个技能的包目录（仅本机使用）。 */
    fun dirFor(skillId: String): File = File(root, skillId)

    /** 写进 `implementationKotlin` 的相对路径写法。 */
    fun relativeManifestPath(skillId: String): String = "$RELATIVE_ROOT/$skillId/$SKILL_FILE"

    /** 导入一个包：[source] 是目录或 `.zip`。失败一律给可读中文原因。 */
    fun import(source: File): Outcome {
        // 先把上次中断留下的半成品清掉：否则同名包会被判成"已存在"，
        // 而那个目录其实是残缺的。
        journal.recover().takeIf { it.isNotEmpty() }?.let { recovered ->
            Logger.w(TAG, "已清理中断的导入：${recovered.joinToString(",")}")
        }
        return importNow(source)
    }

    private fun importNow(source: File): Outcome = when {
        !source.exists() -> Outcome.fail("包不存在：${source.absolutePath}")
        source.isDirectory -> importDirectory(source)
        else -> importZip(source)
    }

    /** 读出技能正文（去掉 frontmatter）。文件缺失返回 null，由调用方给可读提示。 */
    fun instructionBody(skillId: String): String? {
        val manifest = File(dirFor(skillId), SKILL_FILE)
        if (!manifest.isFile) return null
        return SkillPackage.parse(manifest.readText()).getOrNull()?.body
    }

    /**
     * 按 `implementationKotlin` 里存的相对路径读正文。
     *
     * 只接受 `workspace/skills/<id>/SKILL.md` 这种形状，并且仍要过 [SafePaths] ——
     * 数据库里的值是用户可写的地方，不能因为"是自己存的"就当成可信输入。
     */
    fun instructionBodyByPath(relativeManifestPath: String): String? {
        if (!SafePaths.isSafeRelative(relativeManifestPath)) return null
        val base = appContext.filesDir
        val file = SafePaths.safeResolve(base, relativeManifestPath) ?: return null
        if (!file.isFile || !isStrictlyInside(file.canonicalPath, File(base, RELATIVE_ROOT).canonicalPath)) return null
        return SkillPackage.parse(file.readText()).getOrNull()?.body
    }

    /** 删除技能包目录（卸载/更新时调用）。 */
    fun delete(skillId: String): Boolean {
        val dir = dirFor(skillId)
        if (!dir.isDirectory) return false
        val removed = dir.deleteRecursively()
        if (!removed) Logger.w(TAG, "技能包目录未能完全删除：${dir.absolutePath}")
        return removed
    }

    private fun importDirectory(source: File): Outcome {
        val manifest = manifestIn(source) ?: return Outcome.fail(zip.missingManifestReason)
        val (definition, parseError) = parseManifest(manifest.readText())
        if (definition == null) return Outcome.fail(parseError ?: "SKILL.md 无效")
        val (plan, conflict) = planFor(definition)
        if (plan == null) return Outcome.fail(conflict ?: "无法准备目标目录")
        // 打标记再复制：复制到一半进程被杀时，下次导入能把残目录清掉
        journal.begin(plan.skillId, plan.target)
        return runCatching { source.copyRecursively(plan.target, overwrite = false) }.fold(
            onSuccess = {
                journal.commit(plan.skillId)
                Outcome.ok(plan.imported(definition))
            },
            onFailure = { failAfterFailedCopy(plan.target, it) },
        )
    }

    private fun importZip(source: File): Outcome = zip.inspect(source).fold(
        onSuccess = { scanned ->
            val (definition, parseError) = parseManifest(scanned.manifestText)
            if (definition == null) {
                Outcome.fail(parseError ?: "SKILL.md 无效")
            } else {
                val (plan, conflict) = planFor(definition)
                if (plan == null) {
                    Outcome.fail(conflict ?: "无法准备目标目录")
                } else {
                    journal.begin(plan.skillId, plan.target)
                    runCatching { zip.extractTo(scanned, plan.target) }.fold(
                        onSuccess = {
                            journal.commit(plan.skillId)
                            Outcome.ok(plan.imported(definition))
                        },
                        onFailure = { failAfterFailedCopy(plan.target, it) },
                    )
                }
            }
        },
        onFailure = { Outcome.fail("ZIP 校验失败：${it.message ?: it.javaClass.simpleName}") },
    )

    /**
     * 规划落盘位置。已有同名包时返回可读拒绝原因，**不覆盖** ——
     * 覆盖会把"更新"和"重装"两种意图混成一个动作，用户连反悔的余地都没有。
     *
     * 刻意返回二元组而不是 `require` 抛异常：这两个调用点都在 `runCatching` 之外，
     * 抛出去就只是崩溃日志里一条 IllegalArgumentException，界面上看不到原因。
     */
    private fun planFor(definition: SkillPackage.Parsed): Pair<Plan?, String?> {
        val skillId = deriveSkillId(definition.name)
        val target = dirFor(skillId)
        return if (target.exists()) {
            null to existingPackageMessage(skillId)
        } else {
            Plan(skillId, target) to null
        }
    }

    private data class Plan(val skillId: String, val target: File) {
        fun imported(definition: SkillPackage.Parsed) = SkillPackageStore.Imported(skillId, definition, target)
    }

    /** 清单允许在根目录，也允许在**唯一的**单层子目录里（很多人是直接把文件夹压进去的）。 */
    private fun manifestIn(dir: File): File? {
        val direct = File(dir, SKILL_FILE)
        if (direct.isFile) return direct
        val onlyChild = dir.listFiles()?.singleOrNull { it.isDirectory } ?: return null
        return File(onlyChild, SKILL_FILE).takeIf { it.isFile }
    }

    /** 解析 SKILL.md：返回 (定义, null) 或 (null, 可读原因)。 */
    private fun parseManifest(text: String): Pair<SkillPackage.Parsed?, String?> {
        val result = SkillPackage.parse(text)
        val parsed = result.getOrNull()
        return if (parsed != null) {
            parsed to null
        } else {
            null to ("SKILL.md 无效：" + ((result as? io.zer0.common.Result.Error)?.message ?: "解析失败"))
        }
    }

    private fun existingPackageMessage(skillId: String): String =
        "技能包已存在（id=$skillId）。先删除旧版本，或改 $SKILL_FILE 里的 name"

    private fun failAfterFailedCopy(target: File, error: Throwable): Outcome {
        target.deleteRecursively()
        val message = error.message ?: error.javaClass.simpleName
        return if (message.contains("技能包已存在")) Outcome.fail(message) else Outcome.fail("落盘失败：$message")
    }

    /**
     * id **只由显示名派生**，不看来源文件夹叫什么。
     *
     * 这不是口味问题：带来源名时，同一个技能换个文件夹名（或者别人转发时改了名）
     * 就会派生出第二个 id —— 用户看到的是"我明明装了它，列表里冒出两个"，
     * 而"重复导入不覆盖"的保护也随之失效。显示名才是用户认得的那个身份。
     */
    internal fun deriveSkillId(displayName: String): String {
        val base = slugify(displayName).ifBlank { "skill" }.take(MAX_ID_BASE_CHARS)
        val candidate = base + "-" + digest(displayName)
        return if (SafePaths.isSafeRelative(candidate)) candidate else "skill-" + digest(displayName)
    }

    private fun slugify(text: String): String = text.lowercase()
        .map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
        .joinToString("")
        .let { Regex("-+").replace(it, "-") }
        .trim('-')

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-1")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(8)

    /** 导入结果。 */
    data class Outcome(val imported: Imported?, val reason: String?) {
        val success get() = imported != null

        companion object {
            fun ok(value: Imported) = Outcome(value, null)
            fun fail(reason: String) = Outcome(null, reason)
        }
    }

    /** 一个已落盘的包。 */
    data class Imported(val skillId: String, val definition: SkillPackage.Parsed, val dir: File)

    companion object {
        /** 清单文件名（导入器写临时包时也要用同一个值，不能各写一份字面量）。 */
        const val SKILL_FILE_NAME = "SKILL.md"

        private const val TAG = "SkillPackageStore"
        private const val RELATIVE_ROOT = "workspace/skills"
        private const val SKILL_FILE = SKILL_FILE_NAME
        private const val MAX_ID_BASE_CHARS = 40
    }
}

/**
 * 安全地读一个技能包 ZIP：**先扫名、后落盘**两趟，都不把整包读进内存。
 *
 * 两趟各有用途：
 *  - 第一趟只走条目名 + 读一个 `SKILL.md`（小文件），因此**一个字节都不写**就能拒掉穿越包，
 *    也能在决定目标目录之前拿到技能名；
 *  - 第二趟流式解压，逐条目限额（[copyZipEntryWithLimit]），zip 炸弹的体积只在解压后显现。
 *
 * 穿越判定交给 [SafePaths] —— 全仓只留那一份实现：这类校验写多份的结局，
 * 一定是某一次为了放宽别处只改了其中一份，另一份悄悄变成更宽松的那条。
 *
 * 符号链接条目不单独处理：`java.util.zip` 建不出符号链接，那种条目只会落成一个
 * "内容是路径字符串"的普通文件，跟随不了任何地方。
 */
internal class SkillPackageZip {

    /** 第一趟的结果：清单文本 + 要落盘的条目名与顶层前缀。 */
    class Scanned(val manifestText: String, val source: File, val prefix: String, val entryNames: List<String>)

    val missingManifestReason: String get() = "$SKILL_FILE 不在包根目录（也不在唯一的单层子目录里）"

    /** 扫描并校验：条目名合法性、条目数、清单存在与可解析。 */
    fun inspect(source: File): Result<Scanned> = runCatching {
        ZipInputStream(FileInputStream(source)).use { zip ->
            val names = mutableListOf<String>()
            var manifest: String? = null
            var unsafe: String? = null
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.take(MAX_ZIP_ENTRIES + 1).forEach { entry ->
                val name = entry.name.replace('\\', '/')
                if (!SafePaths.isSafeRelative(name) && unsafe == null) unsafe = name
                names += name
                if (name.endsWith(SKILL_FILE) && manifest == null) {
                    manifest = readZipEntryWithLimit(zip, MAX_MANIFEST_BYTES, name).toString(Charsets.UTF_8)
                }
            }
            require(names.size <= MAX_ZIP_ENTRIES) { "条目数超过 $MAX_ZIP_ENTRIES 上限" }
            require(unsafe == null) { "包内路径非法（疑似目录穿越）：$unsafe" }
            val prefix = commonTopLevelDir(names)
            require(manifest != null && names.contains(prefix + SKILL_FILE)) { missingManifestReason }
            Scanned(requireNotNull(manifest), source, prefix, names)
        }
    }

    /** 第二趟：把 [Scanned.prefix] 下的条目流式写到 [target]。 */
    fun extractTo(scanned: Scanned, target: File) {
        target.mkdirs()
        ZipInputStream(FileInputStream(scanned.source)).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                writeEntry(zip, entry, scanned.prefix, target)
            }
        }
    }

    private fun writeEntry(zip: ZipInputStream, entry: ZipEntry, prefix: String, target: File) {
        val name = entry.name.replace('\\', '/')
        if (!name.startsWith(prefix)) return
        val relative = name.removePrefix(prefix)
        if (relative.isBlank()) return
        val out = SafePaths.safeResolve(target, relative) ?: error("落盘路径被拒绝：$name")
        out.parentFile?.mkdirs()
        out.outputStream().use { write -> copyZipEntryWithLimit(zip, write, MAX_ENTRY_BYTES, name) }
    }

    /**
     * 所有条目共用同一个顶层目录时返回它（含结尾斜杠），否则返回空串。
     *
     * 必须**每个名字都含分隔符**才算有顶层目录：
     * 扁平包（根下就一个 `SKILL.md`）里没有 `/`，`substringBefore('/')` 会返回文件名本身，
     * 于是"SKILL.md"被当成目录名，清单路径变成 `SKILL.md/SKILL.md` —— 这个 bug 是被
     * 扁平 ZIP 的测试用例抓到的。
     */
    private fun commonTopLevelDir(names: List<String>): String {
        if (names.isEmpty() || names.any { !it.contains('/') }) return ""
        val first = names.first().substringBefore('/')
        if (first.isEmpty()) return ""
        return if (names.all { it.substringBefore('/') == first }) "$first/" else ""
    }

    private companion object {
        const val SKILL_FILE = "SKILL.md"
        const val MAX_ZIP_ENTRIES = 2_000
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 64L * 1024
    }
}
