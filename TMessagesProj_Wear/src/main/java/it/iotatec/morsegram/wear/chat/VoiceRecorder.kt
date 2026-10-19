package it.iotatec.morsegram.wear.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Registratore di messaggi vocali per Telegram: OGG/Opus, 48 kHz, mono, 32 kbps (API 29+).
 * Nessun fallback AAC: un vocale Telegram deve essere Opus in contenitore OGG, quindi se il
 * device non sa codificarlo restituiamo [UnsupportedFormatException] e l'interfaccia lo dice.
 *
 * Thread: i metodi non sono thread-safe tra loro; il ViewModel li chiama in sequenza da una
 * coroutine su IO. [sampleAmplitude] va chiamato periodicamente (~100 ms) durante la
 * registrazione per costruire la forma d'onda che TDLib mostra nella bolla.
 */
class VoiceRecorder(context: Context) {

    class UnsupportedFormatException(cause: Throwable?) : Exception("OGG/Opus non supportato", cause)
    class TooShortException : Exception("registrazione troppo breve")

    class Recording(val file: File, val durationSeconds: Int, val waveform: ByteArray)

    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var startedAtMs = 0L
    private val amplitudes = ArrayList<Int>(MAX_SAMPLES)

    val isRecording: Boolean get() = recorder != null

    /** Millisecondi trascorsi dall'inizio della registrazione corrente (0 se ferma). */
    val elapsedMs: Long get() = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAtMs

    /** Avvia una nuova registrazione in `filesDir/voice/<ts>.ogg`. */
    fun start(): Result<Unit> {
        if (recorder != null) return Result.success(Unit)
        val dir = File(appContext.filesDir, DIR).apply { mkdirs() }
        cleanupOld(dir)
        val file = File(dir, "${System.currentTimeMillis()}.ogg")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setMaxDuration(MAX_DURATION_MS + 5_000) // il ViewModel ferma prima: il recorder non deve auto-fermarsi
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outFile = file
            startedAtMs = SystemClock.elapsedRealtime()
            amplitudes.clear()
            Result.success(Unit)
        } catch (e: Exception) {
            // IOException/IllegalStateException/RuntimeException: su questo device OGG/Opus non parte
            Log.w(TAG, "start: ${e.message}")
            runCatching { r.reset() }
            r.release()
            file.delete()
            // SecurityException = permesso microfono mancante (errore generico); il resto è il codec
            Result.failure(if (e is SecurityException) e else UnsupportedFormatException(e))
        }
    }

    /** Campiona l'ampiezza massima dall'ultima chiamata (per la forma d'onda). */
    fun sampleAmplitude() {
        val r = recorder ?: return
        if (amplitudes.size >= MAX_SAMPLES) return
        val amp = try { r.maxAmplitude } catch (e: Exception) { 0 }
        amplitudes.add(amp.coerceAtLeast(0))
    }

    /** Ferma e restituisce il file pronto da inviare; [TooShortException] sotto il secondo scarso. */
    fun stop(): Result<Recording> {
        val r = recorder ?: return Result.failure(IllegalStateException("nessuna registrazione"))
        val file = outFile
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        recorder = null
        outFile = null
        val stopped = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // stop() lancia se non è stato scritto nulla di valido
            Log.w(TAG, "stop: ${e.message}")
            false
        } finally {
            r.release()
        }
        if (file == null || !stopped || !file.isFile || file.length() == 0L) {
            file?.delete()
            return Result.failure(if (elapsed < MIN_DURATION_MS) TooShortException() else IOException("file vocale vuoto"))
        }
        if (elapsed < MIN_DURATION_MS) {
            file.delete()
            return Result.failure(TooShortException())
        }
        val duration = ((elapsed + 500L) / 1000L).toInt().coerceAtLeast(1)
        return Result.success(Recording(file, duration, buildWaveform(amplitudes)))
    }

    /** Annulla la registrazione corrente e cancella il file. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (e: RuntimeException) { /* niente di scritto */ } finally { r.release() }
        outFile?.delete()
        outFile = null
        amplitudes.clear()
    }

    fun release() = cancel()

    /** Elimina i vocali più vecchi di un giorno: TDLib ha già copiato quelli inviati nel suo storage. */
    private fun cleanupOld(dir: File) {
        val threshold = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < threshold) f.delete() }
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val DIR = "voice"
        private const val SAMPLE_RATE = 48_000
        private const val BIT_RATE = 32_000
        /** Sotto questa durata [stop] fallisce con [TooShortException] (il ViewModel la usa per decidere se inviare). */
        const val MIN_DURATION_MS = 700L
        const val MAX_DURATION_MS = 10 * 60 * 1000
        private const val MAX_SAMPLES = 6_000 // 10 min a 100 ms
        const val WAVEFORM_SAMPLES = 100
        const val WAVEFORM_BYTES = 63 // 100 valori a 5 bit = 500 bit

        /**
         * Forma d'onda TDLib: 100 valori a 5 bit (0..31) impaccati LSB-first in 63 byte, come fa
         * l'app ufficiale. Le ampiezze (0..32767 da MediaRecorder) vengono ricampionate a 100
         * segmenti (max per segmento) e normalizzate sul picco della registrazione.
         */
        fun buildWaveform(amplitudes: List<Int>): ByteArray {
            val values = IntArray(WAVEFORM_SAMPLES)
            if (amplitudes.isNotEmpty()) {
                val peak = amplitudes.maxOrNull() ?: 0
                if (peak > 0) {
                    val n = amplitudes.size
                    for (i in 0 until WAVEFORM_SAMPLES) {
                        val from = i * n / WAVEFORM_SAMPLES
                        val to = ((i + 1) * n / WAVEFORM_SAMPLES).coerceAtLeast(from + 1).coerceAtMost(n)
                        var m = 0
                        for (j in from until to) if (amplitudes[j] > m) m = amplitudes[j]
                        values[i] = (m.toLong() * 31L / peak).toInt().coerceIn(0, 31)
                    }
                }
            }
            return packWaveform(values)
        }

        /** Impacca valori 0..31 a 5 bit ciascuno, little-endian per bit (set_bits dell'app ufficiale). */
        fun packWaveform(values: IntArray): ByteArray {
            val out = ByteArray(WAVEFORM_BYTES)
            for (i in 0 until minOf(values.size, WAVEFORM_SAMPLES)) {
                val v = values[i].coerceIn(0, 31)
                val bit = i * 5
                val index = bit / 8
                val shift = bit % 8
                val packed = v shl shift
                out[index] = (out[index].toInt() or (packed and 0xFF)).toByte()
                if (index + 1 < out.size) out[index + 1] = (out[index + 1].toInt() or ((packed shr 8) and 0xFF)).toByte()
            }
            return out
        }
    }
}
