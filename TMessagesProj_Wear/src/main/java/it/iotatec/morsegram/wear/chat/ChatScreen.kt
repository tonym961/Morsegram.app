package it.iotatec.morsegram.wear.chat

import android.Manifest
import android.app.Activity
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import dev.g000sha256.tdl.dto.MessageSenderChat
import dev.g000sha256.tdl.dto.MessageSenderUser
import it.iotatec.morsegram.wear.R
import it.iotatec.morsegram.wear.ui.theme.Amber
import it.iotatec.morsegram.wear.ui.theme.Muted
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Schermata di una chat: storico in [ScalingLazyColumn] (il più recente in fondo), composer in
 * coda con "Rispondi" (RemoteInput di sistema: dettatura, tastiera, emoji) e "Vocale"
 * (registrazione OGG/Opus). Le ricevute di lettura partono per i messaggi davvero visibili.
 *
 * Contratto di navigazione: `ChatScreen(chatId, onBack)`; [onBack] non serve perché lo
 * swipe-to-dismiss dell'host chiude la schermata, ma resta disponibile.
 */
@Composable
fun ChatScreen(chatId: Long, onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat-$chatId") { ChatViewModel(chatId) }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val isGroup by vm.isGroup.collectAsStateWithLifecycle()
    val senderNames by vm.senderNames.collectAsStateWithLifecycle()
    val lastReadOutbox by vm.lastReadOutbox.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadingOlder by vm.loadingOlder.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val playerState by vm.player.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val actions = remember(vm) {
        MessageRowActions(
            ensureDownloaded = vm::ensureDownloaded,
            resolveSender = { item -> vm.resolveSender(item.message.senderId) },
            playVoice = vm::playVoice,
        )
    }
    // errori -> toast brevi
    LaunchedEffect(vm) {
        vm.errors.collect { e ->
            Toast.makeText(context, context.getString(e.messageRes()), Toast.LENGTH_SHORT).show()
        }
    }

    // mentre si registra lo schermo non deve spegnersi per timeout (sull'orologio bastano 15-30 s):
    // la pausa dell'activity chiuderebbe la registrazione
    val view = LocalView.current
    DisposableEffect(view, recording.active) {
        view.keepScreenOn = recording.active
        onDispose { view.keepScreenOn = false }
    }

    // la schermata esce (altra app, polso giù, schermo spento): fermiamo l'audio; un vocale
    // abbastanza lungo viene inviato, altrimenti annullato con avviso (vedi ChatViewModel.onScreenPaused)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        vm.onScreenPaused()
    }

    // indici della lista: 0 = header, 1..n = messaggi, n+1 = composer (ultimo item)

    // apertura: appena arriva lo storico scendiamo in fondo
    var scrolledInitially by remember { mutableStateOf(false) }
    LaunchedEffect(loading, messages.isEmpty()) {
        if (!loading && !scrolledInitially) {
            scrolledInitially = true
            listState.scrollToItem(messages.size + 1)
        }
    }
    // nostro messaggio inviato -> in fondo (sull'ultimo item che la lista conosce in questo momento)
    LaunchedEffect(vm) {
        vm.scrollToBottom.collect {
            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.animateScrollToItem(last)
        }
    }
    // nuovo messaggio in coda: lo seguiamo solo se l'ultimo messaggio precedente era già visibile
    // (chiave = id dell'ultimo messaggio: una pagina di storico aggiunta in cima non fa saltare in fondo)
    var previousCount by remember { mutableIntStateOf(-1) }
    val lastMessageId = messages.lastOrNull()?.id ?: 0L
    LaunchedEffect(lastMessageId) {
        val prev = previousCount
        previousCount = messages.size
        if (prev > 0 && scrolledInitially && messages.size > prev) {
            val wasAtBottom = listState.layoutInfo.visibleItemsInfo.any { it.index >= prev }
            if (wasAtBottom) listState.animateScrollToItem(messages.size + 1)
        }
    }
    // ricevute di lettura: i messaggi effettivamente visibili (chiave = id del messaggio)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
            .distinctUntilChanged()
            .collect { ids -> vm.onMessagesVisible(ids) }
    }
    // paginazione verso l'alto: quando l'header (indice 0) torna visibile
    LaunchedEffect(listState) {
        snapshotFlow { scrolledInitially && listState.layoutInfo.visibleItemsInfo.any { it.index == 0 } }
            .distinctUntilChanged()
            .collect { atTop -> if (atTop) vm.loadOlder() }
    }

    // ---- "Rispondi": RemoteInput di sistema (stesso intent di androidx.wear.input.RemoteInputIntentHelper)
    val replyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.let { RemoteInput.getResultsFromIntent(it) }
                ?.getCharSequence(KEY_REPLY)
                ?.toString()
                ?.trim()
            if (!text.isNullOrEmpty()) vm.sendText(text)
        }
    }
    val inputLabel = stringResource(R.string.chat_input_label)
    val inputUnavailable = stringResource(R.string.chat_err_input)
    val launchReply = {
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel(inputLabel)
            .setAllowFreeFormInput(true)
            .build()
        val intent = Intent(ACTION_REMOTE_INPUT)
            .putExtra(EXTRA_REMOTE_INPUTS, arrayOf(remoteInput))
            .putExtra(EXTRA_TITLE, inputLabel)
        try {
            replyLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, inputUnavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- "Vocale": permesso microfono a runtime, poi registrazione
    val micDenied = stringResource(R.string.chat_err_mic_permission)
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else Toast.makeText(context, micDenied, Toast.LENGTH_SHORT).show()
    }
    val startVoice = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startRecording() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            anchorType = ScalingLazyListAnchorType.ItemCenter,
            autoCentering = AutoCenteringParams(itemIndex = 0),
        ) {
            item(key = KEY_HEADER) {
                ChatHeader(title = title, loading = loading, loadingOlder = loadingOlder, empty = messages.isEmpty())
            }
            items(messages, key = { it.id }) { item ->
                val senderKey = when (val s = item.message.senderId) {
                    is MessageSenderUser -> s.userId
                    is MessageSenderChat -> s.chatId
                    else -> 0L
                }
                MessageRow(
                    item = item,
                    isGroup = isGroup,
                    senderName = senderNames[senderKey],
                    lastReadOutbox = lastReadOutbox,
                    files = files,
                    playerState = if (playerState.messageId == item.id) playerState else VoicePlayer.Idle,
                    actions = actions,
                )
            }
            item(key = KEY_COMPOSER) {
                if (recording.active) {
                    RecordingComposer(
                        elapsedMs = recording.elapsedMs,
                        onSend = vm::stopRecordingAndSend,
                        onCancel = vm::cancelRecording,
                    )
                } else {
                    IdleComposer(onReply = launchReply, onVoice = startVoice)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun ChatHeader(title: String, loading: Boolean, loadingOlder: Boolean, empty: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colors.onBackground,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        when {
            loading || loadingOlder -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                indicatorColor = Amber,
                trackColor = Muted.copy(alpha = 0.3f),
                strokeWidth = 2.dp,
            )
            empty -> Text(
                text = stringResource(R.string.chat_no_messages),
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------ composer

@Composable
private fun IdleComposer(onReply: () -> Unit, onVoice: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip(
            label = { Text(stringResource(R.string.chat_reply), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = onReply,
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(ChatIcons.Keyboard, contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
            colors = ChipDefaults.primaryChipColors(),
        )
        Chip(
            label = { Text(stringResource(R.string.chat_voice), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = onVoice,
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(ChatIcons.Mic, contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
}

@Composable
private fun RecordingComposer(elapsedMs: Long, onSend: () -> Unit, onCancel: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "rec")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "recAlpha",
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .alpha(alpha)
                    .background(Color(0xFFE53935), CircleShape),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.chat_recording) + " " + formatDurationMs(elapsedMs),
                color = MaterialTheme.colors.onBackground,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                onClick = onCancel,
                modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                colors = ButtonDefaults.secondaryButtonColors(),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_cancel), modifier = Modifier.size(24.dp))
            }
            Button(
                onClick = onSend,
                modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                colors = ButtonDefaults.primaryButtonColors(),
            ) {
                Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.chat_send), modifier = Modifier.size(24.dp))
            }
        }
    }
}

private fun ChatViewModel.UiError.messageRes(): Int = when (this) {
    ChatViewModel.UiError.SEND_FAILED -> R.string.chat_err_send
    ChatViewModel.UiError.RECORD_UNSUPPORTED -> R.string.chat_err_record_unsupported
    ChatViewModel.UiError.RECORD_FAILED -> R.string.chat_err_record_failed
    ChatViewModel.UiError.RECORD_CANCELLED -> R.string.chat_err_record_cancelled
    ChatViewModel.UiError.TOO_SHORT -> R.string.chat_err_too_short
    ChatViewModel.UiError.PLAY_FAILED -> R.string.chat_err_play
}

private const val KEY_HEADER = "header"
private const val KEY_COMPOSER = "composer"
private const val KEY_REPLY = "reply"

// Contratto dell'activity di input di Wear OS (gli stessi valori di androidx.wear.input.RemoteInputIntentHelper,
// che vive in androidx.wear:wear-input e non è tra le dipendenze del modulo).
private const val ACTION_REMOTE_INPUT = "android.support.wearable.input.action.REMOTE_INPUT"
private const val EXTRA_REMOTE_INPUTS = "android.support.wearable.input.extra.REMOTE_INPUTS"
private const val EXTRA_TITLE = "android.support.wearable.input.extra.TITLE"
