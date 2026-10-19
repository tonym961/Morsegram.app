package it.iotatec.morsegram.wear.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import dev.g000sha256.tdl.dto.ConnectionState
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.ConnectionStateUpdating
import dev.g000sha256.tdl.dto.ConnectionStateWaitingForNetwork
import it.iotatec.morsegram.wear.R
import it.iotatec.morsegram.wear.settings.AirplaneMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val RowShape = RoundedCornerShape(24.dp)

/**
 * Lista chat (lista principale TDLib). Header con nome app + chip impostazioni e banner di
 * connessione; righe con avatar, titolo, anteprima, orario, badge non letti, icone muto/fissata;
 * in coda "Carica altre" finché TDLib non dice 404 (auto-caricamento quando il footer è visibile).
 */
@Composable
fun ChatListScreen(onOpenChat: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val vm: ChatListViewModel = viewModel()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val allLoaded by vm.allLoaded.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val airplane by AirplaneMode.enabled.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val times = remember { ChatTimeFormatter(LocalDate.now(), ZoneId.systemDefault(), Locale.getDefault()) }

    val footerVisible by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }
    LaunchedEffect(footerVisible, isLoading, allLoaded, loadFailed, chats.size) {
        if (footerVisible && !isLoading && !allLoaded && !loadFailed && chats.isNotEmpty()) {
            delay(250)
            vm.loadMore()
        }
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "header") { Header(connection = connection, airplane = airplane, onOpenSettings = onOpenSettings) }
            if (chats.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        loading = isLoading || (!allLoaded && !loadFailed),
                        failed = loadFailed,
                        onRetry = vm::loadMore,
                    )
                }
            }
            items(count = chats.size, key = { chats[it].id }) { index ->
                ChatRow(item = chats[index], times = times, onOpen = onOpenChat)
            }
            if (chats.isNotEmpty() && !allLoaded) {
                item(key = "footer") { LoadMoreFooter(loading = isLoading, failed = loadFailed, onClick = vm::loadMore) }
            }
        }
    }
}

@Composable
private fun Header(connection: ConnectionState?, airplane: Boolean, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            CompactChip(
                onClick = onOpenSettings,
                colors = ChipDefaults.secondaryChipColors(),
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.chats_settings),
                        modifier = Modifier.size(ChipDefaults.SmallIconSize),
                    )
                },
            )
        }
        ConnectionBanner(connection, airplane)
    }
}

