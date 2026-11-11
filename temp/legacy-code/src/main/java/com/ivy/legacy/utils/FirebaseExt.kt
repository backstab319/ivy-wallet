package com.ivy.legacy.utils

import timber.log.Timber

// This fork doesn't report to Ivy's Firebase project: errors are only logged on the device.

fun sendToCrashlytics(
    msg: String
) {
    DeveloperException(msg).sendToCrashlytics(msg)
}

fun Exception.sendToCrashlytics(
    clarification: String? = null
) {
    clarification?.let {
        logToCrashlytics("Log: $it")
    }
    Timber.e(this)
}

fun logToCrashlytics(msg: String) {
    Timber.d(msg)
}

class DeveloperException(msg: String) : Exception(msg)
