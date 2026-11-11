package com.ivy.automation.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ivy.automation.autoentry.PaymentNotificationListener
import com.ivy.navigation.navigation
import com.ivy.navigation.screenScopedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreenImpl(
    viewModel: AutomationViewModel = screenScopedViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val nav = navigation()

    // Google's account chooser / consent screen
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onConsentResult(result.data)
        } else {
            viewModel.onConsentCancelled()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.consentRequests.collect { consent ->
            consentLauncher.launch(IntentSenderRequest.Builder(consent.intentSender).build())
        }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        viewModel.setSmsEnabled(granted[Manifest.permission.RECEIVE_SMS] == true)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional: only used to tell the user that an expense was added */ }

    // Both can be changed in the system settings while this screen is in the background
    var hasSmsPermission by remember { mutableStateOf(hasSmsPermission(context)) }
    var hasNotificationAccess by remember { mutableStateOf(PaymentNotificationListener.hasAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasSmsPermission = hasSmsPermission(context)
                hasNotificationAccess = PaymentNotificationListener.hasAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun askToShowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Sync & automation") },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Google Drive") }
            item {
                ToggleCard(
                    title = "Backup & sync with Google Drive",
                    description = "Keeps a copy of your data in your Google Drive and updates it " +
                        "automatically. Turn it on with the same Google account on another " +
                        "device to keep both in sync.",
                    checked = state.driveSyncEnabled,
                    onCheckedChange = viewModel::setDriveSync,
                )
            }
            if (state.driveSyncEnabled || state.driveError != null) {
                item {
                    DriveStatusCard(
                        state = state,
                        onSyncNow = viewModel::syncNow,
                    )
                }
            }

            item { SectionTitle("Automatic entries") }
            item {
                ToggleCard(
                    title = "From bank SMS",
                    description = "Adds an expense when your bank texts you that money was debited.",
                    checked = state.smsEnabled && hasSmsPermission,
                    onCheckedChange = { enable ->
                        if (enable && !hasSmsPermission) {
                            smsPermissionLauncher.launch(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    arrayOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                } else {
                                    arrayOf(Manifest.permission.RECEIVE_SMS)
                                }
                            )
                        } else {
                            viewModel.setSmsEnabled(enable)
                            if (enable) askToShowNotifications()
                        }
                    },
                )
            }
            item {
                ToggleCard(
                    title = "From UPI app notifications",
                    description = "Adds an expense when Google Pay, PhonePe, Paytm, BHIM, CRED or " +
                        "another UPI app notifies you about a payment you made.",
                    checked = state.notificationsEnabled && hasNotificationAccess,
                    onCheckedChange = { enable ->
                        viewModel.setNotificationsEnabled(enable)
                        if (enable) {
                            askToShowNotifications()
                            if (!hasNotificationAccess) {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    },
                )
            }
            if (state.notificationsEnabled && !hasNotificationAccess) {
                item {
                    Text(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        text = "Ivy Wallet doesn't have notification access yet. Turn it on for " +
                            "\"Ivy Wallet automatic entries\" in the screen that just opened.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (state.accounts.isNotEmpty()) {
                item { SectionTitle("Add automatic entries to") }
                items(state.accounts, key = { it.id }) { account ->
                    AccountRow(
                        account = account,
                        selected = account.id == state.selectedAccountId,
                        onSelect = { viewModel.selectAccount(account.id) },
                    )
                }
            }
        }
    }
}

private fun hasSmsPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = description, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun DriveStatusCard(
    state: AutomationUiState,
    onSyncNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (state.driveSyncEnabled) {
                Text(
                    text = when {
                        state.syncing -> "Syncing…"
                        state.lastSyncEpochMs != null -> "Last synced " +
                            DateUtils.getRelativeTimeSpanString(
                                state.lastSyncEpochMs,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString().lowercase()

                        else -> "Not synced yet"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.driveError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.driveSyncEnabled) {
                Spacer(Modifier.size(8.dp))
                if (state.syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else if (state.driveError != null) {
                    Button(onClick = onSyncNow) { Text("Try again") }
                } else {
                    OutlinedButton(onClick = onSyncNow) { Text("Sync now") }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = account.name, style = MaterialTheme.typography.bodyLarge)
    }
}
