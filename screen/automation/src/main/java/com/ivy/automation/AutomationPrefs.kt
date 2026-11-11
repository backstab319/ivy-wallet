package com.ivy.automation

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object AutomationPrefs {
    // Google Drive sync
    val DRIVE_SYNC_ENABLED = booleanPreferencesKey("drive_sync_enabled")
    val DRIVE_LAST_SYNC_EPOCH_MS = longPreferencesKey("drive_sync_last_success_epoch_ms")
    val DRIVE_LAST_ERROR = stringPreferencesKey("drive_sync_last_error")
    val DRIVE_FILE_ID = stringPreferencesKey("drive_sync_file_id")
    val DRIVE_FILE_MD5 = stringPreferencesKey("drive_sync_file_md5")

    // Automatic entries
    val AUTO_ENTRY_SMS_ENABLED = booleanPreferencesKey("auto_entry_sms_enabled")
    val AUTO_ENTRY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("auto_entry_notifications_enabled")
    val AUTO_ENTRY_ACCOUNT_ID = stringPreferencesKey("auto_entry_account_id")
    val AUTO_ENTRY_RECENT = stringPreferencesKey("auto_entry_recent_payments")
}
