package com.ivy.automation.drivesync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

sealed interface DriveAccess {
    data class Granted(val accessToken: String) : DriveAccess

    /** The user has to pick a Google account / approve access. Launch [consent] from an Activity. */
    data class NeedsConsent(val consent: PendingIntent) : DriveAccess
}

/**
 * Asks Google Play services for access to the files this app creates in the user's
 * Google Drive. The app can't see any other file in the Drive.
 */
class DriveAuth @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {
    private val request: AuthorizationRequest
        get() = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()

    suspend fun access(): DriveAccess {
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        return result.toAccess()
    }

    /** Call with the data of the Activity result after launching [DriveAccess.NeedsConsent.consent]. */
    fun accessFromConsentResult(data: Intent?): DriveAccess.Granted? {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        return result.toAccess() as? DriveAccess.Granted
    }

    private fun AuthorizationResult.toAccess(): DriveAccess {
        val pendingIntent = pendingIntent
        val token = accessToken
        return when {
            hasResolution() && pendingIntent != null -> DriveAccess.NeedsConsent(pendingIntent)
            token != null -> DriveAccess.Granted(token)
            else -> error("Google didn't return an access token")
        }
    }

    companion object {
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
