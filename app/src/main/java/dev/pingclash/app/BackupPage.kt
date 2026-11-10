// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Settings > Backup and restore. A backup holds preferences only unless the user includes profiles,
 * which also writes subscription links and node passwords into the file.
 */
@Composable
internal fun BackupPage(model: AppViewModel, enabled: Boolean, connected: Boolean) {
    var withProfiles by rememberSaveable { mutableStateOf(false) }
    var confirmProfiles by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val exporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) model.exportBackup(uri, withProfiles)
        }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(model::restoreBackup)
        }
    SettingsSection(tr(R.string.ui_settings_backup)) {
        SettingSwitch(tr(R.string.ui_backup_include_profiles), withProfiles, enabled) {
            if (it) confirmProfiles = true else withProfiles = false
        }
        RowDivider(underIcons = false)
        ActionRow(tr(R.string.ui_backup_export), "backup-export", enabled = enabled) {
            exporter.launch("PingClash-backup.json")
        }
    }
    RestoreSection(enabled && !connected, connected) { confirmRestore = true }
    if (confirmProfiles)
        ConfirmDialog(
            tr(R.string.ui_backup_include_profiles),
            tr(R.string.ui_backup_profiles_warning),
            tr(R.string.ui_backup_include_confirm),
            onDismiss = { confirmProfiles = false },
        ) {
            withProfiles = true
        }
    if (confirmRestore)
        ConfirmDialog(
            tr(R.string.ui_backup_restore),
            tr(R.string.ui_backup_restore_warning),
            tr(R.string.ui_backup_choose_file),
            onDismiss = { confirmRestore = false },
        ) {
            importer.launch(BACKUP_TYPES)
        }
}

@Composable
private fun RestoreSection(enabled: Boolean, connected: Boolean, onRestore: () -> Unit) {
    // Restoring replaces settings the running tunnel uses.
    SettingsSection(footer = if (connected) tr(R.string.ui_backup_restore_connected) else null) {
        ActionRow(tr(R.string.ui_backup_restore), "backup-restore", enabled = enabled) {
            onRestore()
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton({
                onDismiss()
                onConfirm()
            }) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel_2)) } },
    )
}

// Some file managers label .json files as plain text or as generic bytes.
private val BACKUP_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")
