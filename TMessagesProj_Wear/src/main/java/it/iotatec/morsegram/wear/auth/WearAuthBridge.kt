package it.iotatec.morsegram.wear.auth

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lato orologio del bridge di login col telefono Morsegram. Il lato telefono è
 * `org.telegram.ui.WearAuthSheet` + `org.telegram.messenger.WearAuthListenerService`: il protocollo
 * qui sotto è rispecchiato byte per byte.
 *
 * ```
 * watch -> phone  /tg-wear-auth/offer    sessionId(16) || watchPub(256)                 watchPub = g^y mod p
 * phone -> watch  /tg-wear-auth/answer   sessionId(16) || noncePhone(16) || phonePub(256)
 * phone -> watch  /tg-wear-auth/token    sessionId(16) || iv(12) || AES-256-GCM(key, iv, tag 128, no AAD,
 *                                            int32BE tokenLen || token(utf8) || int32BE dcId || byte isTest)
 * watch -> phone  /tg-wear-auth/cancel   (vuoto)
 * ```
 * con p = RFC 3526 gruppo 14 (2048 bit), g = 2, ss = encode256(phonePub^y mod p),
 * key = SHA-256(ss || sessionId || noncePhone), sas = SHA-256(ss || watchPub) → 4 emoji da confrontare.
 *
 * Trasporto: Wearable Data Layer MessageClient (stesso package + stessa firma dell'app telefono).
 */
class WearAuthBridge(context: Context) {

    /** Esito del bridge: token web di login da passare a `checkAuthenticationWebToken`. */
    class Token(val token: String, val dcId: Int, val isTest: Boolean)

    /** Nessun telefono raggiungibile via Data Layer (nessun nodo connesso, API non disponibile, invio fallito). */
    class NoPhoneException(message: String) : Exception(message)

    /** Il telefono non ha risposto entro il tempo massimo. */
    class TimeoutException(message: String) : Exception(message)

    /** Il telefono ha risposto con dati non validi (chiave fuori range, cifratura non verificata, formato errato). */
    class ProtocolException(message: String) : Exception(message)

    private val appContext = context.applicationContext
    private val messageClient = Wearable.getMessageClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)

    @Volatile private var offeredNodes: List<String> = emptyList()
    @Volatile private var peerNode: String? = null
    @Volatile private var completed = false

    /** Vero se l'offer è stata consegnata ad almeno un telefono e il token non è ancora arrivato. */
    val needsCancel: Boolean get() = offeredNodes.isNotEmpty() && !completed

    /**
     * Esegue tutto l'handshake e sospende finché non arriva il token (o fallisce). Lavora su
     * [Dispatchers.Default] (modPow a 2048 bit). Cancellare la coroutine rimuove il listener;
     * chi cancella deve poi chiamare [sendCancel] per chiudere il foglio sul telefono.
     */
    suspend fun run(
        onOfferSent: suspend (nodes: Int) -> Unit,
        onEmojis: suspend (emojis: List<String>) -> Unit,
    ): Token = withContext(Dispatchers.Default) {
        val session = Session()
        val nodes = try {
            nodeClient.connectedNodes.await()
        } catch (e: Exception) {
            Log.w(TAG, "connectedNodes: ${e.message}")
            throw NoPhoneException("wearable api: ${e.message}")
        }
        if (nodes.isEmpty()) throw NoPhoneException("no connected nodes")

        val inbox = Channel<MessageEvent>(Channel.UNLIMITED)
        val listener = MessageClient.OnMessageReceivedListener { event -> inbox.trySend(event) }
        messageClient.addListener(listener).await()
        try {
            val offer = session.offer()
            val delivered = nodes.filter { node ->
                runCatching { messageClient.sendMessage(node.id, PATH_OFFER, offer).await() }
                    .onFailure { Log.w(TAG, "offer to ${node.id} (${node.displayName}) failed: ${it.message}") }
                    .isSuccess
            }
            if (delivered.isEmpty()) throw NoPhoneException("offer not delivered")
            offeredNodes = delivered.map { it.id }
            Log.d(TAG, "offer delivered to ${delivered.size} node(s), session ${session.sessionId.hex()}")
            onOfferSent(delivered.size)
            awaitToken(session, inbox, onEmojis)
        } finally {
            messageClient.removeListener(listener)
            inbox.close()
        }
    }

    /** Consuma answer e token dalla coda dei messaggi; ignora quelli di altre sessioni. */
    private suspend fun awaitToken(
        session: Session,
        inbox: Channel<MessageEvent>,
        onEmojis: suspend (emojis: List<String>) -> Unit,
    ): Token {
        var timeoutMs = ANSWER_TIMEOUT_MS
        while (true) {
            val event = withTimeoutOrNull(timeoutMs) { inbox.receive() }
                ?: throw TimeoutException("no reply from phone")
            when (event.path) {
                PATH_ANSWER -> {
                    val emojis = session.onAnswer(event.data) ?: continue
                    peerNode = event.sourceNodeId
                    timeoutMs = TOKEN_TIMEOUT_MS
                    Log.d(TAG, "answer from ${event.sourceNodeId}; sas emojis=$emojis")
                    onEmojis(emojis)
                }
                PATH_TOKEN -> {
                    val token = session.onToken(event.data) ?: continue
                    completed = true
                    Log.d(TAG, "token received (dcId=${token.dcId}, test=${token.isTest})")
                    return token
                }
            }
        }
    }

    /** Avvisa il telefono che l'orologio ha abbandonato la sessione (chiude il foglio di conferma). */
    suspend fun sendCancel() {
        val targets = peerNode?.let { listOf(it) } ?: offeredNodes
        offeredNodes = emptyList()
        for (id in targets) {
            runCatching { messageClient.sendMessage(id, PATH_CANCEL, ByteArray(0)).await() }
                .onFailure { Log.w(TAG, "cancel to $id failed: ${it.message}") }
        }
    }

    /** Stato crittografico di una sessione: esponente privato y, chiave condivisa dopo l'answer. */
    private class Session {
        val sessionId = ByteArray(SESSION_ID_LEN).also { random.nextBytes(it) }
        private val y = BigInteger(DH_BITS, random)
        val watchPub: ByteArray
        private var sharedKey: ByteArray? = null

        init {
            val gy = DH_G.modPow(y, DH_P)
            check(isValidPub(gy)) { "our pubkey invalid (extremely unlikely)" }
            watchPub = encode256(gy)
        }

        fun offer(): ByteArray = sessionId + watchPub

        /** answer = sessionId(16) || noncePhone(16) || phonePub(256). Null = non per questa sessione. */
        fun onAnswer(data: ByteArray?): List<String>? {
            if (data == null || data.size != SESSION_ID_LEN + NONCE_LEN + DH_PUBKEY_LEN) return null
            if (!MessageDigest.isEqual(sessionId, data.copyOfRange(0, SESSION_ID_LEN))) return null
            if (sharedKey != null) return null // answer duplicata
            val noncePhone = data.copyOfRange(SESSION_ID_LEN, SESSION_ID_LEN + NONCE_LEN)
            val phonePubBytes = data.copyOfRange(SESSION_ID_LEN + NONCE_LEN, data.size)
            val phonePub = BigInteger(1, phonePubBytes)
            if (!isValidPub(phonePub)) throw ProtocolException("peer pubkey out of range")
            val ss = encode256(phonePub.modPow(y, DH_P))
            sharedKey = sha256(ss, sessionId, noncePhone)
            val sas = sha256(ss, watchPub)
            return emojify(sas, 4)
        }

        /** token = sessionId(16) || iv(12) || ciphertext+tag. Null = non per questa sessione. */
        fun onToken(data: ByteArray?): Token? {
            if (data == null || data.size < SESSION_ID_LEN + AES_GCM_IV_LEN + AES_GCM_TAG_LEN) return null
            if (!MessageDigest.isEqual(sessionId, data.copyOfRange(0, SESSION_ID_LEN))) return null
            val key = sharedKey ?: throw ProtocolException("token before answer")
            val iv = data.copyOfRange(SESSION_ID_LEN, SESSION_ID_LEN + AES_GCM_IV_LEN)
            val ct = data.copyOfRange(SESSION_ID_LEN + AES_GCM_IV_LEN, data.size)
            val plain = try {
                aeadDecrypt(key, iv, ct)
            } catch (e: Exception) {
                throw ProtocolException("token decrypt failed: ${e.message}")
            }
            return parseToken(plain)
        }
    }

    companion object {
        private const val TAG = "WearAuthBridge"

        const val PATH_OFFER = "/tg-wear-auth/offer"
        const val PATH_ANSWER = "/tg-wear-auth/answer"
        const val PATH_TOKEN = "/tg-wear-auth/token"
        const val PATH_CANCEL = "/tg-wear-auth/cancel"

        private const val SESSION_ID_LEN = 16
        private const val NONCE_LEN = 16
        private const val DH_PUBKEY_LEN = 256
        private const val DH_BITS = 2048
        private const val AES_GCM_IV_LEN = 12
        private const val AES_GCM_TAG_LEN = 16

        private const val ANSWER_TIMEOUT_MS = 3 * 60_000L
        private const val TOKEN_TIMEOUT_MS = 3 * 60_000L

        private val random = SecureRandom()

        /** RFC 3526, gruppo 14 (2048 bit) — identico a WearAuthSheet.DH_P. */
        private val DH_P = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF",
            16,
        )
        private val DH_G: BigInteger = BigInteger.valueOf(2)

        private fun isValidPub(v: BigInteger): Boolean =
            v > BigInteger.ONE && v < DH_P.subtract(BigInteger.ONE)

        /** 256 byte big-endian, senza il byte di segno di `toByteArray`, con zeri a sinistra se corto. */
        private fun encode256(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == DH_PUBKEY_LEN -> raw
                raw.size == DH_PUBKEY_LEN + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
                raw.size < DH_PUBKEY_LEN -> ByteArray(DH_PUBKEY_LEN).also { raw.copyInto(it, DH_PUBKEY_LEN - raw.size) }
                else -> error("unexpected DH value size ${raw.size}")
            }
        }

        private fun sha256(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            for (p in parts) md.update(p)
            return md.digest()
        }

        private fun aeadDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AES_GCM_TAG_LEN * 8, iv))
            return cipher.doFinal(ciphertext)
        }

        /** int32BE tokenLen || token(utf8) || int32BE dcId || byte isTest */
        private fun parseToken(plain: ByteArray): Token {
            val buf = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN)
            if (buf.remaining() < 4 + 4 + 1) throw ProtocolException("token payload too short")
            val len = buf.int
            if (len < 0 || len > buf.remaining() - 5) throw ProtocolException("bad token length $len")
            val tokenBytes = ByteArray(len).also { buf.get(it) }
            val dcId = buf.int
            val isTest = buf.get() != 0.toByte()
            val token = String(tokenBytes, Charsets.UTF_8)
            if (token.isEmpty()) throw ProtocolException("empty token")
            return Token(token, dcId, isTest)
        }

        private fun emojify(sas: ByteArray, count: Int): List<String> {
            val table = EMOJIS
            return List(count) { i -> table[(bytesToLong(sas, i * 8) % table.size).toInt()] }
        }

        /** Come WearAuthSheet.bytesToLong: primo byte mascherato con 0x7F → sempre non negativo. */
        private fun bytesToLong(a: ByteArray, off: Int): Long =
            ((a[off].toLong() and 0x7F) shl 56) or
                ((a[off + 1].toLong() and 0xFF) shl 48) or
                ((a[off + 2].toLong() and 0xFF) shl 40) or
                ((a[off + 3].toLong() and 0xFF) shl 32) or
                ((a[off + 4].toLong() and 0xFF) shl 24) or
                ((a[off + 5].toLong() and 0xFF) shl 16) or
                ((a[off + 6].toLong() and 0xFF) shl 8) or
                (a[off + 7].toLong() and 0xFF)

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        /**
         * Tabella SAS: copia verbatim di WearAuthSheet.getEmojis(), duplicati inclusi
         * (la lunghezza dell'array entra nel modulo, quindi deve coincidere esattamente).
         */
        private val EMOJIS: Array<String> = arrayOf(
            "👋", "👍", "👎", "👌", "👊", "🤟", "🫵", "👏", "🤝", "✍", "💪", "👀", "👅", "🥶",
            "🤡", "💀", "👽", "😈", "😎", "🤠", "🤩", "😍", "🤯", "🦄", "🐶", "🐷", "🐔", "🐥",
            "🦊", "🐙", "🐸", "🐳", "🦉", "🦆", "🐢", "🦖", "🐵", "🐝", "🦁", "🐧", "🦋", "🐬",
            "🦀", "🐌", "🦠", "🐠", "🌵", "💐", "💐", "🎄", "🍄", "🍔", "🍕", "☕", "🍩", "🍪",
            "🎂", "🍫", "🍭", "🍎", "🥥", "🍒", "🌶", "🥒", "🥦", "🍇", "🍋", "🍓", "🍌", "🍍",
            "🍆", "🌽", "🍺", "🍷", "🍾", "🍦", "🍰", "🍞", "🍖", "🌭", "🧊", "🍳", "⭐", "☁",
            "🚀", "🎈", "💎", "💡", "🔑", "❄", "🔎", "👠", "👕", "👗", "👖", "👙", "👜", "👓",
            "🎀", "💄", "💍", "♠", "❤", "♦", "♣", "🌈", "🌊", "🎃", "👻", "🎁", "🔮", "🎥",
            "💿", "💻", "📡", "🔉", "⏳", "🔒", "🚗", "🔱", "🔗", "🎲", "🎮", "⚽", "🎳", "🏁",
            "🏆", "🎸", "💣", "🚽", "🎹", "🎤", "🎨", "🔫", "💊", "💰", "📦", "📅", "📚", "❗",
            "❓", "💯", "💦", "💤", "🌍", "🏝", "🚂", "🛢", "🛹", "🚢", "✈", "🛎", "🧳", "🌖",
            "🌞", "🔥", "🏓", "🎰", "🧸", "🪩", "🎭", "👑", "🎩", "🧢", "🔈", "🔋", "🕯", "✏",
            "💼", "📌", "✂", "🗑", "🛡", "⚙", "🧲", "🪏", "⚖", "🧪", "🚪", "🫧", "🛒", "🪑",
            "🗿", "🏁", "🏴‍☠", "📊", "🥁", "🎧", "🎵", "🧩", "⛳", "🥇", "🥈", "🥈", "🌪", "⛺",
            "🧭", "🫆", "🧠", "💋"
        )
    }
}
