package it.iotatec.morsegram.wear.chat

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Player dei messaggi vocali su [MediaPlayer]: audio USAGE_MEDIA / CONTENT_TYPE_SPEECH, quindi
 * esce dall'altoparlante dell'orologio o dalle cuffie Bluetooth collegate (routing di sistema).
 * Un solo vocale alla volta; [state] espone messaggio corrente, posizione e durata.
 *
 * Confinato al main thread (MediaPlayer consegna i callback sul Looper del thread che lo crea).
 */
class VoicePlayer(private val scope: CoroutineScope) {

    data class State(
        val messageId: Long = 0L,
        val isPlaying: Boolean = false,
        val isPreparing: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
    ) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    /** Emette quando la riproduzione fallisce (file corrotto, codec mancante). */
    val errors: SharedFlow<Unit> = _errors.asSharedFlow()

    private var player: MediaPlayer? = null
    private var currentId = 0L
    private var ticker: Job? = null

    /** Play/pausa del vocale [messageId] dal file locale [path]; cambiare messaggio ferma il precedente. */
    @MainThread
    fun toggle(messageId: Long, path: String) {
        val p = player
        if (p != null && currentId == messageId) {
            if (_state.value.isPlaying) pause() else resume()
            return
        }
        stop()
        currentId = messageId
        _state.value = State(messageId = messageId, isPreparing = true)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(path)
            mp.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                try {
                    prepared.start()
                    _state.update { it.copy(isPlaying = true, isPreparing = false, durationMs = prepared.duration.coerceAtLeast(0)) }
                    startTicker()
                } catch (e: IllegalStateException) {
                    fail(e)
                }
            }
            mp.setOnCompletionListener { done ->
                if (player !== done) return@setOnCompletionListener
                stopTicker()
                _state.update { it.copy(isPlaying = false, positionMs = 0) }
                runCatching { done.seekTo(0) }
            }
            mp.setOnErrorListener { errored, what, extra ->
                if (player === errored) {
                    Log.w(TAG, "MediaPlayer error $what/$extra")
                    fail(null)
                }
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            runCatching { mp.reset() }
            mp.release()
            player = null
            fail(e)
        }
    }

    /** Mette in pausa (no-op se non sta suonando). Da chiamare anche quando la schermata esce. */
    @MainThread
    fun pause() {
        val p = player ?: return
        try {
            if (p.isPlaying) p.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pause: ${e.message}")
        }
        stopTicker()
        _state.update { it.copy(isPlaying = false, positionMs = currentPositionSafe(p)) }
    }

    @MainThread
    private fun resume() {
        val p = player ?: return
        try {
            p.start()
            _state.update { it.copy(isPlaying = true, isPreparing = false) }
            startTicker()
        } catch (e: IllegalStateException) {
            fail(e)
        }
    }

    /** Ferma e rilascia il MediaPlayer corrente. */
    @MainThread
    fun stop() {
        stopTicker()
        player?.let { p ->
            runCatching { p.reset() }
            p.release()
        }
        player = null
        currentId = 0L
        _state.value = State()
    }

    @MainThread
    fun release() = stop()

    private fun fail(e: Exception?) {
        if (e != null) Log.w(TAG, "playback: ${e.message}")
        stop()
        _errors.tryEmit(Unit)
    }

    private fun currentPositionSafe(p: MediaPlayer): Int = try { p.currentPosition.coerceAtLeast(0) } catch (e: IllegalStateException) { 0 }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val p = player ?: break
                _state.update { it.copy(positionMs = currentPositionSafe(p)) }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    companion object {
        private const val TAG = "VoicePlayer"
        private const val TICK_MS = 200L

        /** Stato "nessun vocale": da passare alle righe che non sono quella in riproduzione. */
        val Idle = State()
    }
}
