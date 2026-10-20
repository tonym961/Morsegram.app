package it.iotatec.morsegram.wear.auth

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    val airplane by vm.airplane.collectAsStateWithLifecycle()

    when (val s = state) {
        AuthUiState.Loading -> LoadingScreen(conn)
        AuthUiState.Intro -> IntroScreen(conn, airplane, onBridge = vm::startBridge, onQr = vm::startQr, onAirplaneOff = vm::disableAirplane)
        is AuthUiState.Bridge -> SwipeBack(onBack = vm::back, background = { IntroScreen(conn, airplane, {}, {}, {}) }) {
            KeepScreenOn()
            BridgeScreen(s.step, onRetry = vm::startBridge, onQr = vm::startQr, onBack = vm::back)
        }
        is AuthUiState.Qr -> SwipeBack(onBack = vm::back, background = { IntroScreen(conn, airplane, {}, {}, {}) }) {
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

/**
 * Prima schermata. Ancorata al chip principale (item 2) ma spostato un po' sotto il centro: con le
 * 3 righe italiane dell'avviso il titolo finirebbe addosso al TimeText, così invece respira e i
 * due chip restano entrambi in vista su 454 px.
 */
@Composable
private fun IntroScreen(
    conn: ConnectionState?,
    airplane: Boolean,
    onBridge: () -> Unit,
    onQr: () -> Unit,
    onAirplaneOff: () -> Unit,
) {
    val connText = connectionText(conn)
    AuthList(centerIndex = 2, centerOffset = INTRO_CENTER_OFFSET) {
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
        if (airplane) {
            // modalità aereo in-app rimasta attiva senza sessione: spiega l'"in attesa di rete" e offre di spegnerla
            item { Caption(stringResource(R.string.auth_airplane_hint)) }
            item {
                ActionChip(
                    stringResource(R.string.auth_airplane_off),
                    onAirplaneOff,
                    primary = false,
                    icon = { Text("✈", style = MaterialTheme.typography.title3) },
                )
            }
        } else if (connText != null) {
            item { Caption(connText) }
        }
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

/**
 * QR al 62% del lato (ben dentro il cerchio: il quadrato inscritto è il 70,7%, e serve aria per
 * il TimeText e la didascalia), centrato come primo item di una ScalingLazyColumn; la didascalia
 * (2 righe) sta sotto e si porta al centro con corona/swipe, senza mai finire tagliata sul bordo.
 * Il QR resta nero su bianco con quiet zone (card bianca + margine del codice), filtro nearest.
 */
@Composable
private fun QrView(link: String) {
    val cd = stringResource(R.string.auth_qr_cd)
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = minOf(maxWidth, maxHeight) * 0.62f
        val pad = 5.dp
        val sizePx = with(LocalDensity.current) { (side - pad * 2).roundToPx() }
        val bitmap by produceState<ImageBitmap?>(initialValue = null, link, sizePx) {
            value = runCatching { QrCode.render(link, sizePx) }.getOrNull()
        }
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            autoCentering = AutoCenteringParams(itemIndex = 0),
        ) {
            item {
                Box(
                    Modifier
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
            }
            item {
                Text(
                    stringResource(R.string.auth_qr_caption),
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .padding(top = 6.dp, bottom = 18.dp),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Password cloud (verifica in due passaggi) con un campo Compose in-app: niente attività
 * REMOTE_INPUT di sistema, che mostrerebbe la password in chiaro e la offrirebbe alla dettatura.
 * Il testo vive solo in memoria (`remember`, non `rememberSaveable`) e non viene registrato.
 */
@Composable
private fun PasswordScreen(s: AuthUiState.Password, onSubmit: (String) -> Unit, onRestart: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (password.isNotEmpty() && !s.busy) {
            keyboard?.hide()
            onSubmit(password)
            password = ""
        }
    }
    AuthList(centerIndex = 2) {
        item { Icon(Icons.Rounded.Lock, null, Modifier.size(28.dp), tint = MaterialTheme.colors.primary) }
        item { Title(stringResource(R.string.auth_password_title)) }
        item { PasswordField(password, onValueChange = { password = it }, enabled = !s.busy, onDone = submit) }
        item {
            if (s.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(size = 20.dp)
                    Text(stringResource(R.string.auth_password_checking), style = MaterialTheme.typography.body2)
                }
            } else {
                ActionChip(
                    stringResource(R.string.auth_password_confirm),
                    onClick = submit,
                    enabled = password.isNotEmpty(),
                    icon = { Icon(Icons.Rounded.Check, null, Modifier.size(ChipDefaults.IconSize)) },
                )
            }
        }
        if (s.hint.isNotBlank()) item { Caption(stringResource(R.string.auth_password_hint, s.hint)) }
        s.error?.let { item { Body(it, color = MaterialTheme.colors.error) } }
        item { Body(stringResource(R.string.auth_password_text), style = MaterialTheme.typography.caption2) }
        item { ActionChip(stringResource(R.string.auth_restart), onRestart, primary = false, enabled = !s.busy) }
    }
}

/**
 * Campo password su una "pillola" (sicura sul tondo: 82% della larghezza, angoli pieni), testo
 * mascherato, tastiera di sistema con tipo Password e tasto Fine. All'apertura prende il focus e
 * chiede la tastiera (il solo focus programmatico non la mostra).
 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val label = stringResource(R.string.auth_password_title)
    val textColor = MaterialTheme.colors.onSurface
    val hintColor = MaterialTheme.colors.onSurfaceVariant
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "")) },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.body1.copy(color = textColor, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(MaterialTheme.colors.primary),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .focusRequester(focusRequester)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colors.surface)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty()) {
                    Text(label, style = MaterialTheme.typography.body1, color = hintColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        },
    )
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
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

/** Di quanto il chip principale dell'intro sta sotto il centro (positivo = più in basso). */
private val INTRO_CENTER_OFFSET = 28.dp

/**
 * Lista standard Wear: TimeText, vignetta, indicatore di posizione, padding sicuro per schermi tondi.
 * [centerIndex] = item centrato all'apertura (titolo/azione principale), gli item precedenti stanno sopra;
 * [centerOffset] lo sposta rispetto al centro (positivo = verso il basso).
 */
@Composable
private fun AuthList(centerIndex: Int = 1, centerOffset: Dp = 0.dp, content: ScalingLazyListScope.() -> Unit) {
    // ScalingLazyListState.centerItemScrollOffset: positivo = ancora dell'item sopra la linea centrale
    val offsetPx = with(LocalDensity.current) { -centerOffset.roundToPx() }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = centerIndex, initialCenterItemScrollOffset = offsetPx)
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
            autoCentering = AutoCenteringParams(itemIndex = centerIndex, itemOffset = offsetPx),
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
