package it.iotatec.morsegram.wear.chats

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.BasicGroup
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatList
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListFolder
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatNotificationSettings
import dev.g000sha256.tdl.dto.ChatPhotoInfo
import dev.g000sha256.tdl.dto.ChatPosition
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSecret
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.DraftMessage
import dev.g000sha256.tdl.dto.DraftMessageContentText
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAnimatedEmoji
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageBasicGroupChatCreate
import dev.g000sha256.tdl.dto.MessageCall
import dev.g000sha256.tdl.dto.MessageChatAddMembers
import dev.g000sha256.tdl.dto.MessageChatChangePhoto
import dev.g000sha256.tdl.dto.MessageChatChangeTitle
import dev.g000sha256.tdl.dto.MessageChatDeleteMember
import dev.g000sha256.tdl.dto.MessageChatDeletePhoto
import dev.g000sha256.tdl.dto.MessageChatJoinByLink
import dev.g000sha256.tdl.dto.MessageChatJoinByRequest
import dev.g000sha256.tdl.dto.MessageChecklist
import dev.g000sha256.tdl.dto.MessageContact
import dev.g000sha256.tdl.dto.MessageContactRegistered
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.MessageDice
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageExpiredPhoto
import dev.g000sha256.tdl.dto.MessageExpiredVideo
import dev.g000sha256.tdl.dto.MessageExpiredVideoNote
import dev.g000sha256.tdl.dto.MessageExpiredVoiceNote
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
import dev.g000sha256.tdl.dto.MessageSenderChat
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.MessageSendingStateFailed
import dev.g000sha256.tdl.dto.MessageSticker
import dev.g000sha256.tdl.dto.MessageStory
import dev.g000sha256.tdl.dto.MessageSupergroupChatCreate
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageUnsupported
import dev.g000sha256.tdl.dto.MessageVenue
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVideoChatEnded
import dev.g000sha256.tdl.dto.MessageVideoChatStarted
import dev.g000sha256.tdl.dto.MessageVideoNote
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.Minithumbnail
import dev.g000sha256.tdl.dto.NotificationSettingsScope
import dev.g000sha256.tdl.dto.NotificationSettingsScopeChannelChats
import dev.g000sha256.tdl.dto.NotificationSettingsScopeGroupChats
import dev.g000sha256.tdl.dto.OptionValueInteger
import dev.g000sha256.tdl.dto.ScopeNotificationSettings
import dev.g000sha256.tdl.dto.Supergroup
import dev.g000sha256.tdl.dto.Update
import dev.g000sha256.tdl.dto.UpdateBasicGroup
import dev.g000sha256.tdl.dto.UpdateChatAddedToList
import dev.g000sha256.tdl.dto.UpdateChatDraftMessage
import dev.g000sha256.tdl.dto.UpdateChatIsMarkedAsUnread
import dev.g000sha256.tdl.dto.UpdateChatLastMessage
import dev.g000sha256.tdl.dto.UpdateChatNotificationSettings
import dev.g000sha256.tdl.dto.UpdateChatPhoto
import dev.g000sha256.tdl.dto.UpdateChatPosition
import dev.g000sha256.tdl.dto.UpdateChatReadInbox
import dev.g000sha256.tdl.dto.UpdateChatReadOutbox
import dev.g000sha256.tdl.dto.UpdateChatRemovedFromList
import dev.g000sha256.tdl.dto.UpdateChatTitle
import dev.g000sha256.tdl.dto.UpdateChatUnreadMentionCount
import dev.g000sha256.tdl.dto.UpdateFile
import dev.g000sha256.tdl.dto.UpdateMessageContent
import dev.g000sha256.tdl.dto.UpdateNewChat
import dev.g000sha256.tdl.dto.UpdateOption
import dev.g000sha256.tdl.dto.UpdateScopeNotificationSettings
import dev.g000sha256.tdl.dto.UpdateSupergroup
import dev.g000sha256.tdl.dto.UpdateUser
import dev.g000sha256.tdl.dto.UpdateUserStatus
import dev.g000sha256.tdl.dto.User
import dev.g000sha256.tdl.dto.UserStatus
import dev.g000sha256.tdl.dto.UserStatusOnline
import dev.g000sha256.tdl.dto.UserTypeBot
import dev.g000sha256.tdl.dto.UserTypeDeleted
import it.iotatec.morsegram.wear.MorsegramWearApp
import it.iotatec.morsegram.wear.R
import it.iotatec.morsegram.wear.tdlib.TdlException
import it.iotatec.morsegram.wear.tdlib.Tdlib
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tipo di chat, già risolto (per le private distingue i bot). */
enum class ChatKind { PRIVATE, BOT, GROUP, CHANNEL, SECRET }

