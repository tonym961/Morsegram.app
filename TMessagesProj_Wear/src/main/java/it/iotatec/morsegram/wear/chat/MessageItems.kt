package it.iotatec.morsegram.wear.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.g000sha256.tdl.dto.File as TdFile
import dev.g000sha256.tdl.dto.MessageAnimatedEmoji
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageBasicGroupChatCreate
import dev.g000sha256.tdl.dto.MessageCall
import dev.g000sha256.tdl.dto.MessageChatAddMembers
import dev.g000sha256.tdl.dto.MessageChatBoost
import dev.g000sha256.tdl.dto.MessageChatChangePhoto
import dev.g000sha256.tdl.dto.MessageChatChangeTitle
import dev.g000sha256.tdl.dto.MessageChatDeleteMember
import dev.g000sha256.tdl.dto.MessageChatDeletePhoto
import dev.g000sha256.tdl.dto.MessageChatJoinByLink
import dev.g000sha256.tdl.dto.MessageChatJoinByRequest
import dev.g000sha256.tdl.dto.MessageChatSetBackground
import dev.g000sha256.tdl.dto.MessageChatSetMessageAutoDeleteTime
import dev.g000sha256.tdl.dto.MessageChatSetTheme
import dev.g000sha256.tdl.dto.MessageChatUpgradeFrom
import dev.g000sha256.tdl.dto.MessageChatUpgradeTo
import dev.g000sha256.tdl.dto.MessageContact
import dev.g000sha256.tdl.dto.MessageContactRegistered
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.MessageCustomServiceAction
import dev.g000sha256.tdl.dto.MessageDice
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageExpiredPhoto
import dev.g000sha256.tdl.dto.MessageExpiredVideo
import dev.g000sha256.tdl.dto.MessageExpiredVideoNote
import dev.g000sha256.tdl.dto.MessageExpiredVoiceNote
import dev.g000sha256.tdl.dto.MessageForumTopicCreated
import dev.g000sha256.tdl.dto.MessageForumTopicEdited
import dev.g000sha256.tdl.dto.MessageGame
import dev.g000sha256.tdl.dto.MessageGift
import dev.g000sha256.tdl.dto.MessageGiftedPremium
import dev.g000sha256.tdl.dto.MessageInvoice
import dev.g000sha256.tdl.dto.MessageLiveLocation
import dev.g000sha256.tdl.dto.MessageLocation
import dev.g000sha256.tdl.dto.MessagePaidMedia
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessagePinMessage
import dev.g000sha256.tdl.dto.MessagePoll
import dev.g000sha256.tdl.dto.MessagePremiumGiftCode
import dev.g000sha256.tdl.dto.MessageScreenshotTaken
import dev.g000sha256.tdl.dto.MessageSenderChat
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.MessageSendingStateFailed
import dev.g000sha256.tdl.dto.MessageSendingStatePending
import dev.g000sha256.tdl.dto.MessageSticker
import dev.g000sha256.tdl.dto.MessageStory
import dev.g000sha256.tdl.dto.MessageSupergroupChatCreate
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageVenue
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVideoChatEnded
import dev.g000sha256.tdl.dto.MessageVideoChatScheduled
import dev.g000sha256.tdl.dto.MessageVideoChatStarted
import dev.g000sha256.tdl.dto.MessageVideoNote
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.Sticker
import dev.g000sha256.tdl.dto.StickerFormatWebp
import dev.g000sha256.tdl.dto.ThumbnailFormatJpeg
import dev.g000sha256.tdl.dto.ThumbnailFormatPng
import dev.g000sha256.tdl.dto.ThumbnailFormatWebp
import it.iotatec.morsegram.wear.R
import it.iotatec.morsegram.wear.ui.theme.Amber
import it.iotatec.morsegram.wear.ui.theme.AmberDark
import it.iotatec.morsegram.wear.ui.theme.Muted
import it.iotatec.morsegram.wear.ui.theme.Surface

/**
 * Rendering di un messaggio nella lista chat: bolle (testo, foto, sticker, vocale, media
 * compatti), messaggi di servizio centrati, "non supportato" per tutto il resto.
 * Nessun contenuto fa crashare: ogni `when` ha il ramo di fallback.
 */

