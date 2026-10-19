package it.iotatec.morsegram.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import it.iotatec.morsegram.wear.tdlib.Tdlib
import it.iotatec.morsegram.wear.ui.theme.MorsegramTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Chat richiesta da una notifica: (chatId, contatore) così due tap sulla stessa chat navigano entrambi. */
    private var pendingChat by mutableStateOf<Pair<Long, Int>?>(null)
    private var tapCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tdlib.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        intent?.chatIdExtra()?.let { pendingChat = it to ++tapCounter }
        // setContent UNA sola volta: la chat richiesta arriva come stato osservato dalla composizione
        setContent {
            MorsegramTheme {
                MorsegramNavHost(initialChatId = pendingChat?.first, nonce = pendingChat?.second ?: 0)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.chatIdExtra()?.let { pendingChat = it to ++tapCounter }
    }

    private fun Intent.chatIdExtra(): Long? = if (hasExtra(EXTRA_CHAT_ID)) getLongExtra(EXTRA_CHAT_ID, 0L).takeIf { it != 0L } else null

    companion object {
        /** Extra usato dalle notifiche per aprire direttamente una chat. */
        const val EXTRA_CHAT_ID = "chat_id"
    }
}
