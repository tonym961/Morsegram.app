package it.iotatec.morsegram.wear.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatType
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageSenderChat
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.MessageTopicForum
import dev.g000sha256.tdl.dto.Notification
import dev.g000sha256.tdl.dto.NotificationGroupType
import dev.g000sha256.tdl.dto.NotificationGroupTypeMentions
import dev.g000sha256.tdl.dto.NotificationGroupTypeMessages
import dev.g000sha256.tdl.dto.NotificationSettingsScope
import dev.g000sha256.tdl.dto.NotificationSettingsScopeChannelChats
import dev.g000sha256.tdl.dto.NotificationSettingsScopeGroupChats
import dev.g000sha256.tdl.dto.NotificationSettingsScopePrivateChats
import dev.g000sha256.tdl.dto.NotificationTypeNewMessage
import dev.g000sha256.tdl.dto.NotificationTypeNewPushMessage
import dev.g000sha256.tdl.dto.OptionValueInteger
import dev.g000sha256.tdl.dto.ScopeNotificationSettings
import dev.g000sha256.tdl.dto.Update
import dev.g000sha256.tdl.dto.UpdateActiveNotifications
import dev.g000sha256.tdl.dto.UpdateChatNotificationSettings
import dev.g000sha256.tdl.dto.UpdateChatReadInbox
import dev.g000sha256.tdl.dto.UpdateChatTitle
import dev.g000sha256.tdl.dto.UpdateHavePendingNotifications
import dev.g000sha256.tdl.dto.UpdateMessageSendFailed
import dev.g000sha256.tdl.dto.UpdateNotification
import dev.g000sha256.tdl.dto.UpdateNotificationGroup
import dev.g000sha256.tdl.dto.UpdateScopeNotificationSettings
import dev.g000sha256.tdl.dto.UpdateUser
import dev.g000sha256.tdl.dto.User
import it.iotatec.morsegram.wear.R
import it.iotatec.morsegram.wear.push.PushRegistrar
import it.iotatec.morsegram.wear.settings.SettingsPrefs
import it.iotatec.morsegram.wear.tdlib.Tdlib
import it.iotatec.morsegram.wear.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Notifiche Android dei messaggi, pilotate dalle notifiche di TDLib.
 *
 * TDLib raggruppa le notifiche per chat (`notificationGroup`); qui ogni chat diventa UNA notifica
 * Android nel canale [CHANNEL_MESSAGES] con `MessagingStyle` (anteprime come la lista chat),
 * risposta inline ("reply") e "Segna come letto", gestite da [NotificationActionReceiver].
 * Quando TDLib rimuove il gruppo (letto altrove) la notifica Android viene cancellata.
 *
 * Avvio: [ensureStarted] (idempotente) da `MorsegramWearApp.onCreate` dopo `Tdlib.start`;
 * la chiamano anche il servizio FCM e il receiver, così funziona pure se il processo è stato
 * risvegliato da un push o da un'azione della notifica.
 *
 * Tutto lo stato mutabile vive su [worker] (un thread alla volta): niente lock.
 */
object MessageNotifications {
    const val CHANNEL_MESSAGES = "messages"

    private const val TAG = "MsgNotify"
    private const val NOTIFICATION_TAG = "morsegram.chat"
    private const val GROUP_COUNT_MAX = 5L
    private const val GROUP_SIZE_MAX = 10L
    private const val ACCENT = 0xFFF5A623.toInt()
    /** Una risposta inviata dalla notifica resta visibile finché l'utente non la chiude; se però la chat viene letta altrove dopo questo tempo, sparisce. */
    private const val ECHO_READ_GRACE_MS = 3_000L
    private val VIBRATION = longArrayOf(0, 60, 50, 60)

    private val started = AtomicBoolean(false)
    private lateinit var app: Context

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(1)

    // ---- stato: solo su worker ----
    private val groups = HashMap<Int, GroupState>()
    private val echoes = HashMap<Long, Echo>()
    private val lastAlerted = HashMap<Long, Int>()
    private val userNames = HashMap<Long, String>()
    private val scopeSettings = HashMap<Class<out NotificationSettingsScope>, ScopeNotificationSettings>()
    private val dirty = LinkedHashSet<Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private class GroupState(val id: Int, val type: NotificationGroupType, val chatId: Long, var totalCount: Int, var soundId: Long) {
        /** id notifica -> notifica, in ordine di arrivo. */
        val notifications = LinkedHashMap<Int, Notification>()
    }

