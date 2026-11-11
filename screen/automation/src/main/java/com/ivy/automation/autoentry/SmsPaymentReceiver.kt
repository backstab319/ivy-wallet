package com.ivy.automation.autoentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ivy.automation.AutomationEntryPoint
import com.ivy.automation.AutomationPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Receives incoming SMS and passes the ones from banks to [AutoEntryProcessor].
 */
class SmsPaymentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return

        // Banks send from sender ids like "VM-HDFCBK". A text from a phone number
        // is a person, however much it looks like a bank message.
        val sender = parts.first().originatingAddress.orEmpty()
        if (personalNumber.matches(sender)) return
        val text = parts.joinToString(separator = "") { it.messageBody.orEmpty() }

        val pending = goAsync()
        scope.launch {
            try {
                val entryPoint = AutomationEntryPoint.get(context)
                val enabled = entryPoint.dataStore().data.first()[AutomationPrefs.AUTO_ENTRY_SMS_ENABLED]
                if (enabled == true) {
                    entryPoint.autoEntryProcessor().onMessage(text, source = "SMS ($sender)")
                }
            } catch (e: Exception) {
                Timber.e(e, "Automatic entry from SMS failed")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val personalNumber = Regex("^\\+?[0-9 ]{7,}$")
    }
}
