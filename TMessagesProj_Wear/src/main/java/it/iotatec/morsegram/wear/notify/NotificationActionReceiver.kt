package it.iotatec.morsegram.wear.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputMessageReplyToMessage
import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.MessageSourceNotification
import dev.g000sha256.tdl.dto.MessageTopicForum
import dev.g000sha256.tdl.dto.TextEntity
import it.iotatec.morsegram.wear.R
import it.iotatec.morsegram.wear.tdlib.Tdlib
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Azioni delle notifiche messaggi: risposta inline (RemoteInput "reply"), "Segna come letto"
 * e chiusura della notifica da parte dell'utente. Non esportato: lo invocano solo i PendingIntent
 * creati da [MessageNotifications].
 *
 * Il lavoro TDLib è asincrono ([goAsync]); il processo può essere stato appena risvegliato, per cui
 * si attende (con timeout) che la sessione sia `authorizationStateReady`.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        if (chatId == 0L) return
        val app = context.applicationContext
        MessageNotifications.ensureStarted(app)
        val groups = intent.getIntArrayExtra(EXTRA_GROUP_IDS) ?: IntArray(0)
        val maxIds = intent.getIntArrayExtra(EXTRA_MAX_NOTIFICATION_IDS) ?: IntArray(0)
        val messageIds = intent.getLongArrayExtra(EXTRA_MESSAGE_IDS) ?: LongArray(0)
        val replyText = if (action == ACTION_REPLY) RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty() else ""
        if (action == ACTION_REPLY && replyText.isEmpty()) {
            MessageNotifications.onReplyFailed(chatId)
            return
        }
        if (action == ACTION_MARK_READ) MessageNotifications.onMarkedRead(chatId)
        if (action == ACTION_DISMISS) MessageNotifications.onDismissed(chatId)

        val pending = goAsync()
        Tdlib.scope.launch {
            try {
                val ready = withTimeoutOrNull(READY_TIMEOUT_MS) { Tdlib.authState.filterIsInstance<AuthorizationStateReady>().first() } != null
                if (!ready) {
                    Log.w(TAG, "$action: sessione non pronta, azione ignorata")
                    if (action == ACTION_REPLY) replyFailed(app, chatId)
                    return@launch
                }
                when (action) {
                    ACTION_REPLY -> reply(app, chatId, replyText, groups, maxIds, messageIds, intent.getIntExtra(EXTRA_FORUM_TOPIC_ID, 0), intent.getLongExtra(EXTRA_REPLY_TO_MESSAGE_ID, 0L))
                    ACTION_MARK_READ -> markRead(chatId, groups, maxIds, messageIds)
                    ACTION_DISMISS -> removeGroups(groups, maxIds)
                    else -> Log.w(TAG, "azione sconosciuta $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "$action chat $chatId: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun reply(app: Context, chatId: Long, text: String, groups: IntArray, maxIds: IntArray, messageIds: LongArray, forumTopicId: Int, replyToMessageId: Long) {
        val topic = if (forumTopicId != 0) MessageTopicForum(forumTopicId) else null
        val replyTo = if (replyToMessageId != 0L) InputMessageReplyToMessage(replyToMessageId, null, 0, "") else null
        val content = InputMessageText(FormattedText(text, emptyArray<TextEntity>()), null, false)
        val sent = Tdlib.req { sendMessage(chatId, topic, replyTo, null, null, content) }
        sent.onSuccess { message ->
            Log.i(TAG, "risposta accodata in chat $chatId (msg ${message.id})")
            MessageNotifications.onReplySent(chatId, text, message.id)
            // come l'app ufficiale: rispondere dalla notifica segna la chat come letta (ricevute di lettura inviate)
            if (messageIds.isNotEmpty()) {
                Tdlib.req { viewMessages(chatId, messageIds, MessageSourceNotification(), true) }
                    .onFailure { Log.w(TAG, "viewMessages: ${it.message}") }
            }
            removeGroups(groups, maxIds)
        }.onFailure { e ->
            Log.w(TAG, "sendMessage fallita: ${e.message}")
            replyFailed(app, chatId)
        }
    }

    private suspend fun markRead(chatId: Long, groups: IntArray, maxIds: IntArray, messageIds: LongArray) {
        if (messageIds.isNotEmpty()) {
            Tdlib.req { viewMessages(chatId, messageIds, MessageSourceNotification(), true) }
                .onFailure { Log.w(TAG, "viewMessages: ${it.message}") }
        }
        removeGroups(groups, maxIds)
    }

    /** Dice a TDLib che l'utente ha rimosso i gruppi di notifica (così non li ripropone). */
    private suspend fun removeGroups(groups: IntArray, maxIds: IntArray) {
        for (i in groups.indices) {
            val maxId = maxIds.getOrElse(i) { Int.MAX_VALUE }
            Tdlib.req { removeNotificationGroup(groups[i], maxId) }
                .onFailure { Log.d(TAG, "removeNotificationGroup(${groups[i]}): ${it.message}") }
        }
    }

    private fun replyFailed(app: Context, chatId: Long) {
        MessageNotifications.onReplyFailed(chatId)
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(app, R.string.notify_reply_failed, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "toast: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "NotifyAction"
        private const val READY_TIMEOUT_MS = 8_000L

        const val KEY_REPLY = "reply"
        const val ACTION_REPLY = "it.iotatec.morsegram.wear.notify.REPLY"
        const val ACTION_MARK_READ = "it.iotatec.morsegram.wear.notify.MARK_READ"
        const val ACTION_DISMISS = "it.iotatec.morsegram.wear.notify.DISMISS"

        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_GROUP_IDS = "group_ids"
        const val EXTRA_MAX_NOTIFICATION_IDS = "max_notification_ids"
        const val EXTRA_MESSAGE_IDS = "message_ids"
        const val EXTRA_FORUM_TOPIC_ID = "forum_topic_id"
        const val EXTRA_REPLY_TO_MESSAGE_ID = "reply_to_message_id"

        fun replyIntent(ctx: Context, chatId: Long, groupIds: IntArray, maxIds: IntArray, messageIds: LongArray, forumTopicId: Int, replyToMessageId: Long): Intent =
            base(ctx, ACTION_REPLY, chatId, groupIds, maxIds)
                .putExtra(EXTRA_MESSAGE_IDS, messageIds)
                .putExtra(EXTRA_FORUM_TOPIC_ID, forumTopicId)
                .putExtra(EXTRA_REPLY_TO_MESSAGE_ID, replyToMessageId)

        fun markReadIntent(ctx: Context, chatId: Long, groupIds: IntArray, maxIds: IntArray, messageIds: LongArray): Intent =
            base(ctx, ACTION_MARK_READ, chatId, groupIds, maxIds).putExtra(EXTRA_MESSAGE_IDS, messageIds)

        fun dismissIntent(ctx: Context, chatId: Long, groupIds: IntArray, maxIds: IntArray): Intent =
            base(ctx, ACTION_DISMISS, chatId, groupIds, maxIds)

        /** Data URI per chat: PendingIntent distinti anche a parità di request code. */
        private fun base(ctx: Context, action: String, chatId: Long, groupIds: IntArray, maxIds: IntArray): Intent =
            Intent(ctx, NotificationActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("morsegram://notify/$chatId"))
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_GROUP_IDS, groupIds)
                .putExtra(EXTRA_MAX_NOTIFICATION_IDS, maxIds)
    }
}
