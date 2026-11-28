package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupSecretsEntryWiringTest {
    @Test
    fun manualCloudBackupUsesOuterChoiceAndDirectEntryDefaultsOff() {
        val backupSection = source("ui/settings/BackupSection.kt")
        val cloudPage = source("ui/settings/CloudBackupPage.kt")
        val destinations = source("ui/navigation/MuseDestinations.kt")

        assertTrue(backupSection.contains("var includeSecrets by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(backupSection.contains("onOpenCloudBackup(includeSecrets)"))
        assertTrue(cloudPage.contains("exportToCloud(includeSecrets = includeSecrets)"))
        assertFalse(cloudPage.contains("cloud_backup_include_secrets"))
        assertTrue(destinations.contains("data class SettingsCloudBackupRoute(val includeSecrets: Boolean = false)"))
    }

    @Test
    fun localPlaintextWarningIsBeforeCreateDocumentAndChoiceSurvivesSafCallback() {
        val backupSection = source("ui/settings/BackupSection.kt")
        val warningIndex = backupSection.indexOf("plainExportConfirmVisible = true")
        val confirmDialog = backupSection.substring(backupSection.indexOf("if (plainExportConfirmVisible)"))
        val confirmIndex = confirmDialog.indexOf("plainExportConfirmVisible = false")
        val launchIndex = confirmDialog.indexOf("launchExport()", confirmIndex)

        assertTrue(warningIndex >= 0)
        assertTrue(confirmIndex >= 0)
        assertTrue(launchIndex > confirmIndex)
        assertTrue(backupSection.contains("var pendingExportSecrets by rememberSaveable"))
        assertTrue(backupSection.contains("startExport(it, pendingExportSecrets)"))
    }

    private fun source(relative: String): String = File("src/main/java/io/zer0/muse/$relative").readText()
}
