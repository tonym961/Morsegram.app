package it.iotatec.morsegram.wear.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import dev.g000sha256.tdl.dto.File as TdFile
import dev.g000sha256.tdl.dto.Photo
import dev.g000sha256.tdl.dto.PhotoSize
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utilità media della chat: decodifica bitmap ridotte (con cache), scelta della PhotoSize,
 * formattazione di durate/dimensioni/orari e le due icone vettoriali che material-icons-core
 * non include (microfono e pausa).
 */

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Orario "HH:mm" locale da un timestamp unix in secondi (TDLib `Message.date`). */
fun formatTime(unixSeconds: Int): String =
    Instant.ofEpochSecond(unixSeconds.toLong()).atZone(ZoneId.systemDefault()).format(timeFormatter)

/** Durata "m:ss" (o "h:mm:ss" oltre l'ora). */
fun formatDuration(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

/** Durata da millisecondi, arrotondata al secondo (usata dal timer di registrazione e dal player). */
fun formatDurationMs(ms: Long): String = formatDuration(((ms + 500) / 1000).toInt())

/** Dimensione leggibile ("12 KB", "3,4 MB"). */
fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** Dimensione nota del file (0 se sconosciuta): `size` quando c'è, altrimenti `expectedSize`. */
fun TdFile.knownSize(): Long = if (size > 0L) size else expectedSize

/** Frazione scaricata 0..1, o null se non è in download/non è nota. */
fun TdFile.downloadFraction(): Float? {
    val total = knownSize()
    if (!local.isDownloadingActive || total <= 0L) return null
    return (local.downloadedSize.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * La PhotoSize più piccola che copre [targetPx] sul lato lungo; se nessuna è abbastanza grande,
 * la più grande disponibile. Le PhotoSize arrivano ordinate per dimensione crescente, ma non ci
 * fidiamo e ordiniamo comunque.
 */
fun pickPhotoSize(photo: Photo, targetPx: Int): PhotoSize? {
    val sizes = photo.sizes.sortedBy { maxOf(it.width, it.height) }
    if (sizes.isEmpty()) return null
    return sizes.firstOrNull { maxOf(it.width, it.height) >= targetPx } ?: sizes.last()
}

/** Cache LRU di bitmap decodificate (max ~6 MB: l'orologio ha poca RAM). */
private object BitmapCache {
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
}

/**
 * Decodifica un file immagine locale con `inSampleSize` in modo che il lato lungo sia ~[targetPx].
 * Restituisce null se il file manca o non è decodificabile. Da chiamare fuori dal main thread.
 */
fun decodeScaledBitmap(path: String, targetPx: Int): Bitmap? {
    val key = "$path@$targetPx"
    BitmapCache.get(key)?.let { return it }
    val file = File(path)
    if (!file.isFile || file.length() == 0L) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= targetPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565.takeIf { !bounds.outMimeType.orEmpty().contains("webp") && !bounds.outMimeType.orEmpty().contains("png") }
                ?: Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, opts)?.also { BitmapCache.put(key, it) }
    } catch (e: Throwable) {
        // OutOfMemoryError o file corrotto: meglio il placeholder di un crash
        null
    }
}

/** Decodifica i byte JPEG di una `Minithumbnail` (piccolissima, nessun sampling). */
fun decodeMinithumbnail(data: ByteArray?): Bitmap? {
    if (data == null || data.isEmpty()) return null
    return try { BitmapFactory.decodeByteArray(data, 0, data.size) } catch (e: Throwable) { null }
}

/**
 * Bitmap locale ricordata per il composable: decodifica su IO quando [path] cambia, null finché
 * non è pronta (o se il file non è decodificabile).
 */
@Composable
fun rememberLocalBitmap(path: String?, targetPx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, key1 = path, key2 = targetPx) {
        value = if (path.isNullOrEmpty()) null
        else withContext(Dispatchers.IO) { decodeScaledBitmap(path, targetPx)?.asImageBitmap() }
    }

/** Bitmap da una minithumbnail (byte inline nel messaggio): nessun I/O, ma decodifica su Default. */
@Composable
fun rememberMinithumbnail(data: ByteArray?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, key1 = data) {
        value = if (data == null) null else withContext(Dispatchers.Default) { decodeMinithumbnail(data)?.asImageBitmap() }
    }

/** Icone che material-icons-core non contiene, costruite dai path Material standard. */
object ChatIcons {
    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.White))
            .build()

    val Mic: ImageVector by lazy {
        icon(
            "Mic",
            "M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z",
        )
    }

    val Pause: ImageVector by lazy { icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z") }

    val Keyboard: ImageVector by lazy {
        icon(
            "Keyboard",
            "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z",
        )
    }
}
