package io.zer0.muse.ui.plugin

import io.zer0.muse.data.plugin.PluginUiTree

/** 一次提交的结果：拿到新树，或拿到一句能直接显示的原因。 */
sealed interface PluginUiSubmitOutcome {
    data class Rendered(val parsed: PluginUiTree.Parsed) : PluginUiSubmitOutcome
    data class Rejected(val reason: String) : PluginUiSubmitOutcome
}
