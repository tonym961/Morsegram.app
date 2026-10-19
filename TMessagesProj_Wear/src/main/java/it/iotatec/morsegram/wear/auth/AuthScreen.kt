package it.iotatec.morsegram.wear.auth

import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.LocalContentColor
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import dev.g000sha256.tdl.dto.ConnectionState
import dev.g000sha256.tdl.dto.ConnectionStateConnecting
import dev.g000sha256.tdl.dto.ConnectionStateConnectingToProxy
import dev.g000sha256.tdl.dto.ConnectionStateUpdating
import dev.g000sha256.tdl.dto.ConnectionStateWaitingForNetwork
import it.iotatec.morsegram.wear.R

/**
 * Schermata di accesso, mostrata dal core finché `Tdlib.authState` non è `AuthorizationStateReady`.
 * Tutta la logica è in [AuthViewModel]; qui solo la resa dello stato.
 */
@Composable
fun AuthScreen(vm: AuthViewModel = viewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val conn by vm.connectionState.collectAsStateWithLifecycle()

    when (val s = state) {
        AuthUiState.Loading -> LoadingScreen(conn)
        AuthUiState.Intro -> IntroScreen(conn, onBridge = vm::startBridge, onQr = vm::startQr)
        is AuthUiState.Bridge -> SwipeBack(onBack = vm::back, background = { IntroScreen(conn, {}, {}) }) {
            KeepScreenOn()
            BridgeScreen(s.step, onRetry = vm::startBridge, onQr = vm::startQr, onBack = vm::back)
        }
        is AuthUiState.Qr -> SwipeBack(onBack = vm::back, background = { IntroScreen(conn, {}, {}) }) {
            KeepScreenOn()
            QrScreen(s, conn, onBack = vm::back)
        }
        is AuthUiState.Password -> PasswordScreen(s, onSubmit = vm::submitPassword, onRestart = vm::restart)
        is AuthUiState.FinishOnPhone -> FinishOnPhoneScreen(s, onRetry = vm::retryQr, onRestart = vm::restart)
    }
}

// ---- schermate -----------------------------------------------------------------------------------

@Composable
private fun LoadingScreen(conn: ConnectionState?) {
    Scaffold(timeText = { TimeText() }) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spinner()
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.auth_loading), style = MaterialTheme.typography.body2)
            ConnectionLine(conn)
        }
    }
}

@Composable
private fun IntroScreen(conn: ConnectionState?, onBridge: () -> Unit, onQr: () -> Unit) {
    val connText = connectionText(conn)
    AuthList(centerIndex = 2) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.title2,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
            )
        }
        item { Body(stringResource(R.string.auth_intro_unofficial), style = MaterialTheme.typography.caption2) }
        item {
            ActionChip(stringResource(R.string.auth_chip_phone), onBridge, icon = { PhoneIcon() })
        }
        item {
            ActionChip(stringResource(R.string.auth_chip_qr), onQr, primary = false, icon = { QrGlyph() })
        }
        if (connText != null) item { Caption(connText) }
    }
}

@Composable
private fun BridgeScreen(step: BridgeStep, onRetry: () -> Unit, onQr: () -> Unit, onBack: () -> Unit) {
    AuthList(centerIndex = if (step is BridgeStep.LoggingIn) 0 else 1) {
        when (step) {
            BridgeStep.Searching -> {
                item { Spinner() }
                item { Body(stringResource(R.string.auth_bridge_searching)) }
                item { ActionChip(stringResource(R.string.auth_cancel), onBack, primary = false) }
            }
            BridgeStep.WaitingPhone -> {
                item { Spinner() }
                item { Title(stringResource(R.string.auth_bridge_waiting_title)) }
                item { Body(stringResource(R.string.auth_bridge_waiting_text)) }
                item { ActionChip(stringResource(R.string.auth_cancel), onBack, primary = false) }
            }
            is BridgeStep.CompareEmojis -> {
                item { Title(stringResource(R.string.auth_bridge_compare_title)) }
                item { EmojiRow(step.emojis) }
                item { Body(stringResource(R.string.auth_bridge_compare_text)) }
                item { ActionChip(stringResource(R.string.auth_cancel), onBack, primary = false) }
            }
            BridgeStep.LoggingIn -> {
                item { Spinner() }
                item { Body(stringResource(R.string.auth_bridge_logging_in)) }
            }
            is BridgeStep.Failed -> {
                item { Icon(Icons.Rounded.Warning, null, Modifier.size(28.dp), tint = MaterialTheme.colors.error) }
                item { Body(step.message) }
                if (step.suggestQr) item { ActionChip(stringResource(R.string.auth_chip_qr), onQr, icon = { QrGlyph() }) }
                item { ActionChip(stringResource(R.string.auth_retry), onRetry, primary = false, icon = { RefreshIcon() }) }
                item { ActionChip(stringResource(R.string.auth_back), onBack, primary = false) }
            }
        }
    }
}

