package it.iotatec.morsegram.wear.chat

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatActionCancel
import dev.g000sha256.tdl.dto.ChatActionRecordingVoiceNote
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.File as TdFile
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.InputMessageVoiceNote
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.MessageSender
import dev.g000sha256.tdl.dto.MessageSenderChat
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.MessageSourceChatHistory
import dev.g000sha256.tdl.dto.MessageVideoNote
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.Update
import dev.g000sha256.tdl.dto.UpdateChatReadOutbox
import dev.g000sha256.tdl.dto.UpdateChatTitle
import dev.g000sha256.tdl.dto.UpdateDeleteMessages
import dev.g000sha256.tdl.dto.UpdateFile
import dev.g000sha256.tdl.dto.UpdateMessageContent
import dev.g000sha256.tdl.dto.UpdateMessageContentOpened
import dev.g000sha256.tdl.dto.UpdateMessageEdited
import dev.g000sha256.tdl.dto.UpdateMessageSendFailed
import dev.g000sha256.tdl.dto.UpdateMessageSendSucceeded
import dev.g000sha256.tdl.dto.UpdateNewMessage
import dev.g000sha256.tdl.dto.UpdateUser
import dev.g000sha256.tdl.dto.User
import it.iotatec.morsegram.wear.MorsegramWearApp
import it.iotatec.morsegram.wear.tdlib.Tdlib
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stato di una chat aperta sull'orologio.
 *
 * - `openChat` all'avvio, `closeChat` in [onCleared] (TDLib così sa quando aggiornare la chat).
 * - Storico con `getChatHistory` a pagine finché non ci sono almeno [INITIAL_TARGET] messaggi.
 * - Segue gli update TDLib per nuovi messaggi, invii, modifiche, cancellazioni, ricevute di
 *   lettura in uscita e progresso dei download.
 * - Ricevute di lettura in entrata: [onMessagesVisible] chiama `viewMessages` per i messaggi
 *   ricevuti realmente mostrati (obbligatorio per i termini dell'API Telegram: niente ghost mode).
 * - Ricevuta "ascoltato": [playVoice] chiama `openMessageContent` la prima volta che un vocale
 *   ricevuto viene riprodotto (stessa regola: nessuna ricevuta va soppressa).
 *
 * Tutte le chiamate TDLib girano su [Dispatchers.Default]; lo stato esce da StateFlow.
 */
class ChatViewModel(val chatId: Long) : ViewModel() {

    /** Messaggio più il contenuto corrente (gli `UpdateMessageContent` sostituiscono solo questo). */
    @Immutable
    class ChatMessage(val message: Message, val content: MessageContent) {
        val id: Long get() = message.id
    }

    enum class UiError { SEND_FAILED, RECORD_UNSUPPORTED, RECORD_FAILED, RECORD_CANCELLED, TOO_SHORT, PLAY_FAILED }

    data class RecordingState(val active: Boolean = false, val elapsedMs: Long = 0L)

    private val mutex = Mutex()
    private val byId = HashMap<Long, ChatMessage>()
    private val viewedIds = HashSet<Long>()
    private var hasMoreHistory = true

    private val trackedFiles: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    private val requestedSenders: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    /** Messaggi per cui `openMessageContent` è già partita (una sola richiesta per messaggio). */
    private val openedIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    @Volatile private var pendingVoiceMessageId = 0L
    private var recordJob: Job? = null
    /** Rinnovo periodico di `chatActionRecordingVoiceNote` (vedi [startRecording]); vive quanto la registrazione. */
    private var chatActionJob: Job? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** Messaggi in ordine crescente di id (il più recente in fondo). */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _chat = MutableStateFlow<Chat?>(null)
    val chat: StateFlow<Chat?> = _chat.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /** Vero per gruppi e supergruppi non canale: lì mostriamo il nome del mittente. */
    val isGroup: StateFlow<Boolean> = _chat.map { c ->
        val t = c?.type
        t is ChatTypeBasicGroup || (t is ChatTypeSupergroup && !t.isChannel)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _senderNames = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** Nomi dei mittenti: chiave userId (positivo) o chatId del canale/gruppo mittente (negativo). */
    val senderNames: StateFlow<Map<Long, String>> = _senderNames.asStateFlow()

    private val _lastReadOutbox = MutableStateFlow(0L)
    /** Ultimo messaggio in uscita letto dall'altra parte (per le doppie spunte). */
    val lastReadOutbox: StateFlow<Long> = _lastReadOutbox.asStateFlow()

    private val _files = MutableStateFlow<Map<Int, TdFile>>(emptyMap())
    /** Stato aggiornato dei file di cui abbiamo chiesto il download, per id. */
    val files: StateFlow<Map<Int, TdFile>> = _files.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    private val _recording = MutableStateFlow(RecordingState())
    val recording: StateFlow<RecordingState> = _recording.asStateFlow()

    private val _errors = MutableSharedFlow<UiError>(extraBufferCapacity = 4)
    val errors: SharedFlow<UiError> = _errors.asSharedFlow()

    private val _scrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Evento: un nostro messaggio è stato inviato, la lista deve scendere in fondo. */
    val scrollToBottom: SharedFlow<Unit> = _scrollToBottom.asSharedFlow()

    val player = VoicePlayer(viewModelScope)
    private val recorder = VoiceRecorder(MorsegramWearApp.instance)

    init {
        viewModelScope.launch(Dispatchers.Default) { Tdlib.updates.collect { onUpdate(it) } }
        viewModelScope.launch(Dispatchers.Default) {
            Tdlib.req { openChat(chatId) }.onFailure { Log.w(TAG, "openChat: ${it.message}") }
            Tdlib.req { getChat(chatId) }.onSuccess { c ->
                _chat.value = c
                _title.value = c.title
                _lastReadOutbox.value = c.lastReadOutboxMessageId
            }
            loadInitialHistory()
            _loading.value = false
        }
        viewModelScope.launch { player.errors.collect { _errors.tryEmit(UiError.PLAY_FAILED) } }
    }

    override fun onCleared() {
        val job = recordJob
        recordJob = null
        job?.cancel()
        chatActionJob?.cancel()
        chatActionJob = null
        val wasActive = _recording.value.active
        _recording.value = RecordingState()
        player.release()
        // viewModelScope è già cancellato qui: recorder e closeChat vanno sullo scope dell'app.
        // MediaRecorder.stop()/release() bloccano: stesso schema join-poi-cancel di cancelRecording(), su IO.
        Tdlib.scope.launch(Dispatchers.IO) {
            job?.join()
            recorder.cancel()
            if (wasActive) Tdlib.req { sendChatAction(chatId, null, "", ChatActionCancel()) }
            Tdlib.req { closeChat(chatId) }.onFailure { Log.w(TAG, "closeChat: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- storico

    /**
     * Prima pagina: TDLib spesso risponde con meno messaggi di `limit` (solo quelli in cache),
     * quindi si ripete da `fromMessageId = id più vecchio` finché non ne abbiamo almeno
     * [INITIAL_TARGET] o la risposta non porta nulla di nuovo. Con offset 0 la pagina include
     * anche `fromMessageId` stesso: contiamo solo i messaggi davvero nuovi.
     */
    private suspend fun loadInitialHistory() {
        var from = 0L
        var rounds = 0
        while (rounds < MAX_INITIAL_ROUNDS) {
            rounds++
            val batch = Tdlib.req { getChatHistory(chatId, from, 0, PAGE, false) }
                .onFailure { Log.w(TAG, "getChatHistory: ${it.message}") }
                .getOrNull()?.messages?.filterNotNull() ?: emptyList()
            if (batch.isEmpty()) {
                if (from != 0L) hasMoreHistory = false
                break
            }
            val added = addAll(batch)
            from = batch.minOf { it.id }
            if (added == 0) {
                hasMoreHistory = false
                break
            }
            if (mutex.withLock { byId.size } >= INITIAL_TARGET) break
        }
    }

    /** Carica una pagina di messaggi più vecchi (quando l'utente arriva in cima). */
    fun loadOlder() {
        if (_loading.value || _loadingOlder.value || !hasMoreHistory) return
        _loadingOlder.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val from = mutex.withLock { byId.keys.minOrNull() ?: 0L }
                if (from == 0L) return@launch
                val batch = Tdlib.req { getChatHistory(chatId, from, 0, PAGE, false) }
                    .onFailure { Log.w(TAG, "getChatHistory(older): ${it.message}") }
                    .getOrNull()?.messages?.filterNotNull() ?: emptyList()
                if (batch.isEmpty() || addAll(batch) == 0) hasMoreHistory = false
            } finally {
                _loadingOlder.value = false
            }
        }
    }

    // ---------------------------------------------------------------- update TDLib

    private suspend fun onUpdate(u: Update) {
        when (u) {
            is UpdateNewMessage -> if (u.message.chatId == chatId) {
                mutate { put(u.message) }
                if (u.message.isOutgoing) _scrollToBottom.tryEmit(Unit)
            }
            is UpdateMessageSendSucceeded -> if (u.message.chatId == chatId) mutate {
                byId.remove(u.oldMessageId)
                put(u.message)
            }
            is UpdateMessageSendFailed -> if (u.message.chatId == chatId) {
                mutate {
                    byId.remove(u.oldMessageId)
                    put(u.message)
                }
                _errors.tryEmit(UiError.SEND_FAILED)
            }
            is UpdateMessageContent -> if (u.chatId == chatId) mutate {
                byId[u.messageId]?.let { byId[u.messageId] = ChatMessage(it.message, u.newContent) }
            }
            // contenuto aperto (vocale ascoltato / videomessaggio visto): aggiorniamo il flag locale
            is UpdateMessageContentOpened -> if (u.chatId == chatId) mutate {
                val cm = byId[u.messageId]
                val opened = cm?.content?.markedOpened()
                if (cm != null && opened != null) byId[u.messageId] = ChatMessage(cm.message, opened)
            }
            is UpdateMessageEdited -> if (u.chatId == chatId) {
                val exists = mutex.withLock { byId.containsKey(u.messageId) }
                // mai sospendere sulla rete dentro il collector condiviso di Tdlib.updates
                if (exists) viewModelScope.launch(Dispatchers.Default) {
                    Tdlib.req { getMessage(chatId, u.messageId) }.onSuccess { m -> mutate { put(m) } }
                }
            }
            is UpdateDeleteMessages -> if (u.chatId == chatId && !u.fromCache) mutate {
                u.messageIds.forEach { byId.remove(it) }
            }
            is UpdateChatReadOutbox -> if (u.chatId == chatId) _lastReadOutbox.value = u.lastReadOutboxMessageId
            is UpdateChatTitle -> if (u.chatId == chatId) _title.value = u.title
            is UpdateFile -> onFileUpdate(u.file)
            is UpdateUser -> if (_senderNames.value.containsKey(u.user.id)) {
                _senderNames.update { it + (u.user.id to u.user.displayName()) }
            }
            else -> Unit
        }
    }

    private suspend fun onFileUpdate(f: TdFile) {
        if (f.id !in trackedFiles && !_files.value.containsKey(f.id)) return
        _files.update { it + (f.id to f) }
        if (!f.local.isDownloadingCompleted) return
        val pending = pendingVoiceMessageId
        if (pending == 0L) return
        val voice = mutex.withLock { (byId[pending]?.content as? MessageVoiceNote)?.voiceNote?.voice }
        if (voice != null && voice.id == f.id) {
            pendingVoiceMessageId = 0L
            withContext(Dispatchers.Main) { player.toggle(pending, f.local.path) }
        }
    }

    /** Applica [block] alla mappa sotto mutex e ripubblica la lista ordinata. */
    private suspend fun mutate(block: () -> Unit) = mutex.withLock {
        block()
        publishLocked()
    }

    /** Inserisce una pagina di storico; ritorna quanti messaggi non erano già presenti. */
    private suspend fun addAll(batch: List<Message>): Int = mutex.withLock {
        var added = 0
        for (m in batch) if (byId.put(m.id, ChatMessage(m, m.content)) == null) added++
        publishLocked()
        added
    }

    /** Da chiamare sotto [mutex]. */
    private fun put(m: Message) {
        byId[m.id] = ChatMessage(m, m.content)
    }

    /** Da chiamare sotto [mutex]. */
    private fun publishLocked() {
        _messages.value = byId.values.sortedBy { it.id }
    }

    /** Copia del contenuto con il flag "aperto" (ascoltato/visto) impostato; null se non cambia nulla. */
    private fun MessageContent.markedOpened(): MessageContent? = when (this) {
        is MessageVoiceNote -> if (isListened) null else MessageVoiceNote(voiceNote, caption, true)
        is MessageVideoNote -> if (isViewed) null else MessageVideoNote(videoNote, true, isSecret)
        else -> null
    }

    // ---------------------------------------------------------------- ricevute di lettura

    /**
     * Messaggi attualmente visibili sullo schermo: quelli ricevuti e non ancora segnalati vengono
     * passati a `viewMessages` (MessageSourceChatHistory, forceRead=false) in un'unica chiamata.
     */
    fun onMessagesVisible(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val toView = mutex.withLock {
                ids.filter { id ->
                    val m = byId[id]
                    m != null && !m.message.isOutgoing && id > 0L && id !in viewedIds
                }.also { viewedIds.addAll(it) }
            }
            if (toView.isEmpty()) return@launch
            val r = Tdlib.req { viewMessages(chatId, toView.toLongArray(), MessageSourceChatHistory(), false) }
            if (r.isFailure) {
                Log.w(TAG, "viewMessages: ${r.exceptionOrNull()?.message}")
                mutex.withLock { viewedIds.removeAll(toView.toSet()) }
            }
        }
    }

    // ---------------------------------------------------------------- mittenti

    /** Risolve (una volta) il nome del mittente per i gruppi; il risultato arriva in [senderNames]. */
    fun resolveSender(sender: MessageSender) {
        val key = when (sender) {
            is MessageSenderUser -> sender.userId
            is MessageSenderChat -> sender.chatId
            else -> return
        }
        if (_senderNames.value.containsKey(key) || !requestedSenders.add(key)) return
        viewModelScope.launch(Dispatchers.Default) {
            val name = when (sender) {
                is MessageSenderUser -> Tdlib.req { getUser(sender.userId) }.getOrNull()?.displayName()
                is MessageSenderChat -> Tdlib.req { getChat(sender.chatId) }.getOrNull()?.title
                else -> null
            }
            if (name.isNullOrBlank()) requestedSenders.remove(key)
            else _senderNames.update { it + (key to name) }
        }
    }

    // ---------------------------------------------------------------- file

    /** Avvia (una volta) il download di [file] se non è già locale; lo stato arriva in [files]. */
    fun ensureDownloaded(file: TdFile) {
        if (file.local.isDownloadingCompleted) {
            if (!_files.value.containsKey(file.id)) _files.update { it + (file.id to file) }
            return
        }
        if (!trackedFiles.add(file.id)) return
        viewModelScope.launch(Dispatchers.Default) {
            Tdlib.req { downloadFile(file.id, DOWNLOAD_PRIORITY, 0L, 0L, false) }
                .onSuccess { f -> _files.update { it + (f.id to f) } }
                .onFailure {
                    Log.w(TAG, "downloadFile ${file.id}: ${it.message}")
                    trackedFiles.remove(file.id)
                }
        }
    }

    /** File aggiornato (se seguito) o quello del messaggio. */
    fun currentFile(file: TdFile): TdFile = _files.value[file.id] ?: file

    // ---------------------------------------------------------------- vocali: riproduzione

    /** Play/pausa del vocale: se il file non è ancora locale lo scarica e parte appena completo. */
    fun playVoice(item: ChatMessage) {
        val content = item.content as? MessageVoiceNote ?: return
        // ricevuta "ascoltato" per il mittente: prima di avviare la riproduzione, una volta sola
        if (!content.isListened) markContentOpened(item)
        val f = currentFile(content.voiceNote.voice)
        if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
            pendingVoiceMessageId = 0L
            player.toggle(item.id, f.local.path)
        } else {
            pendingVoiceMessageId = item.id
            ensureDownloaded(f)
        }
    }

    /**
     * Segnala a TDLib che il contenuto di un messaggio ricevuto è stato aperto (vocale ascoltato):
     * TDLib manda al server la ricevuta e poi `updateMessageContentOpened`. Termini dell'API:
     * la ricevuta non va mai soppressa. Per i messaggi in uscita TDLib la ignora, quindi li saltiamo.
     */
    private fun markContentOpened(item: ChatMessage) {
        if (item.message.isOutgoing || item.id <= 0L || !openedIds.add(item.id)) return
        viewModelScope.launch(Dispatchers.Default) {
            Tdlib.req { openMessageContent(chatId, item.id) }.onFailure {
                Log.w(TAG, "openMessageContent ${item.id}: ${it.message}")
                openedIds.remove(item.id)
            }
        }
    }

    // ---------------------------------------------------------------- invio

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val content = InputMessageText(FormattedText(t, emptyArray()), null, false)
            Tdlib.req { sendMessage(chatId, null, null, null, null, content) }
                .onSuccess { m -> mutate { put(m) }; _scrollToBottom.tryEmit(Unit) }
                .onFailure {
                    Log.w(TAG, "sendMessage: ${it.message}")
                    _errors.tryEmit(UiError.SEND_FAILED)
                }
        }
    }

    // ---------------------------------------------------------------- vocali: registrazione

    /**
     * Avvia la registrazione (il permesso RECORD_AUDIO va chiesto prima, dalla UI).
     * Il job campiona l'ampiezza ogni 100 ms e aggiorna il timer; se viene cancellato prima che
     * la registrazione sia "armata" (stato attivo pubblicato) spegne lui stesso il microfono.
     */
    fun startRecording() {
        if (_recording.value.active || recordJob != null) return
        player.pause()
        recordJob = viewModelScope.launch {
            var armed = false
            var actionJob: Job? = null
            try {
                val started = withContext(Dispatchers.IO) { recorder.start() }
                val failure = started.exceptionOrNull()
                if (failure != null) {
                    _errors.tryEmit(if (failure is VoiceRecorder.UnsupportedFormatException) UiError.RECORD_UNSUPPORTED else UiError.RECORD_FAILED)
                    return@launch
                }
                val startedAt = SystemClock.elapsedRealtime()
                armed = true
                _recording.value = RecordingState(active = true, elapsedMs = 0L)
                // Non figlio di questo job: lo stop fa join() sul campionamento e non deve aspettare la rete.
                // Lato server l'azione "sta registrando" scade dopo ~5 s: va rinnovata finché si registra
                // (il job viene cancellato nel finally, cioè a ogni uscita dalla registrazione).
                chatActionJob?.cancel()
                actionJob = viewModelScope.launch(Dispatchers.Default) {
                    while (isActive) {
                        Tdlib.req { sendChatAction(chatId, null, "", ChatActionRecordingVoiceNote()) }
                        delay(CHAT_ACTION_PERIOD_MS)
                    }
                }
                chatActionJob = actionJob
                withContext(Dispatchers.IO) {
                    while (isActive) {
                        delay(SAMPLE_PERIOD_MS)
                        recorder.sampleAmplitude()
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        _recording.update { it.copy(elapsedMs = elapsed) }
                        if (elapsed >= VoiceRecorder.MAX_DURATION_MS) {
                            // limite raggiunto: chiudiamo e inviamo come se l'utente avesse premuto Invia
                            withContext(Dispatchers.Main) { stopRecordingAndSend() }
                            break
                        }
                    }
                }
            } finally {
                actionJob?.cancel()
                if (chatActionJob === actionJob) chatActionJob = null
                if (recordJob === coroutineContext[Job]) recordJob = null
                if (!armed) withContext(NonCancellable + Dispatchers.IO) { recorder.cancel() }
            }
        }
    }

    /** Ferma la registrazione e invia il vocale. */
    fun stopRecordingAndSend() {
        val job = recordJob ?: return
        recordJob = null
        job.cancel()
        if (!_recording.value.active) return
        _recording.value = RecordingState()
        viewModelScope.launch(Dispatchers.IO) {
            job.join() // il campionamento è finito: ora il recorder è solo nostro
            val rec = recorder.stop().getOrElse { e ->
                _errors.tryEmit(if (e is VoiceRecorder.TooShortException) UiError.TOO_SHORT else UiError.RECORD_FAILED)
                Tdlib.req { sendChatAction(chatId, null, "", ChatActionCancel()) }
                return@launch
            }
            val content = InputMessageVoiceNote(InputFileLocal(rec.file.absolutePath), rec.durationSeconds, rec.waveform, null, null)
            Tdlib.req { sendMessage(chatId, null, null, null, null, content) }
                .onSuccess { m -> mutate { put(m) }; _scrollToBottom.tryEmit(Unit) }
                .onFailure {
                    Log.w(TAG, "sendMessage(voice): ${it.message}")
                    _errors.tryEmit(UiError.SEND_FAILED)
                    rec.file.delete()
                }
        }
    }

    /** Annulla la registrazione senza inviare. */
    fun cancelRecording() {
        val job = recordJob ?: return
        recordJob = null
        job.cancel()
        val wasActive = _recording.value.active
        _recording.value = RecordingState()
        viewModelScope.launch(Dispatchers.IO) {
            job.join()
            recorder.cancel()
            if (wasActive) Tdlib.req { sendChatAction(chatId, null, "", ChatActionCancel()) }
        }
    }

    /**
     * La schermata va in pausa (schermo spento per timeout, polso giù, altra app) e l'activity
     * perde il microfono: un vocale già abbastanza lungo da essere valido viene inviato, uno
     * troppo breve viene annullato avvisando l'utente. Così nulla si perde in silenzio.
     * Ferma anche la riproduzione.
     */
    fun onScreenPaused() {
        player.pause()
        if (recordJob == null) return
        val rec = _recording.value
        if (rec.active && rec.elapsedMs >= VoiceRecorder.MIN_DURATION_MS) {
            stopRecordingAndSend()
        } else {
            cancelRecording()
            _errors.tryEmit(UiError.RECORD_CANCELLED)
        }
    }

    private fun User.displayName(): String =
        listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "#$id" }

    private companion object {
        const val TAG = "ChatViewModel"
        const val PAGE = 30
        const val INITIAL_TARGET = 20
        const val MAX_INITIAL_ROUNDS = 6
        const val DOWNLOAD_PRIORITY = 16
        const val SAMPLE_PERIOD_MS = 100L
        /** Le chat action di Telegram scadono dopo ~5 s: rinnovo con lo stesso periodo. */
        const val CHAT_ACTION_PERIOD_MS = 5_000L
    }
}