/**
 * Riga della lista chat pronta per la UI. Immutabile: ogni cambiamento produce una nuova istanza,
 * quindi Compose salta la ricomposizione delle righe che non sono cambiate (`==` strutturale;
 * [avatar] è la stessa istanza finché la foto non cambia).
 *
 * - [lastMessageText] è l'anteprima già localizzata (vedi [MessagePreview]), senza mittente.
 * - [senderPrefix] è il nome del mittente ("Tu" per i messaggi in uscita) solo nei gruppi, da
 *   mostrare come `prefix: testo`; `null` altrove.
 * - [order] è l'ordine TDLib nella lista principale: più alto = più in alto; 0 = non in lista.
 */
@Immutable
data class ChatItem(
    val id: Long,
    val title: String,
    val kind: ChatKind,
    val isSavedMessages: Boolean,
    val isDeletedAccount: Boolean,
    val lastMessageId: Long,
    val lastMessageText: String,
    val lastMessageDate: Int,
    val lastMessageOutgoing: Boolean,
    val lastMessageRead: Boolean,
    val lastMessageFailed: Boolean,
    val senderPrefix: String?,
    val draftText: String?,
    val unreadCount: Int,
    val unreadMentionCount: Int,
    val isMarkedAsUnread: Boolean,
    val isMuted: Boolean,
    val isPinned: Boolean,
    val isOnline: Boolean,
    val order: Long,
    val avatar: ImageBitmap?,
    val initials: String,
    val colorIndex: Int,
)

/**
 * Cache delle chat alimentata dagli update TDLib (lista principale) e sorgente unica per la
 * lista chat e per la schermata chat. Singleton legato a [Tdlib]: vive quanto il processo.
 *
 * Avvio: [start] (idempotente; la ViewModel della lista lo chiama, ma prima è meglio). Appena
 * `Tdlib.authState` è `AuthorizationStateReady` parte il primo [loadMore]; quando la rete torna
 * (`ConnectionStateReady`) un caricamento fallito viene ritentato. Al logout la cache si svuota.
 *
 * API pubblica stabile:
 * - [chats]: righe della lista principale, ordinate per `order` decrescente (le fissate prima).
 * - [users] / [user] / [userStatus]: cache utenti (`updateUser`) e stato online aggiornato
 *   (`updateUserStatus`; il DTO `User` non è aggiornabile, quindi lo stato vive a parte).
 * - [chat]: ultimo DTO `Chat` ricevuto da TDLib (updateNewChat/getChat). I campi vivi (titolo,
 *   ultimo messaggio, non letti, bozza, posizioni) sono in [item] / [itemFlow].
 * - [getChat]: come [chat] ma scarica la chat da TDLib se non è in cache (es. apertura da notifica).
 * - [loadMore] / [allLoaded] / [isLoading] / [loadFailed]: paginazione della lista principale.
 * - [MessagePreview]: anteprima testuale di un `MessageContent`, riusabile dalla schermata chat.
 */
object ChatRepository {
    private const val TAG = "ChatRepository"
    private const val PAGE = 30
    private const val FLUSH_DELAY_MS = 40L
    private const val AVATAR_PX = 96
    private const val OPTION_MY_ID = "my_id"
    private const val SCOPE_PRIVATE = 0
    private const val SCOPE_GROUP = 1
    private const val SCOPE_CHANNEL = 2

    private val scope: CoroutineScope get() = Tdlib.scope
    private val started = AtomicBoolean(false)

    /** Serializza ogni accesso allo stato mutabile (update, flush, fetch). */
    private val stateLock = Mutex()
    private val loadMutex = Mutex()

