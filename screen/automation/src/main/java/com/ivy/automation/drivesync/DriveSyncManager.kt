package com.ivy.automation.drivesync

import android.app.PendingIntent
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.ivy.automation.AutomationPrefs
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.backup.BackupDataUseCase
import com.ivy.data.backup.IvyWalletCompleteData
import com.ivy.data.db.dao.write.WriteAccountDao
import com.ivy.data.db.dao.write.WriteBudgetDao
import com.ivy.data.db.dao.write.WriteCategoryDao
import com.ivy.data.db.dao.write.WriteLoanDao
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.db.dao.write.WritePlannedPaymentRuleDao
import com.ivy.data.db.dao.write.WriteTagAssociationDao
import com.ivy.data.db.dao.write.WriteTagDao
import com.ivy.data.db.dao.write.WriteTransactionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncResult {
    data object Success : SyncResult
    data object Disabled : SyncResult
    data class NeedsConsent(val consent: PendingIntent) : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * Keeps the data of this device and one JSON file in the user's Google Drive the same.
 * With one device it works as an automatic backup; with more it is a two-way sync.
 */
@Singleton
@Suppress("LongParameterList")
class DriveSyncManager @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val auth: DriveAuth,
    private val api: DriveApi,
    private val backup: BackupDataUseCase,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
    private val dataObserver: DataObserver,
    private val accountWriter: WriteAccountDao,
    private val transactionWriter: WriteTransactionDao,
    private val categoryWriter: WriteCategoryDao,
    private val budgetWriter: WriteBudgetDao,
    private val loanWriter: WriteLoanDao,
    private val loanRecordWriter: WriteLoanRecordDao,
    private val plannedPaymentRuleWriter: WritePlannedPaymentRuleDao,
    private val tagWriter: WriteTagDao,
    private val tagAssociationWriter: WriteTagAssociationDao,
) {
    private val mutex = Mutex()

    /** The data both this device and Google Drive had after the last successful sync. */
    private val baseFile: File
        get() = File(context.filesDir, "drive_sync_base.json")

    suspend fun isEnabled(): Boolean =
        dataStore.data.first()[AutomationPrefs.DRIVE_SYNC_ENABLED] ?: false

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit {
            it[AutomationPrefs.DRIVE_SYNC_ENABLED] = enabled
            it.remove(AutomationPrefs.DRIVE_LAST_ERROR)
        }
    }

    suspend fun sync(): SyncResult = mutex.withLock {
        if (!isEnabled()) return SyncResult.Disabled
        withContext(dispatchers.io) {
            try {
                when (val access = auth.access()) {
                    is DriveAccess.NeedsConsent -> {
                        saveError("Google sign-in needed. Open Sync & automation to reconnect.")
                        SyncResult.NeedsConsent(access.consent)
                    }

                    is DriveAccess.Granted -> {
                        sync(access.accessToken)
                        SyncResult.Success
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Google Drive sync failed")
                val message = e.message ?: e.javaClass.simpleName
                saveError(message)
                SyncResult.Failed(message)
            }
        }
    }

    private suspend fun sync(token: String) {
        val prefs = dataStore.data.first()
        val local = backup.generateJsonBackup()
        val remoteFile = api.find(token, FILE_NAME)

        // A wiped or freshly installed app must restore from Drive,
        // not tell Drive that everything was deleted.
        val base = readBase()?.takeUnless { isEmpty(local) && !isEmpty(it) }

        val remoteIsBase = base != null && remoteFile?.md5 != null &&
            remoteFile.id == prefs[AutomationPrefs.DRIVE_FILE_ID] &&
            remoteFile.md5 == prefs[AutomationPrefs.DRIVE_FILE_MD5]
        val remote = when {
            remoteFile == null -> null
            remoteIsBase -> base
            else -> api.download(token, remoteFile.id)
        }

        val merged = SyncMerger.merge(json, local = local, base = base, remote = remote)
        val current = if (SyncMerger.sameData(json, merged, local)) {
            local
        } else {
            applyToThisDevice(before = local, after = merged)
            backup.generateJsonBackup()
        }

        val uploaded = when {
            remoteFile == null -> api.create(token, FILE_NAME, current)
            remote == null || !SyncMerger.sameData(json, current, remote) ->
                api.update(token, remoteFile.id, current)

            else -> remoteFile
        }

        baseFile.writeText(current)
        dataStore.edit {
            it[AutomationPrefs.DRIVE_FILE_ID] = uploaded.id
            uploaded.md5?.let { md5 -> it[AutomationPrefs.DRIVE_FILE_MD5] = md5 }
                ?: it.remove(AutomationPrefs.DRIVE_FILE_MD5)
            it[AutomationPrefs.DRIVE_LAST_SYNC_EPOCH_MS] = System.currentTimeMillis()
            it.remove(AutomationPrefs.DRIVE_LAST_ERROR)
        }
    }

    private suspend fun applyToThisDevice(before: String, after: String) {
        val old = json.decodeFromString<IvyWalletCompleteData>(before)
        val new = json.decodeFromString<IvyWalletCompleteData>(after)

        // Adds and updates
        backup.importJson(after, dedupeByName = false)

        // Deletions
        val accounts = new.accounts.mapTo(HashSet()) { it.id }
        val transactions = new.transactions.mapTo(HashSet()) { it.id }
        val categories = new.categories.mapTo(HashSet()) { it.id }
        val budgets = new.budgets.mapTo(HashSet()) { it.id }
        val loans = new.loans.mapTo(HashSet()) { it.id }
        val loanRecords = new.loanRecords.mapTo(HashSet()) { it.id }
        val plannedPayments = new.plannedPaymentRules.mapTo(HashSet()) { it.id }
        val tags = new.tags.mapTo(HashSet()) { it.id }
        val tagAssociations = new.tagAssociations.mapTo(HashSet()) { it.tagId to it.associatedId }

        old.transactions.filter { it.id !in transactions }.forEach { transactionWriter.deleteById(it.id) }
        old.accounts.filter { it.id !in accounts }.forEach { accountWriter.deleteById(it.id) }
        old.categories.filter { it.id !in categories }.forEach { categoryWriter.deleteById(it.id) }
        old.budgets.filter { it.id !in budgets }.forEach { budgetWriter.deleteById(it.id) }
        old.loans.filter { it.id !in loans }.forEach { loanWriter.deleteById(it.id) }
        old.loanRecords.filter { it.id !in loanRecords }.forEach { loanRecordWriter.deleteById(it.id) }
        old.plannedPaymentRules.filter { it.id !in plannedPayments }
            .forEach { plannedPaymentRuleWriter.deleteById(it.id) }
        old.tags.filter { it.id !in tags }.forEach { tagWriter.deleteById(it.id) }
        old.tagAssociations.filter { (it.tagId to it.associatedId) !in tagAssociations }
            .forEach { tagAssociationWriter.deleteId(it.tagId, it.associatedId) }

        dataObserver.post(DataWriteEvent.AllDataChange)
    }

    private fun isEmpty(data: String): Boolean {
        val parsed = json.decodeFromString<IvyWalletCompleteData>(data)
        return parsed.accounts.isEmpty() && parsed.transactions.isEmpty()
    }

    private fun readBase(): String? = baseFile.takeIf { it.exists() }?.readText()

    private suspend fun saveError(message: String) {
        dataStore.edit { it[AutomationPrefs.DRIVE_LAST_ERROR] = message }
    }

    companion object {
        /** Name of the file in the user's Google Drive. */
        const val FILE_NAME = "ivy-wallet-sync.json"
    }
}
