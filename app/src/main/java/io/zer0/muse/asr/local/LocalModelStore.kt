package io.zer0.muse.asr.local

import android.content.Context
import io.zer0.common.Logger
import java.io.File

/**
 * 本地语音模型文件管理：位置、完整性检查、删除。
 *
 * 模型在不在是文件系统事实，不写进 DataStore（避免两份状态漂移）——
 * 所有"是否可用"的判定都落在 [isReady] 这一个函数上。
 *
 * 目录结构：`filesDir/local_asr/model.int8.onnx` + `tokens.txt`。
 * VAD 模型（silero_vad.onnx）首版不下不用，目录里出现也不影响判定。
 */
internal object LocalModelStore {

    private const val TAG = "LocalModelStore"
    private const val DIR_NAME = "local_asr"

    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"

    /** 模型目录（不自动创建；创建交给下载/导入流程）。 */
    fun modelDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun modelFile(context: Context): File = File(modelDir(context), MODEL_FILE)

    fun tokensFile(context: Context): File = File(modelDir(context), TOKENS_FILE)

    /** 识别可用：模型与词表都存在且非空。 */
    fun isReady(context: Context): Boolean =
        modelFile(context).let { it.isFile && it.length() > 0 } &&
            tokensFile(context).let { it.isFile && it.length() > 0 }

    /**
     * 下载/导入前的落位校验：模型文件存在但词表缺失时提示重新获取完整包。
     * 用于设置页区分「未下载」和「下载不完整」。
     */
    fun status(context: Context): ModelStatus = when {
        !modelFile(context).isFile && !tokensFile(context).isFile -> ModelStatus.NOT_DOWNLOADED
        modelFile(context).isFile && tokensFile(context).isFile -> ModelStatus.READY
        else -> ModelStatus.INCOMPLETE
    }

    enum class ModelStatus { NOT_DOWNLOADED, READY, INCOMPLETE }

    /** 已下载占用字节数（两个文件之和；不存在按 0）。 */
    fun totalBytes(context: Context): Long =
        listOf(modelFile(context), tokensFile(context)).sumOf { if (it.isFile) it.length() else 0L }

    /**
     * 删除模型文件（设置页「删除」入口）。返回是否全部清除成功。
     * 删完状态回到 [ModelStatus.NOT_DOWNLOADED]。
     */
    fun delete(context: Context): Boolean {
        val dir = modelDir(context)
        var ok = true
        dir.listFiles()?.forEach { file ->
            if (!file.delete()) {
                Logger.w(TAG, "删除模型文件失败: ${file.name}")
                ok = false
            }
        }
        // 删除改变可用性：输入栏的语音键要随之消失
        LocalModelStoreBridge.refresh()
        return ok
    }

    /** 读入词表（[SenseVoiceTokens.parse]）。文件缺失抛异常，调用方转用户可见错误。 */
    fun loadTokens(context: Context): Map<Int, String> {
        val file = tokensFile(context)
        if (!file.isFile) throw IllegalStateException("词表文件不存在: ${file.absolutePath}")
        return SenseVoiceTokens.parse(file.readText(Charsets.UTF_8))
    }

    /**
     * 手动导入（Batch 1 验证路径）：把外部模型文件复制进模型目录。
     * 正式下载通道（Batch 2 的 LocalModelDownloader）完成后仍保留：用户从
     * PC 拷模型是真实场景（241 MB 在手机流量下不现实）。
     *
     * @return 导入后的完整校验结果
     */
    fun importModel(context: Context, modelSource: File, tokensSource: File): Result<Unit> = runCatching {
        val dir = modelDir(context)
        if (!dir.isDirectory) dir.mkdirs()
        modelSource.copyTo(modelFile(context), overwrite = true)
        tokensSource.copyTo(tokensFile(context), overwrite = true)
        check(isReady(context)) { "导入后完整性检查未通过" }
    }.onFailure { Logger.w(TAG, "手动导入模型失败: ${it.message}") }
        .onSuccess {
            // 导入成功同样改变可用性
            LocalModelStoreBridge.refresh()
        }
}