    // Stato per chat. La mappa è concorrente perché i getter sincroni la leggono senza lock;
    // i campi di ChatState vengono scritti solo sotto stateLock.
    private val states = ConcurrentHashMap<Long, ChatState>()
    private val rawChats = ConcurrentHashMap<Long, Chat>()
    private val usersMap = ConcurrentHashMap<Long, User>()
    private val userStatuses = ConcurrentHashMap<Long, UserStatus>()
    private val supergroups = ConcurrentHashMap<Long, Supergroup>()
    private val basicGroups = ConcurrentHashMap<Long, BasicGroup>()
    private val itemFlows = ConcurrentHashMap<Long, MutableStateFlow<ChatItem?>>()

    // Solo sotto stateLock.
    private val userChats = HashMap<Long, Long>()          // userId -> chat privata
    private val avatarFiles = HashMap<Int, Long>()          // fileId -> chatId (foto piccola in download)
    private val pendingFetch = HashSet<Long>()
    private val scopeSettings = HashMap<Int, ScopeNotificationSettings>()
    private var usersDirty = false
    @Volatile private var ready = false
    private val flushRequested = AtomicBoolean(false)

    private val _chats = MutableStateFlow<List<ChatItem>>(emptyList())
    /** Lista principale, ordinata per posizione TDLib decrescente (fissate in testa). */
    val chats: StateFlow<List<ChatItem>> = _chats.asStateFlow()

    private val _users = MutableStateFlow<Map<Long, User>>(emptyMap())
    /** Snapshot della cache utenti, pubblicato insieme alla lista (coalescente). */
    val users: StateFlow<Map<Long, User>> = _users.asStateFlow()

    private val _myId = MutableStateFlow(0L)
    /** Id dell'utente loggato (opzione TDLib `my_id`, o `getMe`); 0 finché sconosciuto. */
    val myId: StateFlow<Long> = _myId.asStateFlow()

    private val _allLoaded = MutableStateFlow(false)
    /** Vero quando `loadChats` ha risposto 404: non ci sono altre chat da caricare. */
    val allLoaded: StateFlow<Boolean> = _allLoaded.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    /** Vero mentre un [loadMore] è in corso. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)
    /** Vero se l'ultimo [loadMore] è fallito per un errore diverso da 404 (es. rete). */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    private val res: Resources get() = MorsegramWearApp.instance.resources

    /** Avvia la raccolta degli update e l'osservazione di auth/connessione. Idempotente. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { Tdlib.updates.collect { handle(it) } }
        scope.launch {
            Tdlib.authState.collect { state ->
                if (state is AuthorizationStateReady) onReady() else reset()
            }
        }
        scope.launch {
            Tdlib.connectionState.collect { cs ->
                val needRetry = _loadFailed.value || (_chats.value.isEmpty() && !_allLoaded.value)
                if (cs is ConnectionStateReady && ready && needRetry) launch { loadMore() }
            }
        }
    }

    /**
     * Chiede a TDLib la pagina successiva della lista principale (`loadChats(main, 30)`). Le chat
     * arrivano tramite update; [allLoaded] diventa vero quando TDLib risponde 404. Ignorata se
     * un caricamento è già in corso o se tutto è già caricato.
     */
    suspend fun loadMore() {
        if (_allLoaded.value) return
        if (!loadMutex.tryLock()) return
        try {
            _isLoading.value = true
            val r = Tdlib.req { loadChats(ChatListMain(), PAGE) }
            val err = r.exceptionOrNull()
            when {
                r.isSuccess -> { _loadFailed.value = false; reconcile() }
                err is TdlException && err.code == 404 -> { _allLoaded.value = true; _loadFailed.value = false; reconcile() }
                else -> { _loadFailed.value = true; Log.w(TAG, "loadChats: ${err?.message}") }
            }
        } finally {
            _isLoading.value = false
            loadMutex.unlock()
        }
    }

    /** Ultimo DTO `Chat` ricevuto per [id] (campi vivi in [item]); null se non in cache. */
    fun chat(id: Long): Chat? = rawChats[id]

    /** Come [chat], ma se la chat non è in cache la chiede a TDLib e la aggiunge alla cache. */
    suspend fun getChat(id: Long): Chat? {
        rawChats[id]?.let { return it }
        val chat = Tdlib.req { getChat(id) }.getOrNull() ?: return null
        stateLock.withLock { if (!states.containsKey(id)) addChat(chat) }
        return chat
    }

    /** Riga corrente della chat [id] (anche se non è nella lista principale), o null. */
    fun item(id: Long): ChatItem? = states[id]?.item

