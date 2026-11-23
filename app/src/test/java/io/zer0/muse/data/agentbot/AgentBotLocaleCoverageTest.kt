package io.zer0.muse.data.agentbot

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AgentBotLocaleCoverageTest {

    private val locales = listOf(
        "values",
        "values-en",
        "values-es",
        "values-ja",
        "values-ko",
        "values-pt-rBR",
        "values-ru",
    )

    private val requiredKeys = setOf(
        "agent_screen_summary_counts",
        "agent_screen_needs_action",
        "agent_screen_running",
        "agent_screen_recent",
        "agent_screen_transcript",
        "agent_screen_show_all",
        "agent_screen_show_less",
        "agent_screen_empty",
        "agent_screen_retry_load",
        "agent_screen_open_task",
        "agent_screen_retry_task",
        "agent_screen_verify_result",
        "agent_screen_approval_risk",
        "agent_screen_approval_arguments",
        "agent_screen_approval_expiry",
        "agent_screen_copy",
        "agent_screen_retry_message",
        "agent_screen_delete_message",
        "agent_screen_load_older",
        "agent_screen_add_image",
        "agent_screen_remove_image",
        "agent_screen_open_model",
        "agent_screen_provider_settings",
        "agent_screen_runtime_install",
        "agent_screen_attachment_failed",
        "agent_screen_title",
        "agent_screen_load_failed",
        "agent_screen_default_scope",
        "agent_screen_retry_task_confirm",
        "agent_screen_retry_task_confirm_body",
        "agent_screen_more_actions",
        "agent_screen_open_artifact",
        "agent_screen_task_details",
        "agent_screen_acceptance",
        "agent_screen_review_summary",
        "agent_screen_unverified",
        "agent_screen_side_effects",
        "agent_screen_artifact_preview",
        "agent_screen_candidate_preview_truncated",
        "agent_screen_candidate_review_state",
        "agent_screen_risk_low",
        "agent_screen_risk_standard",
        "agent_screen_risk_high",
        "agent_screen_risk_critical",
        "agent_screen_risk_unknown",
        "agent_screen_attachment_limit",
        "agent_screen_candidate_proposed",
        "agent_screen_candidate_verified",
        "agent_screen_candidate_conflicted",
        "agent_screen_candidate_applied_pending",
        "agent_screen_tool_write",
        "agent_screen_tool_edit",
        "agent_screen_tool_delete",
        "agent_screen_tool_create_folder",
        "agent_screen_tool_move",
        "agent_screen_tool_copy",
        "agent_screen_tool_list",
        "agent_screen_tool_read",
        "agent_screen_tool_search",
        "agent_screen_tool_mounts",
        "agent_hub_open_workbench",
        "agent_hub_switch_bot",
        "agent_hub_input_placeholder",
        "agent_hub_voice_recording",
        "agent_hub_voice_cd",
        "agent_hub_voice_unavailable",
        "agent_hub_plus_cd",
        "agent_hub_scroll_bottom_cd",
        "agent_hub_thinking",
        "agent_hub_live_running",
        "agent_hub_live_approvals",
        "agent_hub_live_vdisplay",
        "agent_hub_mission_steps_count",
        "agent_hub_mission_view_computer",
        "agent_hub_mission_steer",
        "agent_hub_mission_steer_hint",
        "agent_hub_mission_steer_send",
        "agent_hub_mission_steered_badge",
        "agent_hub_mission_save_routine",
        "agent_hub_mission_saved_routine",
        "agent_hub_choice_selected",
        "agent_hub_cue_trigger_briefing",
        "agent_hub_cue_briefing_title",
        "agent_hub_cue_briefing_empty",
        "agent_hub_quick_briefing",
        "agent_hub_quick_teach",
        "agent_hub_quick_workbench",
        "agent_hub_tab_live",
        "agent_hub_tab_routines",
        "agent_hub_tab_squad",
        "agent_hub_tab_artifacts",
        "agent_hub_computer_sandbox_logs",
        "agent_hub_computer_clear_logs",
        "agent_hub_computer_vdisplay_title",
        "agent_hub_computer_vdisplay_takeover",
        "agent_hub_routines_new",
        "agent_hub_routines_title_hint",
        "agent_hub_routines_goal_hint",
        "agent_hub_routines_trigger_expr_hint",
        "agent_hub_routines_run_now",
        "agent_hub_trigger_manual",
        "agent_hub_trigger_cron",
        "agent_hub_trigger_notification",
        "agent_hub_trigger_channel",
        "agent_hub_teach_start",
        "agent_hub_teach_recording",
        "agent_hub_teach_step_hint",
        "agent_hub_teach_add_step",
        "agent_hub_teach_finish",
        "agent_hub_squad_active",
        "agent_hub_squad_switch",
        "agent_hub_squad_channels",
        "agent_hub_squad_open_group",
        "agent_hub_model_routing_title",
        "agent_hub_route_foreground",
        "agent_hub_route_background",
        "agent_hub_route_vision",
        "agent_hub_route_follow_default",
        "agent_hub_artifacts_history",
    )

    @Test
    fun `agent screen resources exist in every shipped locale`() {
        val missing = locales.flatMap { locale ->
            val file = resolveResourceFile(locale)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            val names = (0 until nodes.length).mapNotNull { index ->
                (nodes.item(index) as? Element)?.getAttribute("name")
            }.toSet()
            (requiredKeys - names).map { key -> "$locale:$key" }
        }

        assertTrue("missing Agent screen translations: $missing", missing.isEmpty())
    }

    private fun resolveResourceFile(locale: String): File = sequenceOf(
        File("src/main/res/$locale/strings_features.xml"),
        File("app/src/main/res/$locale/strings_features.xml"),
    ).firstOrNull(File::isFile) ?: error("missing strings_features.xml for $locale")
}
