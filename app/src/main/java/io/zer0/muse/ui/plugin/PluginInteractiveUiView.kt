package io.zer0.muse.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginUiTree
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 声明式界面的**交互**渲染（提交-返回语义，见设计文档 §8）。
 *
 * 三条不显眼但都是用户能感觉到的规矩：
 *  1. **未授权时输入框禁用并写明原因** —— 静默隐藏控件会让界面"少一块"而用户不知道为什么；
 *  2. **同一面板同时只允许一次在飞提交** —— 连点写坏数据是这类面板最常见的事故；
 *  3. **提交失败保留用户已填内容** —— 让用户重打一遍是拿别人的时间换作者的方便。
 *
 * 刻意不做双向绑定：只有"提交 → 插件返回新树 → 整棵替换"。
 */
@Composable
fun PluginInteractiveUiView(
    root: PluginUiTree.Node,
    assetsRoot: File?,
    interactiveEnabled: Boolean,
    disabledReason: String?,
    onSubmit: suspend (handler: String, valuesJson: String) -> PluginUiSubmitOutcome,
) {
    var current by remember(root) { mutableStateOf(root) }
    val inputs = remember(current) { collectInputs(current) }
    // 树被替换（提交成功）才重置表单；失败时不动它，用户已填内容因此自然保留。
    val values = remember(current) {
        mutableStateMapOf<String, String>().apply {
            inputs.forEach { input -> put(input.key, input.value.orEmpty()) }
        }
    }
    var submitting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val busyText = stringResource(R.string.plugin_ui_busy)
    // 文案必须在组合作用域取好：scope.launch 里不是 @Composable 上下文。
    val renderFailedText = stringResource(R.string.plugin_ui_render_failed)
    val scope = rememberCoroutineScope()

    fun submit(handler: String) {
        if (submitting) {
            notice = busyText
            return
        }
        submitting = true
        notice = null
        val payload = valuesJson(inputs, values)
        scope.launch {
            val outcome = onSubmit(handler, payload)
            submitting = false
            when (outcome) {
                is PluginUiSubmitOutcome.Rejected -> notice = outcome.reason
                is PluginUiSubmitOutcome.Rendered -> {
                    val parsed = outcome.parsed
                    val next = parsed.root
                    if (next == null) {
                        // 解析失败时**不替换**当前界面：用户填的东西还在，错误也能看见
                        notice = parsed.reason ?: renderFailedText
                    } else {
                        current = next
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(MusePaddings.screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!interactiveEnabled && !disabledReason.isNullOrBlank()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MuseShapes.small) {
                Text(
                    text = disabledReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(MusePaddings.tightGap),
                )
            }
        }
        InteractiveNode(
            node = current,
            assetsRoot = assetsRoot,
            enabled = interactiveEnabled && !submitting,
            // 提交按钮单独看 interactiveEnabled：进行中保持可点，由 submit() 守卫拒绝并提示，
            // 直接禁用会让用户点了没反应、也不知道为什么。
            submitEnabled = interactiveEnabled,
            values = values,
            submitting = submitting,
            onSubmit = ::submit,
        )
        if (!notice.isNullOrBlank()) {
            Text(
                text = notice.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun InteractiveNode(
    node: PluginUiTree.Node,
    assetsRoot: File?,
    enabled: Boolean,
    submitEnabled: Boolean,
    values: MutableMap<String, String>,
    submitting: Boolean,
    onSubmit: (String) -> Unit,
) {
    when (node) {
        is PluginUiTree.Node.Form -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { child ->
                InteractiveNode(child, assetsRoot, enabled, submitEnabled, values, submitting, onSubmit)
            }
        }
        is PluginUiTree.Node.Column -> Column(verticalArrangement = Arrangement.spacedBy(node.gap.dp(8))) {
            node.children.forEach { child ->
                InteractiveNode(child, assetsRoot, enabled, submitEnabled, values, submitting, onSubmit)
            }
        }
        is PluginUiTree.Node.Row -> Row(horizontalArrangement = Arrangement.spacedBy(node.gap.dp(8))) {
            node.children.forEach { child ->
                InteractiveNode(child, assetsRoot, enabled, submitEnabled, values, submitting, onSubmit)
            }
        }
        is PluginUiTree.Node.Input -> InputControl(node, enabled, values)
        is PluginUiTree.Node.Submit -> MuseCapsuleButton(
            text = node.label,
            onClick = { onSubmit(node.handler) },
            enabled = submitEnabled,
            loading = submitting,
            fillWidth = false,
        )
        // 非交互节点交给只读渲染器：颜色/字号/上限都只有一份实现
        else -> PluginUiReadOnlyNode(node, assetsRoot)
    }
}

@Composable
private fun InputControl(node: PluginUiTree.Node.Input, enabled: Boolean, values: MutableMap<String, String>) {
    val kind = node.kind?.lowercase() ?: "text"
    val value = values[node.key].orEmpty()
    when (kind) {
        "toggle" -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = node.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            MuseSwitch(
                checked = value == "true",
                // null = 禁用（组件本身没有 enabled 参数，这是它表达禁用的方式）
                onCheckedChange = if (enabled) { next -> values[node.key] = next.toString() } else null,
            )
        }
        "select" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = node.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                node.options.forEach { option ->
                    MuseCapsuleButton(
                        text = option,
                        onClick = { if (enabled) values[node.key] = option },
                        enabled = enabled,
                        variant = if (value == option) {
                            IosCapsuleButtonVariant.Primary
                        } else {
                            IosCapsuleButtonVariant.Secondary
                        },
                        fillWidth = false,
                    )
                }
            }
        }
        else -> MuseTextField(
            value = value,
            onValueChange = { if (enabled) values[node.key] = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(node.label) },
            placeholder = node.placeholder?.let { hint -> { Text(hint) } },
            singleLine = true,
            keyboardOptions = if (kind == "number") {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
        )
    }
}

/** 树里是否有需要交互渲染的节点。没有就按只读渲染，别给一个点了没反应的界面。 */
internal fun hasInteractiveNodes(node: PluginUiTree.Node): Boolean {
    fun walk(n: PluginUiTree.Node): Boolean = when (n) {
        is PluginUiTree.Node.Input, is PluginUiTree.Node.Submit -> true
        is PluginUiTree.Node.Column -> n.children.any(::walk)
        is PluginUiTree.Node.Row -> n.children.any(::walk)
        is PluginUiTree.Node.Form -> n.children.any(::walk)
        else -> false
    }
    return walk(node)
}

/** 把表单值变成插件 handler 的参数：数字给数字、开关给布尔，其余给字符串。 */
internal fun valuesJson(inputs: List<PluginUiTree.Node.Input>, values: Map<String, String>): String = buildJsonObject {
    inputs.forEach { input ->
        val raw = values[input.key].orEmpty()
        // 显式分支而不是 `?.let{} ?: put(raw)`：后者依赖 put 的返回值语义，
        // 一旦 put 返回 null 就会静默写回字符串（实测踩到过，数字字段变成 "12"）。
        when (input.kind?.lowercase()) {
            "number" -> {
                val asNumber = raw.toDoubleOrNull()
                if (asNumber != null) put(input.key, asNumber) else put(input.key, raw)
            }
            "toggle" -> put(input.key, raw == "true")
            else -> put(input.key, raw)
        }
    }
}.toString()

/** 深度优先收集输入项（保持出现顺序，便于作者预测参数顺序）。 */
internal fun collectInputs(node: PluginUiTree.Node): List<PluginUiTree.Node.Input> {
    val out = mutableListOf<PluginUiTree.Node.Input>()
    fun walk(n: PluginUiTree.Node) {
        when (n) {
            is PluginUiTree.Node.Input -> out += n
            is PluginUiTree.Node.Column -> n.children.forEach(::walk)
            is PluginUiTree.Node.Row -> n.children.forEach(::walk)
            is PluginUiTree.Node.Form -> n.children.forEach(::walk)
            else -> Unit
        }
    }
    walk(node)
    return out
}

private fun Int?.dp(default: Int) = (this ?: default).coerceIn(0, 64).dp