private val BubbleShape = RoundedCornerShape(14.dp)
private val OutgoingBubble = AmberDark.copy(alpha = 0.38f)
private val ReadTick = Amber

/** Ciò che la riga ha bisogno dal ViewModel, raggruppato per non passare dieci parametri. */
@Stable
class MessageRowActions(
    val ensureDownloaded: (TdFile) -> Unit,
    val resolveSender: (ChatViewModel.ChatMessage) -> Unit,
    val playVoice: (ChatViewModel.ChatMessage) -> Unit,
)

/**
 * Una riga della chat. [files] è lo stato aggiornato dei download (per id): passarlo come
 * parametro fa ricomporre la riga quando un file avanza o si completa. [playerState] è lo stato
 * del player solo se riguarda questo messaggio (altrimenti [VoicePlayer.Idle]).
 */
@Composable
fun MessageRow(
    item: ChatViewModel.ChatMessage,
    isGroup: Boolean,
    senderName: String?,
    lastReadOutbox: Long,
    files: Map<Int, TdFile>,
    playerState: VoicePlayer.State,
    actions: MessageRowActions,
) {
    val serviceText = serviceText(item.content)
    if (serviceText != null) {
        CenteredNote(serviceText)
        return
    }
    val content = item.content
    val outgoing = item.message.isOutgoing
    val showSender = isGroup && !outgoing && item.message.senderId.let { s ->
        (s is MessageSenderUser) || (s is MessageSenderChat && s.chatId != item.message.chatId)
    }
    if (showSender) LaunchedEffect(item.id) { actions.resolveSender(item) }

    // Contenuto a visualizzazione singola / autodistruggente: sull'orologio non lo scarichiamo né
    // lo mostriamo (servirebbe openMessageContent per far partire il timer e avvisare il mittente;
    // termini dell'API: niente aggiramenti). Si rimanda al telefono.
    if (item.isViewOnce()) {
        Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            ViewOnceContent(kindRes = viewOnceKindRes(content), hint = !outgoing)
        }
        return
    }

    when (content) {
        is MessageText -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            BodyText(content.text.text)
        }
        is MessageAnimatedEmoji -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            Text(content.emoji, fontSize = 30.sp, lineHeight = 36.sp)
        }
        is MessagePhoto -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            PhotoContent(content, files, actions)
            Caption(content.caption?.text)
        }
        is MessageSticker -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox, transparent = true) {
            StickerContent(content.sticker, files, actions)
        }
        is MessageVoiceNote -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            VoiceContent(item, content, files, playerState, actions)
            Caption(content.caption?.text)
        }
        is MessageVideo -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            val v = content.video
            MediaRow("▶", stringResource(R.string.chat_video), listOf(formatDuration(v.duration), formatSize(v.video.knownSize())))
            Caption(content.caption?.text)
        }
        is MessageVideoNote -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            val v = content.videoNote
            MediaRow("◉", stringResource(R.string.chat_video_note), listOf(formatDuration(v.duration), formatSize(v.video.knownSize())))
        }
        is MessageAnimation -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            val a = content.animation
            MediaRow("▶", stringResource(R.string.chat_gif), listOf(formatDuration(a.duration), formatSize(a.animation.knownSize())))
            Caption(content.caption?.text)
        }
        is MessageDocument -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            val d = content.document
            MediaRow("▤", d.fileName.ifBlank { stringResource(R.string.chat_document) }, listOf(formatSize(d.document.knownSize())))
            Caption(content.caption?.text)
        }
        is MessageAudio -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            val a = content.audio
            val name = listOf(a.performer, a.title).filter { it.isNotBlank() }.joinToString(" – ")
                .ifBlank { a.fileName.ifBlank { stringResource(R.string.chat_audio) } }
            MediaRow("♪", name, listOf(formatDuration(a.duration), formatSize(a.audio.knownSize())))
            Caption(content.caption?.text)
        }
        is MessageContact -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            val c = content.contact
            val name = listOf(c.firstName, c.lastName).filter { it.isNotBlank() }.joinToString(" ")
            MediaRow("☎", stringResource(R.string.chat_contact), listOf(name, c.phoneNumber))
        }
        is MessageLocation -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow("⌖", stringResource(R.string.chat_location), listOf(String.format(java.util.Locale.ROOT, "%.4f, %.4f", content.location.latitude, content.location.longitude)))
        }
        is MessageLiveLocation -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow("⌖", stringResource(R.string.chat_live_location), emptyList())
        }
        is MessageVenue -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow("⌖", content.venue.title, listOf(content.venue.address))
        }
        is MessagePoll -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow("▥", stringResource(R.string.chat_poll), listOf(content.poll.question.text))
        }
        is MessageDice -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow(content.emoji, stringResource(R.string.chat_dice), listOf(content.value.toString()))
        }
        is MessageCall -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            val label = stringResource(if (content.isVideo) R.string.chat_video_call else R.string.chat_call)
            MediaRow("✆", label, listOf(if (content.duration > 0) formatDuration(content.duration) else ""))
        }
        is MessageStory -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow("◎", stringResource(R.string.chat_story), emptyList())
        }
        is MessageGame -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow("🎮", stringResource(R.string.chat_game), listOf(content.game.title))
        }
        is MessageInvoice -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow("▤", stringResource(R.string.chat_invoice), listOf(content.productInfo.title))
        }
        is MessagePaidMedia -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow("★", stringResource(R.string.chat_paid_media), emptyList())
            Caption(content.caption?.text)
        }
        is MessageGift, is MessageGiftedPremium, is MessagePremiumGiftCode -> Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
            MediaRow("🎁", stringResource(R.string.chat_gift), emptyList())
        }
        is MessageExpiredPhoto, is MessageExpiredVideo, is MessageExpiredVideoNote, is MessageExpiredVoiceNote ->
            Bubble(item, outgoing, showSender, senderName, lastReadOutbox) {
                BodyText(stringResource(R.string.chat_expired), muted = true)
            }
        else -> CenteredNote(stringResource(R.string.chat_unsupported))
    }
}

