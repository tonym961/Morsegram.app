package it.iotatec.morsegram.wear.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import it.iotatec.morsegram.wear.BuildConfig
import it.iotatec.morsegram.wear.R

/**
 * Impostazioni: account, modalità aereo, anteprima notifiche, stato connessione, informazioni, uscita.
 *
 * [onBack] fa parte del contratto di navigazione; su Wear il "back" è lo swipe-to-dismiss gestito
 * dallo SwipeDismissableNavHost, quindi la schermata non ha un tasto indietro proprio. Dopo
 * l'uscita dall'account il core ricrea il client TDLib e l'host torna da solo alla schermata di accesso.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val account by vm.account.collectAsStateWithLifecycle()
    val airplane by vm.airplane.collectAsStateWithLifecycle()
    val notifyPreview by vm.notifyPreview.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val loggingOut by vm.loggingOut.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showLogout by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    if (loggingOut) {
        LoggingOut()
        return
    }

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text(stringResource(R.string.settings_title)) } }
            item { AccountHeader(account) }

            item {
                ToggleChip(
                    checked = airplane,
                    onCheckedChange = vm::setAirplane,
                    label = { Text(stringResource(R.string.settings_airplane), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = if (airplane) {
                        { Text(stringResource(R.string.settings_airplane_on), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else null,
                    toggleControl = { Switch(checked = airplane) },
                    appIcon = { Text("✈", style = MaterialTheme.typography.title3) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.settings_airplane_desc),
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }

            item {
                ToggleChip(
                    checked = notifyPreview,
                    onCheckedChange = vm::setNotifyPreview,
                    label = { Text(stringResource(R.string.settings_notify), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        Text(
                            stringResource(if (notifyPreview) R.string.settings_notify_preview_on else R.string.settings_notify_preview_off),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    toggleControl = { Switch(checked = notifyPreview) },
                    appIcon = {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(ChipDefaults.IconSize),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { ConnectionStatus(labelRes = connectionLabelRes(connection, airplane), highlighted = airplane) }

            item {
                Chip(
                    label = { Text(stringResource(R.string.settings_about), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { showAbout = true },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    label = { Text(stringResource(R.string.settings_logout), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { showLogout = true },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = MaterialTheme.colors.error,
                            modifier = Modifier.size(ChipDefaults.IconSize),
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { msg ->
                item {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }

    Dialog(showDialog = showLogout, onDismissRequest = { showLogout = false }) {
        val text = stringResource(R.string.settings_logout_text) +
            if (airplane) "\n" + stringResource(R.string.settings_logout_text_airplane) else ""
        Alert(
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(24.dp),
                )
            },
            title = { Text(stringResource(R.string.settings_logout_title), textAlign = TextAlign.Center) },
            negativeButton = {
                Button(onClick = { showLogout = false }, colors = ButtonDefaults.secondaryButtonColors()) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_cancel))
                }
            },
            positiveButton = {
                Button(onClick = { showLogout = false; vm.logOut() }) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.settings_confirm))
                }
            },
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    Dialog(showDialog = showAbout, onDismissRequest = { showAbout = false }) {
        Alert(
            icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colors.primary, modifier = Modifier.size(24.dp)) },
            title = { Text(stringResource(R.string.app_name), textAlign = TextAlign.Center) },
            message = {
                Text(
                    stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                )
            },
        ) {
            item { AboutLine(stringResource(R.string.settings_about_unofficial)) }
            item { AboutLine(stringResource(R.string.settings_about_license)) }
            item { AboutLine(stringResource(R.string.settings_about_tdlib)) }
        }
    }
}

@Composable
private fun AccountHeader(state: AccountState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.AccountCircle,
            contentDescription = stringResource(R.string.settings_account_icon),
            tint = MaterialTheme.colors.primary,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(4.dp))
        when (state) {
            AccountState.Loading -> Text(
                stringResource(R.string.settings_account_loading),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
            AccountState.Unavailable -> Text(
                stringResource(R.string.settings_account_unavailable),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
            is AccountState.Loaded -> {
                val info = state.info
                Text(
                    text = info.name.ifBlank { info.username ?: stringResource(R.string.app_name) },
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                info.username?.let {
                    Text(it, style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                info.phoneMasked?.let {
                    Text(it, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatus(labelRes: Int, highlighted: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.settings_connection),
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
        )
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.body2,
            color = if (highlighted) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AboutLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption1,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun LoggingOut() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(indicatorColor = MaterialTheme.colors.primary)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_logout_progress), style = MaterialTheme.typography.body2)
        }
    }
}