@Composable
private fun QrScreen(s: AuthUiState.Qr, conn: ConnectionState?, onBack: () -> Unit) {
    val link = s.link
    val error = s.error
    when {
        error != null -> {
            AuthList(centerIndex = 1) {
                item { Icon(Icons.Rounded.Warning, null, Modifier.size(28.dp), tint = MaterialTheme.colors.error) }
                item { Body(error) }
                item { ActionChip(stringResource(R.string.auth_back), onBack, primary = false) }
            }
        }
        link == null -> {
            Scaffold(timeText = { TimeText() }) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spinner()
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.auth_qr_generating), style = MaterialTheme.typography.body2)
                    ConnectionLine(conn)
                }
            }
        }
        else -> Scaffold(timeText = { TimeText() }) { QrView(link) }
    }
}

/** QR al ~70% dello schermo (il quadrato massimo inscritto nel cerchio è il 70,7%), didascalia sotto. */
@Composable
private fun QrView(link: String) {
    val cd = stringResource(R.string.auth_qr_cd)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = minOf(maxWidth, maxHeight) * 0.70f
        val pad = 5.dp
        val sizePx = with(LocalDensity.current) { (side - pad * 2).roundToPx() }
        val bitmap by produceState<ImageBitmap?>(initialValue = null, link, sizePx) {
            value = runCatching { QrCode.render(link, sizePx) }.getOrNull()
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(side)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .padding(pad)
                .semantics { contentDescription = cd },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    filterQuality = FilterQuality.None,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    indicatorColor = Color.Black,
                    trackColor = Color.LightGray,
                    strokeWidth = 3.dp,
                )
            }
        }
        Text(
            stringResource(R.string.auth_qr_caption),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.62f)
                .padding(bottom = 6.dp),
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PasswordScreen(s: AuthUiState.Password, onSubmit: (String) -> Unit, onRestart: () -> Unit) {
    val inputLabel = stringResource(R.string.auth_password_title)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val typed = result.data
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(KEY_PASSWORD)
            ?.toString()
        if (!typed.isNullOrEmpty()) onSubmit(typed)
    }
    AuthList(centerIndex = 1) {
        item { Icon(Icons.Rounded.Lock, null, Modifier.size(28.dp), tint = MaterialTheme.colors.primary) }
        item { Title(stringResource(R.string.auth_password_title)) }
        item { Body(stringResource(R.string.auth_password_text), style = MaterialTheme.typography.caption2) }
        if (s.hint.isNotBlank()) item { Caption(stringResource(R.string.auth_password_hint, s.hint)) }
        s.error?.let { item { Body(it, color = MaterialTheme.colors.error) } }
        item {
            if (s.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(size = 20.dp)
                    Text(stringResource(R.string.auth_password_checking), style = MaterialTheme.typography.body2)
                }
            } else {
                ActionChip(
                    stringResource(R.string.auth_password_enter),
                    onClick = { launcher.launch(passwordInputIntent(inputLabel)) },
                    icon = { Icon(Icons.Rounded.Lock, null, Modifier.size(ChipDefaults.IconSize)) },
                )
            }
        }
        item { ActionChip(stringResource(R.string.auth_restart), onRestart, primary = false, enabled = !s.busy) }
    }
}

@Composable
private fun FinishOnPhoneScreen(s: AuthUiState.FinishOnPhone, onRetry: () -> Unit, onRestart: () -> Unit) {
    AuthList(centerIndex = 1) {
        item { PhoneIcon(size = 28.dp, tint = MaterialTheme.colors.primary) }
        item { Title(stringResource(R.string.auth_phone_step_title)) }
        item { Body(stringResource(R.string.auth_phone_step_text), style = MaterialTheme.typography.caption2) }
        s.error?.let { item { Body(it, color = MaterialTheme.colors.error) } }
        if (s.busy) {
            item { Spinner() }
        } else {
            item { ActionChip(stringResource(R.string.auth_retry), onRetry, icon = { RefreshIcon() }) }
            item { ActionChip(stringResource(R.string.auth_restart), onRestart, primary = false) }
        }
    }
}

// ---- mattoni -------------------------------------------------------------------------------------