// ------------------------------------------------------------------ bolla

@Composable
private fun Bubble(
    item: ChatViewModel.ChatMessage,
    outgoing: Boolean,
    showSender: Boolean,
    senderName: String?,
    lastReadOutbox: Long,
    transparent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        val bg = when {
            transparent -> Color.Transparent
            outgoing -> OutgoingBubble
            else -> Surface
        }
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(BubbleShape)
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            if (showSender) {
                Text(
                    text = senderName ?: "…",
                    color = Amber,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            content()
            MetaRow(item, outgoing, lastReadOutbox)
        }
    }
}

@Composable
private fun MetaRow(item: ChatViewModel.ChatMessage, outgoing: Boolean, lastReadOutbox: Long) {
    val m = item.message
    val edited = if (m.editDate > 0) " · " + stringResource(R.string.chat_edited) else ""
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        Text(
            text = formatTime(m.date) + edited,
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            maxLines = 1,
        )
        if (outgoing) {
            val (tick, color) = when (m.sendingState) {
                is MessageSendingStatePending -> "◌" to Muted
                is MessageSendingStateFailed -> "!" to MaterialTheme.colors.error
                else -> if (m.id <= lastReadOutbox) "✓✓" to ReadTick else "✓" to Muted
            }
            Spacer(Modifier.size(4.dp))
            Text(text = tick, color = color, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BodyText(text: String, muted: Boolean = false) {
    Text(
        text = text,
        color = if (muted) Muted else MaterialTheme.colors.onSurface,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )
}

@Composable
private fun Caption(text: String?) {
    if (!text.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        BodyText(text)
    }
}

/** Testo piccolo centrato (messaggi di servizio e "non supportato"). */
@Composable
fun CenteredNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Riga compatta per i media non riproducibili: icona, nome e dettagli (durata, dimensione). */
@Composable
private fun MediaRow(icon: String, name: String, details: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Amber.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = Amber, fontSize = 16.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.size(8.dp))
        Column {
            Text(name, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, color = MaterialTheme.colors.onSurface)
            val meta = details.filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, lineHeight = 14.sp, color = Muted, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ visualizzazione singola

/**
 * Vero per i media "a visualizzazione singola" o con timer di autodistruzione: foto, video,
 * videomessaggi e GIF segreti (`isSecret`) e qualunque messaggio con `selfDestructType`
 * (es. vocali view-once, che il contenuto non marca).
 */
private fun ChatViewModel.ChatMessage.isViewOnce(): Boolean {
    val timed = message.selfDestructType != null
    return when (val c = content) {
        is MessagePhoto -> c.isSecret || timed
        is MessageVideo -> c.isSecret || timed
        is MessageVideoNote -> c.isSecret || timed
        is MessageAnimation -> c.isSecret || timed
        is MessageVoiceNote -> timed
        else -> false // già scaduto (MessageExpired*) o tipo senza autodistruzione
    }
}

/** Etichetta del tipo di media view-once (null se non ne abbiamo una). */
private fun viewOnceKindRes(content: MessageContent): Int? = when (content) {
    is MessagePhoto -> R.string.chat_photo
    is MessageVideo -> R.string.chat_video
    is MessageVideoNote -> R.string.chat_video_note
    is MessageAnimation -> R.string.chat_gif
    is MessageVoiceNote -> R.string.chat_voice_note
    else -> null
}

@Composable
private fun ViewOnceContent(kindRes: Int?, hint: Boolean) {
    val kind = kindRes?.let { stringResource(it) }
    MediaRow("①", stringResource(R.string.chat_view_once), listOfNotNull(kind))
    if (hint) {
        Spacer(Modifier.height(4.dp))
        BodyText(stringResource(R.string.chat_view_once_hint), muted = true)
    }
}

// ------------------------------------------------------------------ foto e sticker

/** Stato aggiornato del file se lo seguiamo, altrimenti quello incluso nel messaggio. */
private fun TdFile.resolve(files: Map<Int, TdFile>): TdFile = files[id] ?: this

@Composable
private fun PhotoContent(content: MessagePhoto, files: Map<Int, TdFile>, actions: MessageRowActions) {
    val targetPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp * 0.8f).dp.roundToPx() }
    val size = remember(content) { pickPhotoSize(content.photo, targetPx) }
    val file = size?.photo?.resolve(files)
    LaunchedEffect(size?.photo?.id) { size?.photo?.let(actions.ensureDownloaded) }
    val path = file?.takeIf { it.local.isDownloadingCompleted }?.local?.path
    val bitmap by rememberLocalBitmap(path, targetPx)
    val mini by rememberMinithumbnail(content.photo.minithumbnail?.data)
    val ratio = size?.let { if (it.height > 0) it.width.toFloat() / it.height else 1f } ?: 1f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.6f, 1.8f))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap ?: mini
        if (shown != null) {
            Image(bitmap = shown, contentDescription = stringResource(R.string.chat_photo), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (bitmap == null) DownloadIndicator(file)
    }
}

@Composable
private fun StickerContent(sticker: Sticker, files: Map<Int, TdFile>, actions: MessageRowActions) {
    // WEBP statico: lo sticker stesso; TGS/WEBM animati: la miniatura statica se c'è, altrimenti placeholder
    val target: TdFile? = remember(sticker) {
        if (sticker.format is StickerFormatWebp) sticker.sticker
        else sticker.thumbnail?.takeIf { t ->
            t.format is ThumbnailFormatWebp || t.format is ThumbnailFormatJpeg || t.format is ThumbnailFormatPng
        }?.file
    }
    val file = target?.resolve(files)
    LaunchedEffect(target?.id) { target?.let(actions.ensureDownloaded) }
    val path = file?.takeIf { it.local.isDownloadingCompleted }?.local?.path
    val sizePx = with(LocalDensity.current) { 96.dp.roundToPx() }
    val bitmap by rememberLocalBitmap(path, sizePx)
    val bmp = bitmap
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = sticker.emoji, modifier = Modifier.size(96.dp), contentScale = ContentScale.Fit)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(sticker.emoji.ifBlank { "🙂" }, fontSize = 26.sp, lineHeight = 30.sp)
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.chat_sticker), color = Muted, fontSize = 12.sp, lineHeight = 14.sp)
            if (target != null && file != null && !file.local.isDownloadingCompleted) {
                Spacer(Modifier.size(6.dp))
                DownloadIndicator(file, small = true)
            }
        }
    }
}

