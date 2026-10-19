package it.iotatec.morsegram.wear.notify

import android.content.Context
import dev.g000sha256.tdl.dto.MessageAnimatedEmoji
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageBasicGroupChatCreate
import dev.g000sha256.tdl.dto.MessageCall
import dev.g000sha256.tdl.dto.MessageChatAddMembers
import dev.g000sha256.tdl.dto.MessageChatChangePhoto
import dev.g000sha256.tdl.dto.MessageChatChangeTitle
import dev.g000sha256.tdl.dto.MessageChatDeleteMember
import dev.g000sha256.tdl.dto.MessageChatJoinByLink
import dev.g000sha256.tdl.dto.MessageChatJoinByRequest
import dev.g000sha256.tdl.dto.MessageChecklist
import dev.g000sha256.tdl.dto.MessageContact
import dev.g000sha256.tdl.dto.MessageContactRegistered
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.MessageDice
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageExpiredPhoto
import dev.g000sha256.tdl.dto.MessageExpiredVideo
import dev.g000sha256.tdl.dto.MessageExpiredVideoNote
import dev.g000sha256.tdl.dto.MessageExpiredVoiceNote
import dev.g000sha256.tdl.dto.MessageGame
import dev.g000sha256.tdl.dto.MessageGift
import dev.g000sha256.tdl.dto.MessageGiveaway
import dev.g000sha256.tdl.dto.MessageInvoice
import dev.g000sha256.tdl.dto.MessageLiveLocation
import dev.g000sha256.tdl.dto.MessageLocation
import dev.g000sha256.tdl.dto.MessagePaidMedia
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessagePinMessage
import dev.g000sha256.tdl.dto.MessagePoll
import dev.g000sha256.tdl.dto.MessageScreenshotTaken
import dev.g000sha256.tdl.dto.MessageSticker
import dev.g000sha256.tdl.dto.MessageStory
import dev.g000sha256.tdl.dto.MessageSupergroupChatCreate
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageUnsupported
import dev.g000sha256.tdl.dto.MessageVenue
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVideoChatEnded
import dev.g000sha256.tdl.dto.MessageVideoChatStarted
import dev.g000sha256.tdl.dto.MessageVideoNote
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.PushMessageContent
import dev.g000sha256.tdl.dto.PushMessageContentAnimation
import dev.g000sha256.tdl.dto.PushMessageContentAudio
import dev.g000sha256.tdl.dto.PushMessageContentBasicGroupChatCreate
import dev.g000sha256.tdl.dto.PushMessageContentChatAddMembers
import dev.g000sha256.tdl.dto.PushMessageContentChatChangePhoto
import dev.g000sha256.tdl.dto.PushMessageContentChatChangeTitle
import dev.g000sha256.tdl.dto.PushMessageContentChatDeleteMember
import dev.g000sha256.tdl.dto.PushMessageContentChatJoinByLink
import dev.g000sha256.tdl.dto.PushMessageContentChatJoinByRequest
import dev.g000sha256.tdl.dto.PushMessageContentChecklist
import dev.g000sha256.tdl.dto.PushMessageContentContact
import dev.g000sha256.tdl.dto.PushMessageContentContactRegistered
import dev.g000sha256.tdl.dto.PushMessageContentDocument
import dev.g000sha256.tdl.dto.PushMessageContentGame
import dev.g000sha256.tdl.dto.PushMessageContentGift
import dev.g000sha256.tdl.dto.PushMessageContentGiveaway
import dev.g000sha256.tdl.dto.PushMessageContentHidden
import dev.g000sha256.tdl.dto.PushMessageContentInvoice
import dev.g000sha256.tdl.dto.PushMessageContentLocation
import dev.g000sha256.tdl.dto.PushMessageContentMediaAlbum
import dev.g000sha256.tdl.dto.PushMessageContentMessageForwards
import dev.g000sha256.tdl.dto.PushMessageContentPaidMedia
import dev.g000sha256.tdl.dto.PushMessageContentPhoto
import dev.g000sha256.tdl.dto.PushMessageContentPoll
import dev.g000sha256.tdl.dto.PushMessageContentScreenshotTaken
import dev.g000sha256.tdl.dto.PushMessageContentSticker
import dev.g000sha256.tdl.dto.PushMessageContentStory
import dev.g000sha256.tdl.dto.PushMessageContentText
import dev.g000sha256.tdl.dto.PushMessageContentVideo
import dev.g000sha256.tdl.dto.PushMessageContentVideoChatEnded
import dev.g000sha256.tdl.dto.PushMessageContentVideoChatStarted
import dev.g000sha256.tdl.dto.PushMessageContentVideoNote
import dev.g000sha256.tdl.dto.PushMessageContentVoiceNote
import it.iotatec.morsegram.wear.R