    /** Flusso della riga di [id]; se la chat non è in cache viene scaricata. */
    fun itemFlow(id: Long): StateFlow<ChatItem?> {
        val flow = itemFlows.getOrPut(id) { MutableStateFlow(states[id]?.item) }
        if (!states.containsKey(id)) scope.launch { stateLock.withLock { ensureChat(id) } }
        return flow
    }

    fun user(id: Long): User? = usersMap[id]

    /** Stato online/offline più recente di [id] (`updateUserStatus` ha la precedenza sul DTO). */
    fun userStatus(id: Long): UserStatus? = userStatuses[id] ?: usersMap[id]?.status

    fun supergroup(id: Long): Supergroup? = supergroups[id]

    fun basicGroup(id: Long): BasicGroup? = basicGroups[id]

    /** Nome da mostrare per un utente: nome, altrimenti cognome, altrimenti "Account eliminato". */
    fun displayName(user: User): String {
        val first = user.firstName.trim()
        val last = user.lastName.trim()
        val full = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        return full.ifEmpty { res.getString(R.string.chats_deleted_account) }
    }

    // ---------------------------------------------------------------- ciclo di vita

    private fun onReady() {
        ready = true
        _allLoaded.value = false
        _loadFailed.value = false
        scope.launch {
            if (_myId.value == 0L) {
                Tdlib.req { getMe() }.getOrNull()?.let { me ->
                    stateLock.withLock {
                        _myId.value = me.id
                        usersMap[me.id] = me
                        usersDirty = true
                        touchAllLocked()
                    }
                }
            }
            loadMore()
        }
    }

    private suspend fun reset() {
        ready = false
        _allLoaded.value = false
        _loadFailed.value = false
        stateLock.withLock {
            if (states.isEmpty() && usersMap.isEmpty() && _myId.value == 0L) return
            states.clear(); rawChats.clear(); usersMap.clear(); userStatuses.clear()
            supergroups.clear(); basicGroups.clear(); userChats.clear(); avatarFiles.clear()
            pendingFetch.clear(); scopeSettings.clear()
            itemFlows.values.forEach { it.value = null }
            _myId.value = 0L
            _chats.value = emptyList()
            _users.value = emptyMap()
            usersDirty = false
        }
    }

    /** Dopo un `loadChats` riuscito chiede a TDLib l'ordine reale e scarica le chat che non abbiamo. */
    private suspend fun reconcile() {
        val limit = (states.size + PAGE).coerceIn(PAGE, 500)
        val ids = Tdlib.req { getChats(ChatListMain(), limit) }.getOrNull()?.chatIds ?: return
        stateLock.withLock { for (id in ids) if (!states.containsKey(id)) ensureChat(id) }
    }

    // ---------------------------------------------------------------- update TDLib

    private suspend fun handle(u: Update) {
        stateLock.withLock {
            when (u) {
                is UpdateNewChat -> addChat(u.chat)
                is UpdateChatLastMessage -> withChat(u.chatId) { s ->
                    s.lastMessage = u.lastMessage
                    s.lastContentOverride = null
                    s.positions = u.positions?.toList().orEmpty()
                }
                is UpdateChatPosition -> withChat(u.chatId) { s -> s.setPosition(u.position) }
                is UpdateChatReadInbox -> withChat(u.chatId) { s ->
                    s.lastReadInboxMessageId = u.lastReadInboxMessageId
                    s.unreadCount = u.unreadCount
                }
                is UpdateChatReadOutbox -> withChat(u.chatId) { s -> s.lastReadOutboxMessageId = u.lastReadOutboxMessageId }
                is UpdateChatTitle -> withChat(u.chatId) { s -> s.title = u.title }
                is UpdateChatPhoto -> withChat(u.chatId) { s -> s.photo = u.photo; s.smallFile = null }
                is UpdateChatUnreadMentionCount -> withChat(u.chatId) { s -> s.unreadMentionCount = u.unreadMentionCount }
                is UpdateChatIsMarkedAsUnread -> withChat(u.chatId) { s -> s.isMarkedAsUnread = u.isMarkedAsUnread }
                is UpdateChatDraftMessage -> withChat(u.chatId) { s ->
                    s.draft = u.draftMessage
                    s.positions = u.positions?.toList().orEmpty()
                }
                is UpdateChatNotificationSettings -> withChat(u.chatId) { s -> s.notificationSettings = u.notificationSettings }
                is UpdateChatAddedToList -> if (u.chatList is ChatListMain) withChat(u.chatId) { s -> s.inMainList = true }
                is UpdateChatRemovedFromList -> if (u.chatList is ChatListMain) withChat(u.chatId) { s -> s.inMainList = false }
                is UpdateUser -> {
                    usersMap[u.user.id] = u.user
                    usersDirty = true
                    userChats[u.user.id]?.let { touchId(it) }
                    requestFlush()
                }
                is UpdateUserStatus -> {
                    userStatuses[u.userId] = u.status
                    userChats[u.userId]?.let { touchId(it) }
                }
                is UpdateSupergroup -> supergroups[u.supergroup.id] = u.supergroup
                is UpdateBasicGroup -> basicGroups[u.basicGroup.id] = u.basicGroup
                is UpdateMessageContent -> states[u.chatId]?.let { s ->
                    if (s.lastMessage?.id == u.messageId) { s.lastContentOverride = u.newContent; touch(s) }
                }
                is UpdateScopeNotificationSettings -> {
                    scopeSettings[scopeKey(u.scope)] = u.notificationSettings
                    touchAllLocked()
                }
                is UpdateOption -> if (u.name == OPTION_MY_ID) {
                    (u.value as? OptionValueInteger)?.let { _myId.value = it.value; touchAllLocked() }
                }
                is UpdateFile -> onFile(u.file)
                else -> Unit
            }
        }
    }