    /** Risposta inviata dalla notifica, mostrata in coda alla conversazione. */
    private class Echo(var text: String, val time: Long, val messageId: Long)

    /** Alias di [ensureStarted]. */
    fun init(context: Context) = ensureStarted(context)

    /** Avvia canale, bridging, registrazione push e osservazione delle notifiche TDLib. Idempotente, non blocca. */
    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) return
        app = context.applicationContext
        try {
            createChannel()
        } catch (e: Exception) {
            Log.e(TAG, "canale notifiche: ${e.message}")
        }
        Bridging.configure(app)
        PushRegistrar.start(app)
        Tdlib.scope.launch(worker) { Tdlib.updates.collect { onUpdate(it) } }
        Tdlib.scope.launch(worker) { for (unit in wake) drainDirty() }
        // a ogni nuovo client TDLib (avvio, restart test/prod, dopo logout) le opzioni vanno rimesse
        Tdlib.scope.launch {
            Tdlib.authState.map { it != null }.distinctUntilChanged().filter { it }.collect { applyOptions() }
        }
        Log.i(TAG, "avviato")
    }

    // ---- API per il receiver ----

    /** La risposta è stata accodata a TDLib: la notifica viene ripubblicata con il testo inviato. */
    fun onReplySent(chatId: Long, text: String, messageId: Long) {
        Tdlib.scope.launch(worker) {
            echoes[chatId] = Echo(text, System.currentTimeMillis(), messageId)
            markDirty(chatId)
        }
    }

    /** Invio fallito: la notifica viene ripubblicata com'era (resetta l'input inline). */
    fun onReplyFailed(chatId: Long) {
        Tdlib.scope.launch(worker) { markDirty(chatId) }
    }

    /** L'utente ha segnato la chat come letta dalla notifica: via subito, TDLib confermerà. */
    fun onMarkedRead(chatId: Long) {
        Tdlib.scope.launch(worker) {
            echoes.remove(chatId)
            dropGroupsOf(chatId)
            cancel(chatId)
        }
    }

    /** Preferenze cambiate (es. anteprima on/off): ripubblica le notifiche attive. */
    fun refreshAll() {
        if (!started.get()) return
        Tdlib.scope.launch(worker) { groups.values.forEach { markDirty(it.chatId) } }
    }

    /** L'utente ha chiuso la notifica (swipe o tap). */
    fun onDismissed(chatId: Long) {
        Tdlib.scope.launch(worker) {
            echoes.remove(chatId)
            dropGroupsOf(chatId)
            dirty.remove(chatId)
        }
    }

    /** Id della notifica Android di una chat (con tag [NOTIFICATION_TAG]). */
    fun notificationId(chatId: Long): Int = (chatId xor (chatId ushr 32)).toInt()

    // ---- TDLib ----

    private suspend fun applyOptions() {
        Tdlib.req { setOption("notification_group_count_max", OptionValueInteger(GROUP_COUNT_MAX)) }
            .onFailure { Log.w(TAG, "notification_group_count_max: ${it.message}") }
        Tdlib.req { setOption("notification_group_size_max", OptionValueInteger(GROUP_SIZE_MAX)) }
            .onFailure { Log.w(TAG, "notification_group_size_max: ${it.message}") }
    }

    private fun onUpdate(u: Update) {
        when (u) {
            is UpdateActiveNotifications -> {
                val touched = HashSet<Long>()
                groups.values.forEach { touched += it.chatId }
                groups.clear()
                echoes.clear()
                for (g in u.groups) {
                    if (!g.type.isSupported()) continue
                    val s = GroupState(g.id, g.type, g.chatId, g.totalCount, soundId = 0L)
                    g.notifications.forEach { s.notifications[it.id] = it }
                    if (s.notifications.isEmpty()) continue
                    groups[s.id] = s
                    touched += s.chatId
                    // già mostrate in un avvio precedente: si ripubblicano senza vibrare
                    val maxId = s.notifications.keys.max()
                    lastAlerted[s.chatId] = maxOf(lastAlerted[s.chatId] ?: -1, maxId)
                }
                touched.forEach { markDirty(it) }
            }
            is UpdateNotificationGroup -> {
                if (!u.type.isSupported()) return
                val s = groups.getOrPut(u.notificationGroupId) { GroupState(u.notificationGroupId, u.type, u.chatId, u.totalCount, u.notificationSoundId) }
                s.totalCount = u.totalCount
                s.soundId = u.notificationSoundId
                u.removedNotificationIds.forEach { s.notifications.remove(it) }
                u.addedNotifications.forEach { s.notifications[it.id] = it }
                if (s.notifications.isEmpty()) groups.remove(s.id)
                markDirty(u.chatId)
            }
            is UpdateNotification -> {
                val s = groups[u.notificationGroupId] ?: return
                if (!s.notifications.containsKey(u.notification.id)) return
                s.notifications[u.notification.id] = u.notification
                markDirty(s.chatId)
            }
            is UpdateHavePendingNotifications ->
                Log.d(TAG, "pending: delayed=${u.haveDelayedNotifications} unreceived=${u.haveUnreceivedNotifications}")
            is UpdateUser -> if (userNames.containsKey(u.user.id)) userNames[u.user.id] = displayName(u.user)
            is UpdateScopeNotificationSettings -> {
                scopeSettings[u.scope.javaClass] = u.notificationSettings
                groups.values.forEach { markDirty(it.chatId) }
            }
            is UpdateChatNotificationSettings -> if (hasGroups(u.chatId)) markDirty(u.chatId)
            is UpdateChatTitle -> if (hasGroups(u.chatId)) markDirty(u.chatId)
            is UpdateChatReadInbox -> {
                // chat letta (sull'orologio o altrove) ben dopo la risposta inline: l'eco non serve più
                val echo = echoes[u.chatId] ?: return
                if (u.unreadCount == 0 && System.currentTimeMillis() - echo.time > ECHO_READ_GRACE_MS) {
                    echoes.remove(u.chatId)
                    if (!hasGroups(u.chatId)) cancel(u.chatId)
                }
            }
            is UpdateMessageSendFailed -> {
                val echo = echoes[u.message.chatId] ?: return
                if (echo.messageId == u.oldMessageId || echo.messageId == u.message.id) {
                    echo.text = "⚠️ ${app.getString(R.string.notify_reply_failed)}: ${echo.text}"
                    markDirty(u.message.chatId)
                }
            }
            else -> Unit
        }
    }

    private fun NotificationGroupType.isSupported() = this is NotificationGroupTypeMessages || this is NotificationGroupTypeMentions

    private fun hasGroups(chatId: Long) = groups.values.any { it.chatId == chatId }

    private fun dropGroupsOf(chatId: Long) {
        val ids = groups.values.filter { it.chatId == chatId }.map { it.id }
        ids.forEach { groups.remove(it) }
    }

    private fun markDirty(chatId: Long) {
        dirty += chatId
        wake.trySend(Unit)
    }

    private suspend fun drainDirty() {
        while (true) {
            val chatId = dirty.firstOrNull() ?: return
            dirty.remove(chatId)
            try {
                render(chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "render chat $chatId: ${e.message}", e)
            }
        }
    }

    // ---- costruzione della notifica ----

    private suspend fun render(chatId: Long) {
        val chatGroups = groups.values.filter { it.chatId == chatId }
        val items = chatGroups.flatMap { it.notifications.values }.sortedBy { it.id }
        val echo = echoes[chatId]
        if (items.isEmpty()) {
            // con un'eco la notifica già pubblicata (messaggi + risposta) resta finché l'utente non la chiude
            if (echo == null) cancel(chatId)
            return
        }
        val chat = Tdlib.req { getChat(chatId) }.getOrNull()
        if (chat == null) {
            Log.w(TAG, "getChat($chatId) fallita: notifica saltata")
            return
        }
        if (chatGroups.all { it.type is NotificationGroupTypeMessages } && isMuted(chat)) {
            cancel(chatId)
            return
        }
        val isChannel = (chat.type as? ChatTypeSupergroup)?.isChannel == true
        val isGroup = chat.type is ChatTypeBasicGroup || (chat.type is ChatTypeSupergroup && !isChannel)
        val chatPerson = Person.Builder().setName(chat.title.ifBlank { app.getString(R.string.app_name) }).setKey("c$chatId").build()
        val me = Person.Builder().setName(app.getString(R.string.notify_me)).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(isGroup)
        if (isGroup) style.setConversationTitle(chat.title)

        // preferenza "Notifiche: solo mittente" (impostazioni): niente testo del messaggio
        val previewAllowed = SettingsPrefs.notifyPreview(app)
        val messageIds = ArrayList<Long>(items.size)
        var lastText = ""
        var lastDate = 0L
        var lastMessage: Message? = null
        for (n in items) {
            val text: String
            val sender: Person
            when (val t = n.type) {
                is NotificationTypeNewMessage -> {
                    messageIds += t.message.id
                    lastMessage = t.message
                    if (t.showPreview && previewAllowed) {
                        text = NotificationText.preview(app, t.message.content)
                        sender = if (isChannel) chatPerson else senderPerson(t.message, chat, chatPerson)
                    } else {
                        text = app.getString(R.string.notify_new_message)
                        sender = chatPerson
                    }
                }
                is NotificationTypeNewPushMessage -> {
                    messageIds += t.messageId
                    text = if (previewAllowed) NotificationText.pushPreview(app, t.content) else app.getString(R.string.notify_new_message)
                    sender = when {
                        isChannel -> chatPerson
                        t.senderName.isNotBlank() -> Person.Builder().setName(t.senderName).setKey(senderKey(t.senderId)).build()
                        else -> chatPerson
                    }
                }
                else -> continue
            }
            val time = n.date * 1000L
            style.addMessage(text, time, sender)
            lastText = text
            lastDate = time
        }
        if (messageIds.isEmpty()) {
            if (echo == null) cancel(chatId)
            return
        }
        if (echo != null) {
            style.addMessage(echo.text, echo.time, null as Person?)
            lastText = echo.text
            lastDate = maxOf(lastDate, echo.time)
        }

        val newest = items.last()
        val alerted = lastAlerted[chatId] ?: -1
        val silent = newest.isSilent || chatGroups.any { it.notifications.containsKey(newest.id) && it.soundId == 0L && it.type is NotificationGroupTypeMessages }
        val shouldAlert = newest.id > alerted && !silent && echo == null
        lastAlerted[chatId] = maxOf(alerted, newest.id)

        val totalCount = chatGroups.sumOf { it.totalCount }.coerceAtLeast(messageIds.size)
        val groupIds = chatGroups.map { it.id }.toIntArray()
        val maxIds = chatGroups.map { g -> g.notifications.keys.max() }.toIntArray()
        val forumTopicId = (lastMessage?.topicId as? MessageTopicForum)?.forumTopicId ?: 0
        val replyToMessageId = if (isGroup) (lastMessage?.id ?: 0L) else 0L
        val ids = messageIds.toLongArray()

        val reply = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            app.getString(R.string.notify_action_reply),
            broadcast(NotificationActionReceiver.replyIntent(app, chatId, groupIds, maxIds, ids, forumTopicId, replyToMessageId), mutable = true),
        )
            .addRemoteInput(RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel(app.getString(R.string.notify_reply_hint)).build())
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            app.getString(R.string.notify_action_mark_read),
            broadcast(NotificationActionReceiver.markReadIntent(app, chatId, groupIds, maxIds, ids), mutable = false),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val builder = NotificationCompat.Builder(app, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.morsegram_icon_mono)
            .setColor(ACCENT)
            .setStyle(style)
            .setContentTitle(chat.title)
            .setContentText(lastText)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(lastDate)
            .setShowWhen(true)
            .setNumber(totalCount)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setGroup("chat:$chatId")
            .setOnlyAlertOnce(!shouldAlert)
            .setSilent(!shouldAlert)
            .setContentIntent(openChatIntent(chatId))
            .setDeleteIntent(broadcast(NotificationActionReceiver.dismissIntent(app, chatId, groupIds, maxIds), mutable = false))
            .addAction(reply)
            .addAction(markRead)
        if (messageIds.size > 1) builder.setSubText(app.getString(R.string.notify_new_messages, totalCount))

        post(chatId, builder)
    }

    private fun post(chatId: Long, builder: NotificationCompat.Builder) {
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) {
            Log.w(TAG, "notifiche disattivate (permesso POST_NOTIFICATIONS?): chat $chatId non mostrata")
            return
        }
        try {
            nm.notify(NOTIFICATION_TAG, notificationId(chatId), builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notify negato: ${e.message}")
        }
    }

    private fun cancel(chatId: Long) {
        try {
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_TAG, notificationId(chatId))
        } catch (e: Exception) {
            Log.w(TAG, "cancel: ${e.message}")
        }
    }

    private suspend fun senderPerson(m: Message, chat: Chat, chatPerson: Person): Person = when (val s = m.senderId) {
        is MessageSenderUser -> Person.Builder().setName(userName(s.userId)).setKey("u${s.userId}").build()
        is MessageSenderChat ->
            if (s.chatId == chat.id) chatPerson
            else Person.Builder().setName(Tdlib.req { getChat(s.chatId) }.getOrNull()?.title?.ifBlank { null } ?: chat.title).setKey("c${s.chatId}").build()
        else -> chatPerson
    }

    private fun senderKey(sender: dev.g000sha256.tdl.dto.MessageSender?): String = when (sender) {
        is MessageSenderUser -> "u${sender.userId}"
        is MessageSenderChat -> "c${sender.chatId}"
        else -> "unknown"
    }

    private suspend fun userName(userId: Long): String {
        userNames[userId]?.let { return it }
        val user = Tdlib.req { getUser(userId) }.getOrNull()
        val name = if (user != null) displayName(user) else app.getString(R.string.notify_unknown_sender)
        userNames[userId] = name
        return name
    }

    private fun displayName(u: User): String {
        val full = "${u.firstName} ${u.lastName}".trim()
        if (full.isNotEmpty()) return full
        val username = u.usernames?.activeUsernames?.firstOrNull()
        return if (!username.isNullOrBlank()) "@$username" else app.getString(R.string.notify_unknown_sender)
    }

    /** Vero se la chat è silenziata (impostazione propria o, se "usa predefinita", quella dell'ambito). */
    private suspend fun isMuted(chat: Chat): Boolean {
        val s = chat.notificationSettings
        if (!s.useDefaultMuteFor) return s.muteFor > 0
        val scope = scopeFor(chat.type)
        val settings = scopeSettings[scope.javaClass]
            ?: Tdlib.req { getScopeNotificationSettings(scope) }.getOrNull()?.also { scopeSettings[scope.javaClass] = it }
            ?: return false
        return settings.muteFor > 0
    }

    private fun scopeFor(type: ChatType): NotificationSettingsScope = when (type) {
        is ChatTypeBasicGroup -> NotificationSettingsScopeGroupChats()
        is ChatTypeSupergroup -> if (type.isChannel) NotificationSettingsScopeChannelChats() else NotificationSettingsScopeGroupChats()
        else -> NotificationSettingsScopePrivateChats()
    }

    // ---- intent ----

    private fun openChatIntent(chatId: Long): PendingIntent {
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("morsegram://chat/$chatId"))
            .putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(app, notificationId(chatId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Il reply con RemoteInput deve essere mutabile (API 31+), gli altri immutabili. Azione e data URI distinguono gli intent per chat. */
    private fun broadcast(intent: Intent, mutable: Boolean): PendingIntent {
        val chatId = intent.getLongExtra(NotificationActionReceiver.EXTRA_CHAT_ID, 0L)
        val flags = (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(app, notificationId(chatId), intent, flags)
    }

    private fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(app.getString(R.string.notify_channel_messages))
            .setDescription(app.getString(R.string.notify_channel_messages_desc))
            .setVibrationEnabled(true)
            .setVibrationPattern(VIBRATION)
            .setShowBadge(true)
            .build()
        NotificationManagerCompat.from(app).createNotificationChannel(channel)
    }
}
