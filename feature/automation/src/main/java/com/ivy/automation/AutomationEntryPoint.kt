package com.ivy.automation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ivy.automation.autoentry.AutoEntryProcessor
import com.ivy.automation.drivesync.DriveSyncManager
import com.ivy.automation.drivesync.DriveSyncScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Gives the background pieces (worker, SMS receiver, notification listener)
 * and the app shell access to the automation classes.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutomationEntryPoint {
    fun driveSyncManager(): DriveSyncManager
    fun driveSyncScheduler(): DriveSyncScheduler
    fun autoEntryProcessor(): AutoEntryProcessor
    fun dataStore(): DataStore<Preferences>

    companion object {
        fun get(context: Context): AutomationEntryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutomationEntryPoint::class.java
        )
    }
}