    /** Applica [block] alla chat [id] e la marca da ricostruire; se è sconosciuta la scarica. */
    private inline fun withChat(id: Long, block: (ChatState) -> Unit) {
        val s = states[id]
        if (s == null) { ensureChat(id); return }
        block(s)
        touch(s)
    }

    private fun addChat(chat: Chat) {
        val s = ChatState(chat)
        states[chat.id] = s
        rawChats[chat.id] = chat
        (chat.type as? ChatTypePrivate)?.let { userChats[it.userId] = chat.id }
        (chat.type as? ChatTypeSecret)?.let { userChats[it.userId] = chat.id }
        touch(s)
    }

    private fun ensureChat(id: Long) {
        if (states.containsKey(id) || !pendingFetch.add(id)) return
        scope.launch {
            val r = Tdlib.req { getChat(id) }
            stateLock.withLock {
                pendingFetch.remove(id)
                r.getOrNull()?.let { if (!states.containsKey(it.id)) addChat(it) }
            }
            r.exceptionOrNull()?.let { Log.w(TAG, "getChat($id): ${it.message}") }
        }
    }

    private fun onFile(file: File) {
        val chatId = avatarFiles[file.id] ?: return
        val s = states[chatId] ?: return
        s.smallFile = file
        if (file.local?.isDownloadingCompleted == true) avatarFiles.remove(file.id)
        touch(s)
    }

    private fun touch(s: ChatState) { s.dirty = true; requestFlush() }

    private fun touchId(id: Long) { states[id]?.let { touch(it) } }

    private fun touchAllLocked() {
        for (s in states.values) s.dirty = true
        requestFlush()
    }

    private fun scopeKey(scope: NotificationSettingsScope): Int = when (scope) {
        is NotificationSettingsScopeGroupChats -> SCOPE_GROUP
        is NotificationSettingsScopeChannelChats -> SCOPE_CHANNEL
        else -> SCOPE_PRIVATE
    }

    // ---------------------------------------------------------------- flush (coalescente)

    /** Raggruppa gli update in raffica: una sola ricostruzione/emissione ogni [FLUSH_DELAY_MS]. */
    private fun requestFlush() {
        if (!flushRequested.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(FLUSH_DELAY_MS)
            flushRequested.set(false)
            try { flush() } catch (e: Exception) { Log.e(TAG, "flush", e) }
        }
    }

    private suspend fun flush() {
        stateLock.withLock {
            val now = (System.currentTimeMillis() / 1000L).toInt()
            var changed = false
            for (s in states.values) {
                if (!s.dirty) continue
                s.dirty = false
                val item = buildItem(s, now)
                if (item != s.item) {
                    s.item = item
                    changed = true
                    itemFlows[item.id]?.value = item
                }
            }
            if (changed) {
                val list = states.values
                    .mapNotNull { s -> s.item?.takeIf { it.order > 0L } }
                    .sortedWith(compareByDescending<ChatItem> { it.order }.thenByDescending { it.id })
                if (list != _chats.value) _chats.value = list
            }
            if (usersDirty) {
                usersDirty = false
                _users.value = HashMap(usersMap)
            }
        }
    }

