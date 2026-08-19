package io.zer0.muse.notification

import android.net.Uri

/**
 * Muse 自有通知的点击目标。
 *
 * 通知只能携带一个 PendingIntent；把目标集中成受控的深链，避免各个
 * Worker/Service 自己拼回首页 Intent，导致点击后丢失上下文。
 */
sealed interface MuseNotificationTarget {
    val deepLink: Uri
    val requestKey: String

    data object Home : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://home")
        override val requestKey: String = "home"
    }

    data object Chat : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://chat")
        override val requestKey: String = "chat"
    }

    data class Session(val sessionId: String) : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://session/${Uri.encode(sessionId)}")
        override val requestKey: String = "session:$sessionId"
    }

    data object ScheduledTasks : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://scheduled-tasks")
        override val requestKey: String = "scheduled-tasks"
    }

    data class ScheduledTask(val taskId: String) : MuseNotificationTarget {
        override val deepLink = Uri.parse("muse://scheduled-task/${Uri.encode(taskId)}")
        override val requestKey: String = "scheduled-task:$taskId"
    }

    data object QuickNotes : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://quick-notes")
        override val requestKey: String = "quick-notes"
    }

    data class QuickNote(val noteId: String) : MuseNotificationTarget {
        override val deepLink = Uri.parse("muse://quick-note/${Uri.encode(noteId)}")
        override val requestKey: String = "quick-note:$noteId"
    }

    data object SettingsData : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://settings-data")
        override val requestKey: String = "settings-data"
    }

    data object CloudBackup : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://cloud-backup")
        override val requestKey: String = "cloud-backup"
    }

    data object SettingsAgent : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://settings-agent")
        override val requestKey: String = "settings-agent"
    }

    data object SettingsModel : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://settings-model")
        override val requestKey: String = "settings-model"
    }

    data object SettingsAbout : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://settings-about")
        override val requestKey: String = "settings-about"
    }

    data object Memory : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://memory")
        override val requestKey: String = "memory"
    }

    data object Knowledge : MuseNotificationTarget {
        override val deepLink: Uri = Uri.parse("muse://knowledge")
        override val requestKey: String = "knowledge"
    }

    data object KnowledgeBaseManage : MuseNotificationTarget {
        override val deepLink = Uri.parse("muse://knowledge-bases")
        override val requestKey: String = "knowledge-bases"
    }
}
