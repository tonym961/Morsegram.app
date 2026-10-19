package it.iotatec.morsegram.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import it.iotatec.morsegram.wear.auth.AuthScreen
import it.iotatec.morsegram.wear.chat.ChatScreen
import it.iotatec.morsegram.wear.chats.ChatListScreen
import it.iotatec.morsegram.wear.settings.SettingsScreen
import it.iotatec.morsegram.wear.tdlib.Tdlib

/** Rotte dell'app. Chi aggiunge una schermata la registra qui (file di proprietà del core). */
object Routes {
    const val CHATS = "chats"
    const val CHAT = "chat/{chatId}"
    const val SETTINGS = "settings"
    fun chat(chatId: Long) = "chat/$chatId"
}

/**
 * Finché TDLib non è in `authorizationStateReady` mostra [AuthScreen]; poi la navigazione vera.
 * Il grafo parte da [Routes.CHATS]; swipe-to-dismiss torna indietro (standard Wear).
 */
@Composable
fun MorsegramNavHost(initialChatId: Long? = null, nonce: Int = 0) {
    val auth by Tdlib.authState.collectAsStateWithLifecycle()
    if (auth !is AuthorizationStateReady) {
        AuthScreen()
        return
    }
    val nav = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(navController = nav, startDestination = Routes.CHATS) {
        composable(Routes.CHATS) {
            ChatListScreen(
                onOpenChat = { id -> nav.navigate(Routes.chat(id)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.CHAT, arguments = listOf(navArgument("chatId") { type = NavType.LongType })) { entry ->
            val chatId = entry.arguments?.getLong("chatId") ?: return@composable
            ChatScreen(chatId = chatId, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
    if (initialChatId != null) {
        // nonce: ogni tap su una notifica (anche della stessa chat) rinaviga una volta sola
        androidx.compose.runtime.LaunchedEffect(initialChatId, nonce) { nav.navigate(Routes.chat(initialChatId)) }
    }
}