    private fun buildItem(s: ChatState, now: Int): ChatItem {
        val chat = s.chat
        val type = chat.type
        val privateUserId = (type as? ChatTypePrivate)?.userId ?: (type as? ChatTypeSecret)?.userId
        val user = privateUserId?.let { usersMap[it] }
        val kind = when (type) {
            is ChatTypePrivate -> if (user?.type is UserTypeBot) ChatKind.BOT else ChatKind.PRIVATE
            is ChatTypeBasicGroup -> ChatKind.GROUP
            is ChatTypeSupergroup -> if (type.isChannel) ChatKind.CHANNEL else ChatKind.GROUP
            is ChatTypeSecret -> ChatKind.SECRET
            else -> ChatKind.PRIVATE
        }
        val myId = _myId.value
        val isSaved = privateUserId != null && myId != 0L && privateUserId == myId
        val isDeleted = user?.type is UserTypeDeleted
        val msg = s.lastMessage
        val content = if (msg != null) (s.lastContentOverride ?: msg.content) else null
        val preview = content?.let { MessagePreview.of(it, res) } ?: ""
        val senderPrefix = if (msg != null && kind == ChatKind.GROUP) senderName(msg) else null
        val draftText = (s.draft?.content as? DraftMessageContentText)?.text?.text
            ?.takeIf { it.isNotBlank() }?.let { MessagePreview.collapse(it) }
        val mainPosition = s.mainPosition
        val outgoing = msg?.isOutgoing == true
        val status = user?.let { userStatuses[it.id] ?: it.status }
        val title = s.title.ifBlank { user?.let { displayName(it) } ?: "" }
        return ChatItem(
            id = chat.id,
            title = title,
            kind = kind,
            isSavedMessages = isSaved,
            isDeletedAccount = isDeleted,
            lastMessageId = msg?.id ?: 0L,
            lastMessageText = preview,
            lastMessageDate = msg?.date ?: 0,
            lastMessageOutgoing = outgoing,
            lastMessageRead = outgoing && msg != null && msg.id <= s.lastReadOutboxMessageId,
            lastMessageFailed = msg?.sendingState is MessageSendingStateFailed,
            senderPrefix = senderPrefix,
            draftText = draftText,
            unreadCount = s.unreadCount,
            unreadMentionCount = s.unreadMentionCount,
            isMarkedAsUnread = s.isMarkedAsUnread,
            isMuted = isMuted(s.notificationSettings, kind),
            isPinned = mainPosition?.isPinned == true,
            isOnline = kind == ChatKind.PRIVATE && !isSaved && status is UserStatusOnline && status.expires > now,
            order = mainPosition?.order ?: 0L,
            avatar = avatarFor(s),
            initials = initialsOf(title),
            colorIndex = avatarColorIndex(chat.id),
        )
    }

    private fun senderName(msg: Message): String? {
        if (msg.isOutgoing) return res.getString(R.string.chats_you)
        return when (val sender = msg.senderId) {
            is MessageSenderUser -> usersMap[sender.userId]?.let { u ->
                u.firstName.trim().ifEmpty { u.lastName.trim() }.ifEmpty { null }
            }
            is MessageSenderChat -> states[sender.chatId]?.title?.ifBlank { null }
            else -> null
        }
    }

    private fun isMuted(settings: ChatNotificationSettings?, kind: ChatKind): Boolean {
        if (settings == null) return false
        if (!settings.useDefaultMuteFor) return settings.muteFor > 0
        val key = when (kind) {
            ChatKind.GROUP -> SCOPE_GROUP
            ChatKind.CHANNEL -> SCOPE_CHANNEL
            else -> SCOPE_PRIVATE
        }
        return (scopeSettings[key]?.muteFor ?: 0) > 0
    }

    // ---------------------------------------------------------------- avatar

