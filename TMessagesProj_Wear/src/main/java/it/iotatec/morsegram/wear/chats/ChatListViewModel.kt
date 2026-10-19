package it.iotatec.morsegram.wear.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.g000sha256.tdl.dto.ConnectionState
import it.iotatec.morsegram.wear.tdlib.Tdlib
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel della lista chat: espone gli StateFlow di [ChatRepository] (che vive quanto il
 * processo) e lega le richieste di caricamento al [viewModelScope].
 */
class ChatListViewModel : ViewModel() {
    init { ChatRepository.start() }

    val chats: StateFlow<List<ChatItem>> = ChatRepository.chats
    val allLoaded: StateFlow<Boolean> = ChatRepository.allLoaded
    val isLoading: StateFlow<Boolean> = ChatRepository.isLoading
    val loadFailed: StateFlow<Boolean> = ChatRepository.loadFailed
    val connectionState: StateFlow<ConnectionState?> = Tdlib.connectionState

    /** Pagina successiva (no-op se già in corso o tutto caricato). */
    fun loadMore() {
        viewModelScope.launch { ChatRepository.loadMore() }
    }
}
