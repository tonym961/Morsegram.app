package it.iotatec.morsegram.wear.auth

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rendering del link di login (`tg://login?token=…`) come QR ad alto contrasto, nero su bianco. */
object QrCode {

    /**
     * Codifica [content] in un QR quadrato di [sizePx] pixel (quiet zone di [marginModules] moduli
     * inclusa). Livello di correzione L: meno moduli → moduli più grandi, più facili da inquadrare
     * su uno schermo piccolo. Lavora su [Dispatchers.Default].
     */
    suspend fun render(content: String, sizePx: Int, marginModules: Int = 1): ImageBitmap =
        withContext(Dispatchers.Default) {
            val size = sizePx.coerceAtLeast(21)
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to marginModules,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
}
