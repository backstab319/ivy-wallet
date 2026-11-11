package com.ivy.automation.autoentry

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ivy.automation.AutomationEntryPoint
import com.ivy.automation.AutomationPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Reads the notifications of UPI apps (and only those) and passes them to
 * [AutoEntryProcessor]. Runs only after the user gives Ivy Wallet
 * "Notification access" in the system settings.
 */
class PaymentNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val appName = paymentApps[sbn?.packageName] ?: return
        val extras = sbn?.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString().orEmpty()
        val text = listOf(title, body).filter { it.isNotBlank() }.joinToString(". ")
        if (text.isBlank()) return

        scope.launch {
            try {
                val entryPoint = AutomationEntryPoint.get(applicationContext)
                val enabled = entryPoint.dataStore().data.first()[
                    AutomationPrefs.AUTO_ENTRY_NOTIFICATIONS_ENABLED
                ]
                if (enabled == true) {
                    entryPoint.autoEntryProcessor().onMessage(text, source = appName)
                }
            } catch (e: Exception) {
                Timber.e(e, "Automatic entry from a notification failed")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Package name -> name shown in the expense description. */
        val paymentApps = mapOf(
            "com.google.android.apps.nbu.paisa.user" to "Google Pay",
            "com.phonepe.app" to "PhonePe",
            "net.one97.paytm" to "Paytm",
            "in.org.npci.upiapp" to "BHIM",
            "in.amazon.mShop.android.shopping" to "Amazon Pay",
            "com.dreamplug.androidapp" to "CRED",
            "com.naviapp" to "Navi",
            "money.super.payments" to "super.money",
            "com.mobikwik_new" to "MobiKwik",
            "com.freecharge.android" to "Freecharge",
        )

        /** Whether the user gave this app "Notification access" in the system settings. */
        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val me = ComponentName(context, PaymentNotificationListener::class.java)
            return enabled.split(":").any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