    /**
     * Avatar piccolo: la foto piccola se già scaricata (nitida), altrimenti il minithumbnail JPEG
     * (sfocato ma immediato) e intanto si chiede a TDLib il download della foto piccola.
     * La bitmap decodificata è cachata per chat: stessa istanza finché la foto non cambia.
     */
    private fun avatarFor(s: ChatState): ImageBitmap? {
        val photo = s.photo
        if (photo == null) { s.avatarKey = null; s.avatar = null; return null }
        val small = s.smallFile ?: photo.small
        val local = small?.local
        val path = if (local != null && local.isDownloadingCompleted) local.path.takeIf { it.isNotEmpty() } else null
        val mini = photo.minithumbnail
        val key: String? = when {
            path != null -> "f:$path"
            mini != null -> "m:${mini.data.size}:${mini.data.contentHashCode()}"
            else -> null
        }
        if (key != s.avatarKey) {
            s.avatarKey = key
            s.avatar = if (key == null) null else decodeAvatar(path, mini)
        }
        if (small != null && local != null && path == null && local.canBeDownloaded &&
            !local.isDownloadingActive && s.avatarFileRequested != small.id
        ) {
            s.avatarFileRequested = small.id
            avatarFiles[small.id] = s.chat.id
            val fileId = small.id
            scope.launch {
                Tdlib.req { downloadFile(fileId, 1, 0L, 0L, false) }
                    .exceptionOrNull()?.let { Log.w(TAG, "downloadFile($fileId): ${it.message}") }
            }
        }
        return s.avatar
    }

    private fun decodeAvatar(path: String?, mini: Minithumbnail?): ImageBitmap? = try {
        val bitmap = if (path != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val side = minOf(bounds.outWidth, bounds.outHeight)
            val sample = if (side > 0) Integer.highestOneBit(maxOf(1, side / AVATAR_PX)) else 1
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } else {
            mini?.data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        Log.w(TAG, "decodeAvatar: ${e.message}")
        null
    }

    // ---------------------------------------------------------------- stato interno

    private class ChatState(val chat: Chat) {
        var title: String = chat.title
        var photo: ChatPhotoInfo? = chat.photo
        /** Ultimo `File` ricevuto per la foto piccola (il DTO dentro [photo] non si aggiorna). */
        var smallFile: File? = null
        var lastMessage: Message? = chat.lastMessage
        var lastContentOverride: MessageContent? = null
        var positions: List<ChatPosition> = chat.positions?.toList().orEmpty()
        var unreadCount: Int = chat.unreadCount
        var lastReadInboxMessageId: Long = chat.lastReadInboxMessageId
        var lastReadOutboxMessageId: Long = chat.lastReadOutboxMessageId
        var unreadMentionCount: Int = chat.unreadMentionCount
        var isMarkedAsUnread: Boolean = chat.isMarkedAsUnread
        var draft: DraftMessage? = chat.draftMessage
        var notificationSettings: ChatNotificationSettings? = chat.notificationSettings
        var inMainList: Boolean = chat.chatLists?.any { it is ChatListMain } == true
        var avatarKey: String? = null
        var avatar: ImageBitmap? = null
        var avatarFileRequested: Int = 0
        @Volatile var item: ChatItem? = null
        var dirty: Boolean = true

        val mainPosition: ChatPosition? get() = positions.firstOrNull { it.list is ChatListMain }

        /** Sostituisce la posizione nella lista di [position]; `order == 0` la rimuove. */
        fun setPosition(position: ChatPosition) {
            val rest = positions.filterNot { sameList(it.list, position.list) }
            positions = if (position.order == 0L) rest else rest + position
        }

        private fun sameList(a: ChatList, b: ChatList): Boolean = when (a) {
            is ChatListMain -> b is ChatListMain
            is ChatListArchive -> b is ChatListArchive
            is ChatListFolder -> b is ChatListFolder && b.chatFolderId == a.chatFolderId
            else -> a == b
        }
    }
}

/**
 * Anteprima testuale di un messaggio (una riga, localizzata). Usata dalla lista chat e riusabile
 * dalla schermata chat (es. citazioni, notifiche).
 */
object MessagePreview {
    private const val MAX_CHARS = 160
    private val whitespace = Regex("\\s+")

    /** Versione comoda che usa le risorse dell'app. */
    fun of(content: MessageContent): String = of(content, MorsegramWearApp.instance.resources)