/**
 * Testo di anteprima di un messaggio per le notifiche: stesse regole della lista chat
 * (testo com'è, media come "📷 Foto", "🎤 Vocale"...). Una riga, max [MAX_LEN] caratteri.
 */
object NotificationText {
    private const val MAX_LEN = 300

    /** Anteprima di un messaggio completo (notifica `notificationTypeNewMessage`). */
    fun preview(ctx: Context, content: MessageContent): String = clip(previewOf(ctx, content))

    /** Anteprima da push non ancora scaricato (notifica `notificationTypeNewPushMessage`). */
    fun pushPreview(ctx: Context, content: PushMessageContent): String = clip(pushPreviewOf(ctx, content))

    private fun previewOf(ctx: Context, c: MessageContent): String = when (c) {
        is MessageText -> c.text.text
        is MessagePhoto -> withCaption(ctx.getString(R.string.notify_preview_photo), c.caption.text)
        is MessageVideo -> withCaption(ctx.getString(R.string.notify_preview_video), c.caption.text)
        is MessageVoiceNote -> withCaption(ctx.getString(R.string.notify_preview_voice), c.caption.text)
        is MessageVideoNote -> ctx.getString(R.string.notify_preview_video_note)
        is MessageSticker -> withEmoji(c.sticker.emoji, ctx.getString(R.string.notify_preview_sticker))
        is MessageAnimation -> withCaption(ctx.getString(R.string.notify_preview_gif), c.caption.text)
        is MessageAudio -> withCaption(ctx.getString(R.string.notify_preview_audio), audioTitle(c.audio.title, c.audio.performer, c.audio.fileName))
        is MessageDocument -> withCaption(ctx.getString(R.string.notify_preview_file), c.document.fileName.ifBlank { c.caption.text })
        is MessageContact -> withCaption(ctx.getString(R.string.notify_preview_contact), "${c.contact.firstName} ${c.contact.lastName}".trim())
        is MessageLocation -> ctx.getString(R.string.notify_preview_location)
        is MessageLiveLocation -> ctx.getString(R.string.notify_preview_live_location)
        is MessageVenue -> withCaption(ctx.getString(R.string.notify_preview_location), c.venue.title)
        is MessagePoll -> withCaption(ctx.getString(R.string.notify_preview_poll), c.poll.question.text)
        is MessageDice -> c.emoji
        is MessageAnimatedEmoji -> c.emoji
        is MessageGame -> withCaption(ctx.getString(R.string.notify_preview_game), c.game.title)
        is MessageStory -> ctx.getString(R.string.notify_preview_story)
        is MessageCall -> ctx.getString(if (c.isVideo) R.string.notify_preview_video_call else R.string.notify_preview_call)
        is MessageChecklist -> withCaption(ctx.getString(R.string.notify_preview_checklist), c.list.title.text)
        is MessagePaidMedia -> withCaption(ctx.getString(R.string.notify_preview_paid_media), c.caption.text)
        is MessageGift -> ctx.getString(R.string.notify_preview_gift)
        is MessageInvoice -> withCaption(ctx.getString(R.string.notify_preview_invoice), c.productInfo.title)
        is MessageGiveaway -> ctx.getString(R.string.notify_preview_giveaway)
        is MessagePinMessage -> ctx.getString(R.string.notify_preview_pinned)
        is MessageScreenshotTaken -> ctx.getString(R.string.notify_preview_screenshot)
        is MessageExpiredPhoto -> ctx.getString(R.string.notify_preview_expired_photo)
        is MessageExpiredVideo -> ctx.getString(R.string.notify_preview_expired_video)
        is MessageExpiredVoiceNote -> ctx.getString(R.string.notify_preview_expired_voice)
        is MessageExpiredVideoNote -> ctx.getString(R.string.notify_preview_expired_video_note)
        is MessageContactRegistered -> ctx.getString(R.string.notify_preview_contact_registered)
        is MessageBasicGroupChatCreate -> ctx.getString(R.string.notify_preview_group_created)
        is MessageSupergroupChatCreate -> ctx.getString(R.string.notify_preview_group_created)
        is MessageChatAddMembers -> ctx.getString(R.string.notify_preview_members_added)
        is MessageChatJoinByLink, is MessageChatJoinByRequest -> ctx.getString(R.string.notify_preview_member_joined)
        is MessageChatDeleteMember -> ctx.getString(R.string.notify_preview_member_removed)
        is MessageChatChangeTitle -> withCaption(ctx.getString(R.string.notify_preview_title_changed), c.title)
        is MessageChatChangePhoto -> ctx.getString(R.string.notify_preview_photo_changed)
        is MessageVideoChatStarted -> ctx.getString(R.string.notify_preview_video_chat_started)
        is MessageVideoChatEnded -> ctx.getString(R.string.notify_preview_video_chat_ended)
        is MessageUnsupported -> ctx.getString(R.string.notify_preview_unsupported)
        else -> ctx.getString(R.string.notify_preview_message)
    }