/** Banner di stato sotto il titolo; la modalità aereo in-app (vedi [AirplaneMode]) prevale su ogni stato TDLib. */
@Composable
private fun ConnectionBanner(connection: ConnectionState?, airplane: Boolean) {
    val label = when {
        airplane -> stringResource(R.string.chats_airplane)
        connection == null || connection is ConnectionStateReady -> return
        connection is ConnectionStateWaitingForNetwork -> stringResource(R.string.chats_waiting_network)
        connection is ConnectionStateUpdating -> stringResource(R.string.chats_updating)
        else -> stringResource(R.string.chats_connecting)
    }
    val spinner = !airplane && connection !is ConnectionStateWaitingForNetwork
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                indicatorColor = MaterialTheme.colors.primary,
                trackColor = Color.Transparent,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (airplane) MaterialTheme.colors.primary else MaterialTheme.colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyState(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            loading -> {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), indicatorColor = MaterialTheme.colors.primary)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.chats_loading), fontSize = 13.sp, color = MaterialTheme.colors.onSurfaceVariant)
            }
            failed -> {
                Text(stringResource(R.string.chats_load_error), fontSize = 13.sp, color = MaterialTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.size(8.dp))
                Chip(
                    onClick = onRetry,
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text(stringResource(R.string.chats_retry), maxLines = 1) },
                )
            }
            else -> Text(stringResource(R.string.chats_empty), fontSize = 14.sp, color = MaterialTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LoadMoreFooter(loading: Boolean, failed: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), indicatorColor = MaterialTheme.colors.primary)
        } else {
            Chip(
                onClick = onClick,
                colors = ChipDefaults.secondaryChipColors(),
                label = {
                    Text(
                        text = stringResource(if (failed) R.string.chats_retry else R.string.chats_load_more),
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem, times: ChatTimeFormatter, onOpen: (Long) -> Unit) {
    val muted = MaterialTheme.colors.onSurfaceVariant
    val onSurface = MaterialTheme.colors.onSurface
    val primary = MaterialTheme.colors.primary
    val title = when {
        item.isSavedMessages -> stringResource(R.string.chats_saved_messages)
        item.isDeletedAccount -> stringResource(R.string.chats_deleted_account)
        else -> item.title
    }
    val draftLabel = stringResource(R.string.chats_draft)
    val preview = remember(item.draftText, item.senderPrefix, item.lastMessageText, draftLabel, onSurface, primary) {
        buildAnnotatedString {
            val draft = item.draftText
            if (draft != null) {
                withStyle(SpanStyle(color = primary)) { append(draftLabel); append(": ") }
                append(draft)
            } else {
                item.senderPrefix?.let { withStyle(SpanStyle(color = onSurface)) { append(it); append(": ") } }
                append(item.lastMessageText)
            }
        }
    }
    val time = remember(item.lastMessageDate, times) { times.format(item.lastMessageDate) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RowShape)
            .background(MaterialTheme.colors.surface)
            .clickable { onOpen(item.id) }
            .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            bitmap = item.avatar,
            initials = item.initials,
            colorIndex = item.colorIndex,
            size = 40.dp,
            online = item.isOnline,
            savedMessages = item.isSavedMessages,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isMuted) {
                    Spacer(Modifier.width(3.dp))
                    Icon(imageVector = ChatIcons.Muted, contentDescription = null, tint = muted, modifier = Modifier.size(12.dp))
                }
                if (item.isPinned) {
                    Spacer(Modifier.width(3.dp))
                    Icon(imageVector = ChatIcons.Pinned, contentDescription = null, tint = muted, modifier = Modifier.size(12.dp))
                }
                if (time.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(text = time, fontSize = 12.sp, color = muted, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.lastMessageOutgoing && item.draftText == null) {
                    Text(
                        text = when {
                            item.lastMessageFailed -> "!"
                            item.lastMessageRead -> "✓✓"
                        else -> "✓"
                        },
                        fontSize = 12.sp,
                        color = if (item.lastMessageFailed) MaterialTheme.colors.error else primary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    text = preview,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val badge = when {
                    item.unreadMentionCount > 0 && item.unreadCount <= 1 -> "@"
                    item.unreadCount > 99 -> "99+"
                    item.unreadCount > 0 -> item.unreadCount.toString()
                    item.isMarkedAsUnread -> ""
                    else -> null
                }
                if (badge != null) {
                    Spacer(Modifier.width(4.dp))
                    UnreadBadge(text = badge, muted = item.isMuted)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(text: String, muted: Boolean) {
    val bg = if (muted) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.primary
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (text.isNotEmpty()) {
            Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.onPrimary, maxLines = 1)
        }
    }
}

/** Formatta l'orario dell'ultimo messaggio come fanno i client ufficiali (ora / giorno / data). */
@Stable
class ChatTimeFormatter(private val today: LocalDate, private val zone: ZoneId, locale: Locale) {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val weekdayFmt = DateTimeFormatter.ofPattern("EEE", locale)
    private val dayMonthFmt = DateTimeFormatter.ofPattern("d MMM", locale)
    private val fullFmt = DateTimeFormatter.ofPattern("dd/MM/yy", locale)

    fun format(unixSeconds: Int): String {
        if (unixSeconds <= 0) return ""
        val dt = Instant.ofEpochSecond(unixSeconds.toLong()).atZone(zone)
        val date = dt.toLocalDate()
        return when {
            date == today -> dt.format(timeFmt)
            date.isAfter(today.minusDays(7)) -> dt.format(weekdayFmt)
            date.year == today.year -> dt.format(dayMonthFmt)
            else -> dt.format(fullFmt)
        }
    }
}

/** Icone non presenti in material-icons-core (path dei Material Symbols "push_pin" e "notifications_off"). */
private object ChatIcons {
    val Pinned: ImageVector by lazy {
        vector(
            "Pinned",
            "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5c0,1.66 -1.34,3 -3,3h0v2h5.97v7l1,1l1,-1v-7H19v-2h0C17.34,12 16,10.66 16,9z",
        )
    }
    val Muted: ImageVector by lazy {
        vector(
            "Muted",
            "M20,18.69L7.84,6.14 5.27,3.49 4,4.76l2.8,2.8v0.01c-0.52,0.99 -0.8,2.16 -0.8,3.42v5l-2,2v1h13.73l2,2L21,19.72l-1,-1.03zM12,22c1.11,0 2,-0.89 2,-2h-4c0,1.11 0.89,2 2,2zm6,-7.32V11c0,-3.08 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68c-0.15,0.03 -0.29,0.08 -0.42,0.12 -0.1,0.03 -0.2,0.07 -0.3,0.11h-0.01c-0.01,0 -0.01,0 -0.02,0.01 -0.23,0.09 -0.46,0.2 -0.68,0.31 0,0 -0.01,0 -0.01,0.01L18,14.68z",
        )
    }

    private fun vector(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.White))
            .build()
}