/** Anello di progresso del download (indeterminato finché TDLib non dà la dimensione). */
@Composable
private fun DownloadIndicator(file: TdFile?, small: Boolean = false) {
    val size = if (small) 16.dp else 28.dp
    val fraction = file?.downloadFraction()
    if (fraction != null) {
        CircularProgressIndicator(progress = fraction, modifier = Modifier.size(size), indicatorColor = Amber, trackColor = Muted.copy(alpha = 0.3f), strokeWidth = 2.dp)
    } else {
        CircularProgressIndicator(modifier = Modifier.size(size), indicatorColor = Amber, trackColor = Muted.copy(alpha = 0.3f), strokeWidth = 2.dp)
    }
}

// ------------------------------------------------------------------ vocali

@Composable
private fun VoiceContent(
    item: ChatViewModel.ChatMessage,
    content: MessageVoiceNote,
    files: Map<Int, TdFile>,
    playerState: VoicePlayer.State,
    actions: MessageRowActions,
) {
    val voice = content.voiceNote
    val file = voice.voice.resolve(files)
    val isCurrent = playerState.messageId == item.id
    val downloading = file.local.isDownloadingActive && !file.local.isDownloadingCompleted
    val fraction = if (isCurrent) playerState.fraction else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { actions.playVoice(item) },
            modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            colors = ButtonDefaults.primaryButtonColors(),
        ) {
            when {
                downloading || (isCurrent && playerState.isPreparing) ->
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), indicatorColor = MaterialTheme.colors.onPrimary, trackColor = Color.Transparent, strokeWidth = 2.dp)
                isCurrent && playerState.isPlaying ->
                    Icon(ChatIcons.Pause, contentDescription = stringResource(R.string.chat_pause), modifier = Modifier.size(24.dp))
                else ->
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.chat_play), modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.widthIn(min = 72.dp)) {
            val label = if (isCurrent && (playerState.isPlaying || playerState.positionMs > 0)) {
                formatDurationMs(playerState.positionMs.toLong()) + " / " + formatDuration(voice.duration)
            } else {
                formatDuration(voice.duration)
            }
            Text(label, fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colors.onSurface, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Muted.copy(alpha = 0.35f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(Amber),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ messaggi di servizio

/** Testo del messaggio di servizio, o null se il contenuto non è un messaggio di servizio. */
@Composable
private fun serviceText(content: MessageContent): String? = when (content) {
    is MessageChatAddMembers -> stringResource(R.string.chat_service_add_members)
    is MessageChatJoinByLink -> stringResource(R.string.chat_service_join_link)
    is MessageChatJoinByRequest -> stringResource(R.string.chat_service_join_request)
    is MessageChatDeleteMember -> stringResource(R.string.chat_service_delete_member)
    is MessageChatChangeTitle -> stringResource(R.string.chat_service_change_title, content.title)
    is MessageChatChangePhoto -> stringResource(R.string.chat_service_change_photo)
    is MessageChatDeletePhoto -> stringResource(R.string.chat_service_delete_photo)
    is MessageBasicGroupChatCreate -> stringResource(R.string.chat_service_group_created)
    is MessageSupergroupChatCreate -> stringResource(R.string.chat_service_channel_created)
    is MessagePinMessage -> stringResource(R.string.chat_service_pinned)
    is MessageChatUpgradeTo, is MessageChatUpgradeFrom -> stringResource(R.string.chat_service_upgraded)
    is MessageChatSetTheme -> stringResource(R.string.chat_service_theme)
    is MessageChatSetBackground -> stringResource(R.string.chat_service_background)
    is MessageChatSetMessageAutoDeleteTime -> stringResource(R.string.chat_service_auto_delete)
    is MessageVideoChatStarted -> stringResource(R.string.chat_service_video_chat_started)
    is MessageVideoChatEnded -> stringResource(R.string.chat_service_video_chat_ended)
    is MessageVideoChatScheduled -> stringResource(R.string.chat_service_video_chat_scheduled)
    is MessageContactRegistered -> stringResource(R.string.chat_service_contact_registered)
    is MessageScreenshotTaken -> stringResource(R.string.chat_service_screenshot)
    is MessageForumTopicCreated, is MessageForumTopicEdited -> stringResource(R.string.chat_service_topic)
    is MessageChatBoost -> stringResource(R.string.chat_service_boost)
    is MessageCustomServiceAction -> content.text.ifBlank { stringResource(R.string.chat_service_generic) }
    else -> null
}
