package it.iotatec.morsegram.wear.chats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text

/** Tavolozza degli avatar senza foto (stessi toni dei client ufficiali). */
val AvatarPalette: List<Color> = listOf(
    Color(0xFFE17076), Color(0xFFFAA774), Color(0xFFA695E7), Color(0xFF7BC862),
    Color(0xFF6EC9CB), Color(0xFF65AADD), Color(0xFFEE7AAE),
)

private val OnlineGreen = Color(0xFF4DCD5E)

/** Indice stabile nella [AvatarPalette] derivato dall'id della chat/utente. */
fun avatarColorIndex(id: Long): Int = ((id % AvatarPalette.size + AvatarPalette.size) % AvatarPalette.size).toInt()

/** Iniziali (max 2) delle prime due parole del titolo; sicure con emoji e caratteri non BMP. */
fun initialsOf(title: String): String {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val sb = StringBuilder()
    for (w in words) {
        if (sb.length >= 2) break
        val cp = w.codePointAt(0)
        if (!Character.isLetterOrDigit(cp) && words.size > 1) continue
        sb.append(String(Character.toChars(cp)).uppercase())
    }
    if (sb.isEmpty() && words.isNotEmpty()) sb.append(String(Character.toChars(words[0].codePointAt(0))).uppercase())
    return sb.toString()
}

/**
 * Avatar rotondo: [bitmap] se disponibile, altrimenti [initials] su un colore della tavolozza.
 * [online] aggiunge il pallino verde in basso a destra; [savedMessages] mostra una stella.
 */
@Composable
fun Avatar(
    bitmap: ImageBitmap?,
    initials: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    online: Boolean = false,
    savedMessages: Boolean = false,
) {
    val color = AvatarPalette[colorIndex.mod(AvatarPalette.size)]
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier.fillMaxSize().clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            when {
                savedMessages -> Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.55f),
                )
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                )
                else -> Text(
                    text = initials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.38f).coerceAtLeast(12f).sp,
                    maxLines = 1,
                )
            }
        }
        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f)
                    .clip(CircleShape)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(size * 0.2f).clip(CircleShape).background(OnlineGreen))
            }
        }
    }
}
