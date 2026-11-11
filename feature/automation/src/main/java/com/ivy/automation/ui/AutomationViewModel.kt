package com.ivy.automation.ui

import android.app.PendingIntent
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ivy.automation.AutomationPrefs
import com.ivy.automation.drivesync.DriveAccess
import com.ivy.automation.drivesync.DriveAuth
import com.ivy.automation.drivesync.DriveSyncManager
import com.ivy.automation.drivesync.DriveSyncScheduler
import com.ivy.automation.drivesync.SyncResult
import com.ivy.data.db.dao.read.AccountDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountOption(val id: String, val name: String)

data class AutomationUiState(
    val driveSyncEnabled: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncEpochMs: Long? = null,
    val driveError: String? = null,
    val smsEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val accounts: List<AccountOption> = emptyList(),
    val selectedAccountId: String? = null,
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val driveAuth: DriveAuth,
    private val syncManager: DriveSyncManager,
    private val syncScheduler: DriveSyncScheduler,
    private val accountDao: AccountDao,
) : ViewModel() {

    private data class Local(
        val syncing: Boolean = false,
        val error: String? = null,
        val accounts: List<AccountOption> = emptyList(),
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<AutomationUiState> = combine(dataStore.data, local) { prefs, local ->
        AutomationUiState(
            driveSyncEnabled = prefs[AutomationPrefs.DRIVE_SYNC_ENABLED] ?: false,
            syncing = local.syncing,
            lastSyncEpochMs = prefs[AutomationPrefs.DRIVE_LAST_SYNC_EPOCH_MS],
            driveError = local.error ?: prefs[AutomationPrefs.DRIVE_LAST_ERROR],
            smsEnabled = prefs[AutomationPrefs.AUTO_ENTRY_SMS_ENABLED] ?: false,
            notificationsEnabled = prefs[AutomationPrefs.AUTO_ENTRY_NOTIFICATIONS_ENABLED] ?: false,
            accounts = local.accounts,
            selectedAccountId = prefs[AutomationPrefs.AUTO_ENTRY_ACCOUNT_ID]
                ?: local.accounts.firstOrNull()?.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationUiState())

    /** Google's "choose an account / allow access" screen that the UI has to launch. */
    private val consentChannel = Channel<PendingIntent>(Channel.BUFFERED)
    val consentRequests = consentChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val accounts = accountDao.findAll().sortedBy { it.orderNum }
                .map { AccountOption(id = it.id.toString(), name = it.name) }
            local.update { it.copy(accounts = accounts) }
        }
    }

    fun setDriveSync(enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch {
                syncManager.setEnabled(false)
                syncScheduler.cancelAll()
                local.update { it.copy(error = null) }
            }
            return
        }
        viewModelScope.launch {
            local.update { it.copy(error = null) }
            try {
                when (val access = driveAuth.access()) {
                    is DriveAccess.Granted -> enableAndSync()
                    is DriveAccess.NeedsConsent -> consentChannel.send(access.consent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = connectError(e)) }
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            try {
                if (driveAuth.accessFromConsentResult(data) != null) {
                    enableAndSync()
                } else {
                    local.update { it.copy(error = "Google Drive access wasn't granted.") }
                }
            } catch (e: Exception) {
                local.update { it.copy(error = connectError(e)) }
            }
        }
    }

    fun onConsentCancelled() {
        local.update { it.copy(error = "Google sign-in was cancelled.") }
    }

    fun syncNow() {
        viewModelScope.launch { runSync() }
    }

    fun setSmsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[AutomationPrefs.AUTO_ENTRY_SMS_ENABLED] = enabled }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[AutomationPrefs.AUTO_ENTRY_NOTIFICATIONS_ENABLED] = enabled }
        }
    }

    fun selectAccount(id: String) {
        viewModelScope.launch {
            dataStore.edit { it[AutomationPrefs.AUTO_ENTRY_ACCOUNT_ID] = id }
        }
    }

    private suspend fun enableAndSync() {
        syncManager.setEnabled(true)
        syncScheduler.schedulePeriodic()
        runSync()
    }

    private suspend fun runSync() {
        if (local.value.syncing) return
        local.update { it.copy(syncing = true, error = null) }
        val result = syncManager.sync()
        local.update { it.copy(syncing = false) }
        if (result is SyncResult.NeedsConsent) consentChannel.send(result.consent)
    }

    private fun connectError(e: Exception): String {
        return "Couldn't connect to Google Drive: ${e.message ?: e.javaClass.simpleName}. " +
            "If this is your own build, check that its package name and signing key are " +
            "registered as an Android OAuth client in your Google Cloud project " +
            "(see docs/Drive-Sync-Setup.md)."
    }
}
