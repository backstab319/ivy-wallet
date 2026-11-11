package com.ivy.automation.autoentry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.ivy.automation.AutomationPrefs
import com.ivy.automation.drivesync.DriveSyncScheduler
import com.ivy.base.model.TransactionType
import com.ivy.data.db.dao.read.AccountDao
import com.ivy.data.db.dao.write.WriteTransactionDao
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.domain.AppStarter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns a bank SMS or a UPI app notification into an expense.
 */
@Singleton
class AutoEntryProcessor @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val accountDao: AccountDao,
    private val transactionWriter: WriteTransactionDao,
    private val appStarter: AppStarter,
    private val syncScheduler: DriveSyncScheduler,
) {
    private val mutex = Mutex()

    /**
     * @param text the SMS or notification text
     * @param source shown in the description of the created expense, e.g. "SMS" or "PhonePe"
     */
    suspend fun onMessage(text: String, source: String) {
        val payment = PaymentMessageParser.parse(text) ?: return
        val now = System.currentTimeMillis()

        val added = mutex.withLock {
            val prefs = dataStore.data.first()
            val recent = RecentPayment.decode(prefs[AutomationPrefs.AUTO_ENTRY_RECENT])
                .filter { now - it.timeMs < REMEMBER_MS }
            val current = RecentPayment(
                amountPaise = (payment.amount * 100).roundToLong(),
                reference = payment.reference,
                timeMs = now,
            )
            if (recent.any { it.isSamePaymentAs(current) }) {
                // The bank SMS and the UPI app both report the same payment
                return@withLock false
            }

            val account = pickAccount(prefs[AutomationPrefs.AUTO_ENTRY_ACCOUNT_ID])
            if (account == null) {
                Timber.w("Automatic entry skipped: there is no account to add it to")
                return@withLock false
            }

            transactionWriter.save(
                TransactionEntity(
                    accountId = account.id,
                    type = TransactionType.EXPENSE,
                    amount = payment.amount,
                    title = payment.merchant ?: "Payment",
                    description = "Added automatically from $source:\n$text",
                    dateTime = Instant.ofEpochMilli(now),
                    id = UUID.randomUUID(),
                )
            )
            dataStore.edit {
                it[AutomationPrefs.AUTO_ENTRY_RECENT] =
                    RecentPayment.encode((recent + current).takeLast(MAX_REMEMBERED))
            }
            notifyAdded(payment, account)
            true
        }

        if (added) syncScheduler.syncSoonIfEnabled(delaySeconds = 30)
    }

    private suspend fun pickAccount(preferredId: String?): AccountEntity? {
        val accounts = accountDao.findAll()
        return accounts.firstOrNull { it.id.toString() == preferredId }
            ?: accounts.minByOrNull { it.orderNum }
    }

    private fun notifyAdded(payment: ParsedPayment, account: AccountEntity) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Automatic entries", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Tells you when an expense was added automatically." }
            )
            val amount = String.format(Locale.ENGLISH, "%,.2f", payment.amount)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.ivy.ui.R.drawable.ic_notification)
                .setContentTitle("Expense added: $amount")
                .setContentText(
                    listOfNotNull(payment.merchant, "in ${account.name}").joinToString(" · ")
                )
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        appStarter.getRootIntent(),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            manager.notify(System.nanoTime().toInt(), notification)
        } catch (e: Exception) {
            // Notifications are optional (the permission may be denied)
            Timber.w(e, "Couldn't show the automatic entry notification")
        }
    }

    private data class RecentPayment(
        val amountPaise: Long,
        val reference: String?,
        val timeMs: Long,
    ) {
        fun isSamePaymentAs(other: RecentPayment): Boolean {
            if (amountPaise != other.amountPaise) return false
            return if (reference != null && other.reference != null) {
                reference == other.reference
            } else {
                abs(timeMs - other.timeMs) < SAME_PAYMENT_WINDOW_MS
            }
        }

        companion object {
            fun encode(payments: List<RecentPayment>): String = payments.joinToString(";") {
                "${it.amountPaise}|${it.reference.orEmpty()}|${it.timeMs}"
            }

            fun decode(value: String?): List<RecentPayment> = value.orEmpty().split(";").mapNotNull {
                val parts = it.split("|")
                if (parts.size != 3) return@mapNotNull null
                RecentPayment(
                    amountPaise = parts[0].toLongOrNull() ?: return@mapNotNull null,
                    reference = parts[1].ifEmpty { null },
                    timeMs = parts[2].toLongOrNull() ?: return@mapNotNull null,
                )
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "automatic_entries"
        const val MAX_REMEMBERED = 30
        const val REMEMBER_MS = 48 * 60 * 60 * 1000L
        const val SAME_PAYMENT_WINDOW_MS = 3 * 60 * 1000L
    }
}