    private fun pushPreviewOf(ctx: Context, c: PushMessageContent): String = when (c) {
        is PushMessageContentText -> c.text
        is PushMessageContentHidden -> ctx.getString(R.string.notify_new_message)
        is PushMessageContentPhoto -> withCaption(ctx.getString(R.string.notify_preview_photo), c.caption)
        is PushMessageContentVideo -> withCaption(ctx.getString(R.string.notify_preview_video), c.caption)
        is PushMessageContentVoiceNote -> ctx.getString(R.string.notify_preview_voice)
        is PushMessageContentVideoNote -> ctx.getString(R.string.notify_preview_video_note)
        is PushMessageContentSticker -> withEmoji(c.emoji, ctx.getString(R.string.notify_preview_sticker))
        is PushMessageContentAnimation -> withCaption(ctx.getString(R.string.notify_preview_gif), c.caption)
        is PushMessageContentAudio -> {
            val a = c.audio
            withCaption(ctx.getString(R.string.notify_preview_audio), if (a != null) audioTitle(a.title, a.performer, a.fileName) else "")
        }
        is PushMessageContentDocument -> withCaption(ctx.getString(R.string.notify_preview_file), c.document?.fileName.orEmpty())
        is PushMessageContentContact -> withCaption(ctx.getString(R.string.notify_preview_contact), c.name)
        is PushMessageContentLocation -> ctx.getString(if (c.isLive) R.string.notify_preview_live_location else R.string.notify_preview_location)
        is PushMessageContentPoll -> withCaption(ctx.getString(R.string.notify_preview_poll), c.question)
        is PushMessageContentGame -> withCaption(ctx.getString(R.string.notify_preview_game), c.title)
        is PushMessageContentStory -> ctx.getString(R.string.notify_preview_story)
        is PushMessageContentChecklist -> withCaption(ctx.getString(R.string.notify_preview_checklist), c.title)
        is PushMessageContentMediaAlbum -> ctx.getString(R.string.notify_preview_album)
        is PushMessageContentPaidMedia -> ctx.getString(R.string.notify_preview_paid_media)
        is PushMessageContentGift -> ctx.getString(R.string.notify_preview_gift)
        is PushMessageContentInvoice -> withCaption(ctx.getString(R.string.notify_preview_invoice), c.price)
        is PushMessageContentGiveaway -> ctx.getString(R.string.notify_preview_giveaway)
        is PushMessageContentScreenshotTaken -> ctx.getString(R.string.notify_preview_screenshot)
        is PushMessageContentMessageForwards -> ctx.getString(R.string.notify_preview_forwards, c.totalCount)
        is PushMessageContentContactRegistered -> ctx.getString(R.string.notify_preview_contact_registered)
        is PushMessageContentBasicGroupChatCreate -> ctx.getString(R.string.notify_preview_group_created)
        is PushMessageContentChatAddMembers -> ctx.getString(R.string.notify_preview_members_added)
        is PushMessageContentChatJoinByLink, is PushMessageContentChatJoinByRequest -> ctx.getString(R.string.notify_preview_member_joined)
        is PushMessageContentChatDeleteMember -> ctx.getString(if (c.isLeft) R.string.notify_preview_member_left else R.string.notify_preview_member_removed)
        is PushMessageContentChatChangeTitle -> withCaption(ctx.getString(R.string.notify_preview_title_changed), c.title)
        is PushMessageContentChatChangePhoto -> ctx.getString(R.string.notify_preview_photo_changed)
        is PushMessageContentVideoChatStarted -> ctx.getString(R.string.notify_preview_video_chat_started)
        is PushMessageContentVideoChatEnded -> ctx.getString(R.string.notify_preview_video_chat_ended)
        else -> ctx.getString(R.string.notify_preview_message)
    }

    private fun withCaption(label: String, caption: String?): String =
        if (caption.isNullOrBlank()) label else "$label · ${caption.trim()}"

    private fun withEmoji(emoji: String?, label: String): String =
        if (emoji.isNullOrBlank()) label else "$emoji $label"

    private fun audioTitle(title: String?, performer: String?, fileName: String?): String {
        val t = title?.trim().orEmpty()
        val p = performer?.trim().orEmpty()
        return when {
            t.isNotEmpty() && p.isNotEmpty() -> "$p – $t"
            t.isNotEmpty() -> t
            p.isNotEmpty() -> p
            else -> fileName?.trim().orEmpty()
        }
    }

    private fun clip(s: String): String {
        val oneLine = s.replace('\n', ' ').trim()
        return if (oneLine.length <= MAX_LEN) oneLine else oneLine.substring(0, MAX_LEN - 1) + "…"
    }
}