    fun of(content: MessageContent, res: Resources): String = when (content) {
        is MessageText -> collapse(content.text.text)
        is MessagePhoto -> withCaption(res, R.string.preview_photo, R.string.preview_photo_caption, content.caption?.text)
        is MessageVideo -> withCaption(res, R.string.preview_video, R.string.preview_video_caption, content.caption?.text)
        is MessageAnimation -> withCaption(res, R.string.preview_gif, R.string.preview_gif_caption, content.caption?.text)
        is MessageVoiceNote -> res.getString(R.string.preview_voice, formatDuration(content.voiceNote.duration))
        is MessageVideoNote -> res.getString(R.string.preview_video_note, formatDuration(content.videoNote.duration))
        is MessageSticker -> res.getString(R.string.preview_sticker, content.sticker.emoji.trim()).trim()
        is MessageAnimatedEmoji -> content.emoji
        is MessageDice -> content.emoji
        is MessageAudio -> {
            val a = content.audio
            val title = listOf(a.performer.trim(), a.title.trim()).filter { it.isNotEmpty() }.joinToString(" – ")
            if (title.isNotEmpty()) res.getString(R.string.preview_audio_titled, title) else res.getString(R.string.preview_audio)
        }
        is MessageDocument -> {
            val name = content.document.fileName.trim()
            if (name.isNotEmpty()) res.getString(R.string.preview_document_named, name) else res.getString(R.string.preview_document)
        }
        is MessageContact -> {
            val c = content.contact
            val name = listOf(c.firstName.trim(), c.lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            res.getString(R.string.preview_contact, name.ifEmpty { c.phoneNumber })
        }
        is MessageLocation -> res.getString(R.string.preview_location)
        is MessageLiveLocation -> res.getString(R.string.preview_live_location)
        is MessageVenue -> res.getString(R.string.preview_venue, content.venue.title)
        is MessagePoll -> res.getString(R.string.preview_poll, collapse(content.poll.question.text))
        is MessageCall -> res.getString(if (content.isVideo) R.string.preview_video_call else R.string.preview_call)
        is MessageGame -> res.getString(R.string.preview_game, content.game.title)
        is MessageStory -> res.getString(R.string.preview_story)
        is MessagePaidMedia -> res.getString(R.string.preview_paid_media)
        is MessageInvoice -> res.getString(R.string.preview_invoice, content.productInfo.title)
        is MessageChecklist -> res.getString(R.string.preview_checklist, collapse(content.list.title.text))
        is MessageGift, is MessageGiftedPremium -> res.getString(R.string.preview_gift)
        is MessageExpiredPhoto, is MessageExpiredVideo, is MessageExpiredVoiceNote, is MessageExpiredVideoNote ->
            res.getString(R.string.preview_expired)
        is MessageUnsupported -> res.getString(R.string.preview_unsupported)
        is MessagePinMessage -> res.getString(R.string.preview_pinned)
        is MessageChatAddMembers -> res.getString(R.string.preview_members_added)
        is MessageChatJoinByLink, is MessageChatJoinByRequest -> res.getString(R.string.preview_member_joined)
        is MessageChatDeleteMember -> res.getString(R.string.preview_member_left)
        is MessageChatChangeTitle -> res.getString(R.string.preview_title_changed)
        is MessageChatChangePhoto, is MessageChatDeletePhoto -> res.getString(R.string.preview_photo_changed)
        is MessageBasicGroupChatCreate, is MessageSupergroupChatCreate -> res.getString(R.string.preview_group_created)
        is MessageVideoChatStarted -> res.getString(R.string.preview_video_chat_started)
        is MessageVideoChatEnded -> res.getString(R.string.preview_video_chat_ended)
        is MessageContactRegistered -> res.getString(R.string.preview_contact_registered)
        else -> res.getString(R.string.preview_service)
    }

    /** "0:12" / "1:05:03". */
    fun formatDuration(seconds: Int): String {
        val s = maxOf(0, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Una riga sola, spazi compressi, al massimo [MAX_CHARS] caratteri. */
    fun collapse(text: String): String {
        val t = text.replace(whitespace, " ").trim()
        return if (t.length > MAX_CHARS) t.substring(0, MAX_CHARS) else t
    }

    private fun withCaption(res: Resources, plain: Int, withCaption: Int, caption: String?): String {
        val c = caption?.let { collapse(it) }.orEmpty()
        return if (c.isEmpty()) res.getString(plain) else res.getString(withCaption, c)
    }
}