/**
 * Lista standard Wear: TimeText, vignetta, indicatore di posizione, padding sicuro per schermi tondi.
 * [centerIndex] = item centrato all'apertura (titolo/azione principale), gli item precedenti stanno sopra.
 */
@Composable
private fun AuthList(centerIndex: Int = 1, content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = centerIndex)
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 28.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            autoCentering = AutoCenteringParams(itemIndex = centerIndex),
            content = content,
        )
    }
}

/** Swipe da sinistra = indietro (standard Wear), con la schermata di partenza come sfondo. */
@Composable
private fun SwipeBack(onBack: () -> Unit, background: @Composable () -> Unit, content: @Composable () -> Unit) {
    SwipeToDismissBox(onDismissed = onBack) { isBackground ->
        if (isBackground) background() else content()
    }
}

/** Tiene lo schermo acceso mentre si aspetta il telefono o si mostra il QR (scade in ~30 s). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun ActionChip(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        icon = icon?.let { ic -> { ic() } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
}

@Composable
private fun Body(
    text: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.body2,
    color: Color = MaterialTheme.colors.onBackground,
) {
    Text(text, style = style, color = color, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun Spinner(size: androidx.compose.ui.unit.Dp = 32.dp) {
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        indicatorColor = MaterialTheme.colors.primary,
        trackColor = MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
        strokeWidth = 3.dp,
    )
}

@Composable
private fun ConnectionLine(conn: ConnectionState?) {
    connectionText(conn)?.let {
        Spacer(Modifier.height(6.dp))
        Caption(it)
    }
}

@Composable
private fun connectionText(conn: ConnectionState?): String? = when (conn) {
    is ConnectionStateWaitingForNetwork -> stringResource(R.string.auth_conn_waiting_network)
    is ConnectionStateConnecting, is ConnectionStateConnectingToProxy -> stringResource(R.string.auth_conn_connecting)
    is ConnectionStateUpdating -> stringResource(R.string.auth_conn_updating)
    else -> null
}

/** Le 4 emoji SAS, grandi, da confrontare con il foglio sul telefono. */
@Composable
private fun EmojiRow(emojis: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        emojis.forEach { e ->
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colors.primary.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(e, fontSize = 24.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun PhoneIcon(size: androidx.compose.ui.unit.Dp = ChipDefaults.IconSize, tint: Color = LocalContentColor.current) {
    Icon(Icons.Rounded.Phone, null, Modifier.size(size), tint = tint)
}

@Composable
private fun RefreshIcon() {
    Icon(Icons.Rounded.Refresh, null, Modifier.size(ChipDefaults.IconSize))
}

/** Glifo QR (tre pattern di finder + qualche modulo) per il chip "Codice QR": nessuna icona QR in icons-core. */
@Composable
private fun QrGlyph() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(ChipDefaults.IconSize)) {
        val u = size.minDimension / 7f
        val stroke = u * 0.7f
        fun finder(x: Float, y: Float) {
            drawRect(color, Offset(x + stroke / 2, y + stroke / 2), Size(3 * u - stroke, 3 * u - stroke), style = Stroke(stroke))
            drawRect(color, Offset(x + u, y + u), Size(u, u))
        }
        finder(0f, 0f)
        finder(4 * u, 0f)
        finder(0f, 4 * u)
        for ((x, y) in listOf(4 to 4, 6 to 4, 5 to 5, 4 to 6, 6 to 6)) {
            drawRect(color, Offset(x * u, y * u), Size(u, u))
        }
    }
}

// ---- input password (RemoteInput di sistema) -----------------------------------------------------

private const val KEY_PASSWORD = "password"

/*
 * Stesse costanti di androidx.wear.input.RemoteInputIntentHelper (artefatto androidx.wear:wear-input,
 * non presente tra le dipendenze del modulo): apre l'attività di sistema di inserimento testo
 * (tastiera/dettatura) di Wear OS e restituisce il testo via RemoteInput.
 */
private const val ACTION_REMOTE_INPUT = "android.support.wearable.input.action.REMOTE_INPUT"
private const val EXTRA_REMOTE_INPUTS = "android.support.wearable.input.extra.REMOTE_INPUTS"
private const val EXTRA_TITLE = "android.support.wearable.input.extra.TITLE"

private fun passwordInputIntent(label: String): Intent {
    val remoteInput = RemoteInput.Builder(KEY_PASSWORD)
        .setLabel(label)
        .setAllowFreeFormInput(true)
        .build()
    return Intent(ACTION_REMOTE_INPUT)
        .putExtra(EXTRA_REMOTE_INPUTS, arrayOf(remoteInput))
        .putExtra(EXTRA_TITLE, label)
}

