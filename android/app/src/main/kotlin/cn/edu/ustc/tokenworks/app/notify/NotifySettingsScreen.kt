package cn.edu.ustc.tokenworks

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*

@Composable
fun NotifySettingsScreen(
    state: NotifyUiState, onNotificationsEnabled: (Boolean) -> Unit,
    onBackgroundConnectionEnabled: (Boolean) -> Unit, onBack: () -> Unit,
) {
    val context = LocalContext.current
    var permissionTarget by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val target = permissionTarget; permissionTarget = null
        if (allowed) {
            if (target == "push") onNotificationsEnabled(true)
            if (target == "background") onBackgroundConnectionEnabled(true)
        }
    }
    fun enable(target: String) {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsPermitted(context)) {
            permissionTarget = target; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (target == "push") onNotificationsEnabled(true) else onBackgroundConnectionEnabled(true)
    }
    Scaffold(topBar = {
        TokenWorksTopBar(title = stringResource(R.string.notify_title), contentMaxWidth = TokenWorksSpacing.readingWidth,
            navigationIcon = { IconButton(onBack, Modifier.sizeIn(minWidth = TokenWorksSpacing.minimumTouchTarget, minHeight = TokenWorksSpacing.minimumTouchTarget)) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.notify_back))
            } })
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth().testTag("notification-settings"),
                contentPadding = PaddingValues(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.lg)) {
                item { Text(stringResource(R.string.notify_privacy), style = MaterialTheme.typography.bodyMedium) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        Text(stringResource(R.string.notify_push), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        NotifyToggle(stringResource(R.string.notify_opt_in), state.enabled, !state.busy && state.configured, "notification-consent") {
                            if (it) enable("push") else onNotificationsEnabled(false)
                        }
                        Text(stringResource(when {
                            !state.configured -> R.string.notify_unconfigured
                            !state.permissionGranted -> R.string.notify_permission_missing
                            state.busy -> R.string.notify_registering
                            state.registered -> R.string.notify_registered
                            else -> R.string.notify_disabled
                        }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        Text(stringResource(R.string.notify_background), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        NotifyToggle(stringResource(R.string.notify_background_opt_in), state.backgroundEnabled, state.backgroundEnabled || state.canHoldConnection, "background-connection") {
                            if (it) enable("background") else onBackgroundConnectionEnabled(false)
                        }
                        Text(stringResource(if (state.backgroundActive) R.string.notify_background_active else R.string.notify_background_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let { code -> item {
                    val text = when (code) {
                        "push.not_configured" -> R.string.notify_unconfigured
                        "push.permission_required", "push.background_requires_session_permission" -> R.string.notify_permission_session
                        "push.reference_rejected" -> R.string.notify_reference_rejected
                        "push.revocation_unconfirmed" -> R.string.notify_revocation_unconfirmed
                        else -> R.string.notify_failed
                    }
                    Text(stringResource(text), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                } }
            }
        }
    }
}

@Composable
private fun NotifyToggle(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.sizeIn(minWidth = TokenWorksSpacing.minimumTouchTarget,
            minHeight = TokenWorksSpacing.minimumTouchTarget).testTag(tag).semantics { contentDescription = label })
    }
}
